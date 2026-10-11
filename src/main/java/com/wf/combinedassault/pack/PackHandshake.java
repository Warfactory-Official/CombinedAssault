package com.wf.combinedassault.pack;

import com.wf.combinedassault.CombinedAssault;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ConfigurationTask;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Configuration task: server sends its {@code (packId, sha256)} list, client reconciles ({@link #clientSync}) and
 * answers with its own, server compares. Runs after NeoForge registry sync => a missing pack whose items the server
 * has disconnects there first. Play phase: same list after a content reload; client mismatch => client disconnects.
 */
public final class PackHandshake {
    public static final ConfigurationTask.Type TASK = new ConfigurationTask.Type(CombinedAssault.modLocation("pack_check"));

    private PackHandshake() {
    }

    /** Client: server list -> problem after reconciling local content; null => matches. */
    private static Function<List<Entry>, String> clientSync = server -> mismatch(server, local());

    /** {@code mod}: providing mod of a jar pack, "" => packs dir. */
    public record Entry(String id, String sha256, String mod) {
        static final StreamCodec<ByteBuf, Entry> CODEC = StreamCodec.composite(ByteBufCodecs.STRING_UTF8, Entry::id,
                ByteBufCodecs.STRING_UTF8, Entry::sha256, ByteBufCodecs.STRING_UTF8, Entry::mod, Entry::new);

        public static Entry of(CombinedPack p) {
            return new Entry(p.id(), p.sha256(), p.mod());
        }
    }

    public record ServerPacks(List<Entry> packs) implements CustomPacketPayload {
        public static final Type<ServerPacks> TYPE = new Type<>(CombinedAssault.modLocation("server_packs"));
        static final StreamCodec<ByteBuf, ServerPacks> CODEC = Entry.CODEC.apply(ByteBufCodecs.list())
                .map(ServerPacks::new, ServerPacks::packs);

        @Override
        public Type<ServerPacks> type() {
            return TYPE;
        }
    }

    public record ClientPacks(List<Entry> packs) implements CustomPacketPayload {
        public static final Type<ClientPacks> TYPE = new Type<>(CombinedAssault.modLocation("client_packs"));
        static final StreamCodec<ByteBuf, ClientPacks> CODEC = Entry.CODEC.apply(ByteBufCodecs.list())
                .map(ClientPacks::new, ClientPacks::packs);

        @Override
        public Type<ClientPacks> type() {
            return TYPE;
        }
    }

    record Task(List<Entry> packs) implements ICustomConfigurationTask {
        @Override
        public void run(Consumer<CustomPacketPayload> sender) {
            sender.accept(new ServerPacks(packs));
        }

        @Override
        public Type type() {
            return TASK;
        }
    }

    public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .commonToClient(ServerPacks.TYPE, ServerPacks.CODEC, PackHandshake::onServerPacks)
                .configurationToServer(ClientPacks.TYPE, ClientPacks.CODEC, PackHandshake::onClientPacks);
    }

    public static void clientSync(Function<List<Entry>, String> sync) {
        clientSync = sync;
    }

    /** After a server content reload. */
    public static void broadcast() {
        PacketDistributor.sendToAllPlayers(new ServerPacks(local()));
    }

    private static void onServerPacks(ServerPacks p, IPayloadContext ctx) {
        String problem = clientSync.apply(p.packs());
        if (ctx.protocol() == ConnectionProtocol.CONFIGURATION) {
            ctx.reply(new ClientPacks(local()));
        } else if (problem != null) {
            ctx.disconnect(Component.literal(problem));
        }
    }

    public static void onRegisterTasks(RegisterConfigurationTasksEvent event) {
        event.register(new Task(local()));
    }

    private static void onClientPacks(ClientPacks p, IPayloadContext ctx) {
        String problem = mismatch(local(), p.packs());
        if (problem != null) {
            ctx.disconnect(Component.literal(problem));
        } else {
            ctx.finishCurrentTask(TASK);
        }
    }

    public static List<Entry> local() {
        return CombinedPacks.packs().stream().map(Entry::of).toList();
    }

    /** Null => identical sets. */
    @Nullable
    public static String mismatch(List<Entry> server, List<Entry> client) {
        // Duplicate client id => "" => reported as different, never a merge exception.
        Map<String, String> c = client.stream().collect(Collectors.toMap(Entry::id, Entry::sha256, (a, b) -> ""));
        Map<String, String> s = server.stream().collect(Collectors.toMap(Entry::id, Entry::sha256));
        List<String> missing = new ArrayList<>(), differ = new ArrayList<>(), extra = new ArrayList<>();
        for (Entry e : server) {
            String h = c.get(e.id());
            if (h == null) {
                missing.add(e.id());
            } else if (!h.equals(e.sha256())) {
                differ.add(e.id());
            }
        }
        c.keySet().stream().filter(id -> !s.containsKey(id)).sorted().forEach(extra::add);
        if (missing.isEmpty() && differ.isEmpty() && extra.isEmpty()) {
            return null;
        }
        Map<String, String> mods = new TreeMap<>();
        Stream.concat(server.stream(), client.stream()).filter(e -> !e.mod().isEmpty())
                .forEach(e -> mods.put(e.id(), e.mod()));
        List<String> all = Stream.of(missing, differ, extra).flatMap(List::stream).toList();
        StringBuilder b = new StringBuilder("CombinedAssault packs differ from the server.");
        Map<String, List<String>> parts = new LinkedHashMap<>();
        parts.put("Missing", missing);
        parts.put("Different contents", differ);
        parts.put("Not on the server", extra);
        parts.forEach((k, v) -> {
            if (!v.isEmpty()) {
                b.append('\n').append(k).append(": ").append(String.join(", ", v));
            }
        });
        List<String> jar = all.stream().filter(mods::containsKey).map(id -> id + " (mod " + mods.get(id) + ")")
                .toList();
        if (!jar.isEmpty()) {
            b.append("\nFrom mod jars: ").append(String.join(", ", jar))
                    .append(". Use the server's versions of those mods.");
        }
        if (jar.size() < all.size()) {
            b.append("\nInstall exactly the server's packs into <game dir>/combinedassault/packs.");
        }
        return b.toString();
    }
}
