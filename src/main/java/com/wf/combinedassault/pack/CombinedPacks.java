package com.wf.combinedassault.pack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wf.combinedassault.CombinedAssault;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforgespi.language.IModFileInfo;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Jar packs ({@code combinedassault_packs/<dir>} in mod files, mod id order), then {@code <gamedir>/combinedassault/packs}
 * (dirs + zips, name order). Problems across all packs => one {@link IllegalStateException}. Read on both sides from
 * disk; {@link PackHandshake} keeps client and server identical.
 */
public final class CombinedPacks {

    public static final String JAR_PACKS = "combinedassault_packs";
    public static final String DEFS = CombinedAssault.MOD_ID;
    private static final Pattern PACK_ID = Pattern.compile("[a-z0-9_.-]+");
    private static final Set<String> META_KEYS = Set.of("id", "requires", "license", "authors");

    private static volatile List<CombinedPack> packs = List.of();
    private static List<FileSystem> archives = List.of();
    private static List<Path> jarRoots = List.of();
    private static Map<Path, String> jarMods = Map.of();

    private CombinedPacks() {
    }

    public static Path packsDir() {
        return FMLPaths.GAMEDIR.get().resolve(CombinedAssault.MOD_ID).resolve("packs");
    }

    public static List<CombinedPack> packs() {
        return packs;
    }

    /** Mod construction: mod files fixed until restart. */
    public static void discover() {
        List<Path> roots = new ArrayList<>();
        Map<Path, String> mods = new LinkedHashMap<>();
        List<IModFileInfo> files = new ArrayList<>(ModList.get().getModFiles());
        files.sort(Comparator.comparing(f -> f.getMods().isEmpty() ? "" : f.getMods().getFirst().getModId()));
        for (IModFileInfo f : files) {
            Path root = f.getFile().findResource(JAR_PACKS);
            if (f.getMods().isEmpty() || !Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> dirs = Files.list(root)) {
                for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                    roots.add(dir);
                    mods.put(dir, f.getMods().getFirst().getModId());
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot list " + root, e);
            }
        }
        jarRoots = List.copyOf(roots);
        jarMods = Map.copyOf(mods);
        rescan();
    }

    /** Rereads every pack; jar roots from {@link #discover}. */
    public static synchronized void rescan() {
        long start = System.nanoTime();
        for (FileSystem fs : archives) {
            try {
                fs.close();
            } catch (IOException ignored) {
            }
        }
        List<String> problems = new ArrayList<>();
        List<CombinedPack> found = new ArrayList<>();
        List<FileSystem> opened = new ArrayList<>();
        for (Path root : jarRoots) {
            read(root, root, CombinedPack.Origin.JAR, jarMods.get(root), found, problems);
        }
        Path dir = packsDir();
        try {
            Files.createDirectories(dir);
            try (Stream<Path> entries = Files.list(dir)) {
                for (Path entry : entries.sorted().toList()) {
                    if (Files.isDirectory(entry)) {
                        read(entry, entry, CombinedPack.Origin.DIRECTORY, "", found, problems);
                    } else if (entry.getFileName().toString().endsWith(".zip")) {
                        FileSystem fs = FileSystems.newFileSystem(entry);
                        opened.add(fs);
                        read(entry, fs.getPath("/"), CombinedPack.Origin.ARCHIVE, "", found, problems);
                    }
                }
            }
        } catch (IOException e) {
            problems.add(dir + ": " + e);
        }
        Map<String, CombinedPack> byId = new LinkedHashMap<>();
        for (CombinedPack pack : found) {
            CombinedPack prev = byId.putIfAbsent(pack.id(), pack);
            if (prev != null) {
                problems.add("pack id " + pack.id() + " twice: " + prev.source() + ", " + pack.source());
            }
        }
        for (CombinedPack pack : found) {
            for (String required : pack.requires()) {
                if (!byId.containsKey(required)) {
                    problems.add(pack.id() + ": requires pack " + required + ", not installed");
                }
            }
        }
        archives = List.copyOf(opened);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("CombinedAssault packs:\n  " + String.join("\n  ", problems));
        }
        packs = List.copyOf(found);
        CombinedAssault.LOGGER.info("CombinedAssault packs ({} jar + {}): {} in {} ms", jarRoots.size(), dir, packs.size(),
                (System.nanoTime() - start) / 1_000_000);
        for (CombinedPack p : packs) {
            CombinedAssault.LOGGER.info("  {} ({}{} {}): sha256 {}", p.id(), p.origin(), p.mod().isEmpty() ? "" : " " + p.mod(),
                    p.source().getFileName(), p.sha256());
        }
    }

    private static void read(Path source, Path root, CombinedPack.Origin origin, String mod, List<CombinedPack> out,
                             List<String> problems) {
        Path mcmeta = root.resolve("pack.mcmeta");
        if (!Files.isRegularFile(mcmeta)) {
            problems.add(source + ": no pack.mcmeta");
            return;
        }
        try {
            JsonObject meta = JsonParser.parseString(Files.readString(mcmeta)).getAsJsonObject();
            String description = meta.getAsJsonObject("pack").get("description").getAsString();
            JsonObject own = meta.getAsJsonObject(DEFS);
            if (own == null || !own.has("id")) {
                problems.add(source + ": pack.mcmeta has no " + DEFS + ".id");
                return;
            }
            for (String key : own.keySet()) {
                if (!META_KEYS.contains(key)) {
                    problems.add(source + ": pack.mcmeta " + DEFS + "." + key + ": unknown field");
                }
            }
            String id = own.get("id").getAsString();
            if (!PACK_ID.matcher(id).matches()) {
                problems.add(source + ": pack id " + id + " not [a-z0-9_.-]+");
                return;
            }
            List<String> requires = new ArrayList<>();
            if (own.has("requires")) {
                own.getAsJsonArray("requires").forEach(e -> requires.add(e.getAsString()));
            }
            out.add(new CombinedPack(id, description, List.copyOf(requires), source, root, origin, mod, hash(root)));
        } catch (Exception e) {
            problems.add(source + ": " + e);
        }
    }

    private static String hash(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String top : new String[]{DEFS, "assets"}) {
            Path base = root.resolve(top);
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(Files::isRegularFile).filter(p -> top.equals(DEFS) || model(p)).forEach(files::add);
            }
        }
        files.sort(Comparator.comparing(p -> root.relativize(p).toString().replace('\\', '/')));
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        for (Path file : files) {
            byte[] bytes = Files.readAllBytes(file);
            digest.update(root.relativize(file).toString().replace('\\', '/').getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Long.toString(bytes.length).getBytes(StandardCharsets.UTF_8));
            digest.update(bytes);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean model(Path p) {
        String name = p.getFileName().toString();
        return name.endsWith(".gltf") || name.endsWith(".glb") || name.endsWith(".bin");
    }

    /** {@code assets/<ns>/<path>} of the first pack that has it; empty if none. Either side. */
    public static Optional<byte[]> asset(ResourceLocation id) throws IOException {
        for (CombinedPack pack : packs) {
            Path file = pack.root().resolve("assets").resolve(id.getNamespace()).resolve(id.getPath());
            if (Files.isRegularFile(file)) {
                return Optional.of(Files.readAllBytes(file));
            }
        }
        return Optional.empty();
    }

    /** Raw file of one pack: {@code relative} under its root. */
    @Nullable
    public static byte[] file(CombinedPack pack, String relative) throws IOException {
        Path file = pack.root().resolve(relative);
        return Files.isRegularFile(file) ? Files.readAllBytes(file) : null;
    }

    public record Def(ResourceLocation id, CombinedPack pack, JsonObject json) {
    }

    /** {@code combinedassault/<ns>/<type>/<path>.json} => {@code <ns>:<path>}; duplicate id across packs => throws. */
    public static Map<ResourceLocation, Def> defs(String type) {
        Map<ResourceLocation, Def> out = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (CombinedPack pack : packs) {
            Path base = pack.root().resolve(DEFS);
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> namespaces = Files.list(base)) {
                for (Path ns : namespaces.filter(Files::isDirectory).sorted().toList()) {
                    Path dir = ns.resolve(type);
                    if (!Files.isDirectory(dir)) {
                        continue;
                    }
                    try (Stream<Path> walk = Files.walk(dir)) {
                        for (Path file : walk.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                            String path = dir.relativize(file).toString().replace('\\', '/');
                            path = path.substring(0, path.length() - ".json".length());
                            ResourceLocation id = ResourceLocation.tryBuild(ns.getFileName().toString().replace("/", ""), path);
                            if (id == null) {
                                problems.add(pack.id() + ": " + file + ": invalid id");
                                continue;
                            }
                            JsonElement json = JsonParser.parseString(Files.readString(file));
                            Def prev = out.putIfAbsent(id, new Def(id, pack, json.getAsJsonObject()));
                            if (prev != null) {
                                problems.add(type + " " + id + " in " + prev.pack().id() + " and " + pack.id());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                problems.add(pack.id() + ": " + e);
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("CombinedAssault " + type + ":\n  " + String.join("\n  ", problems));
        }
        return out;
    }

    /** Every pack as a vanilla pack of the event's type: lang, sounds, textures, models; recipes, tags. */
    public static void onAddPackFinders(AddPackFindersEvent event) {
        PackType type = event.getPackType();
        event.addRepositorySource(consumer -> {
            for (CombinedPack pack : packs) {
                PackLocationInfo info = new PackLocationInfo(DEFS + "/" + pack.id(), Component.literal(pack.description()),
                        PackSource.BUILT_IN, Optional.empty());
                Pack.ResourcesSupplier supplier = new PathPackResources.PathResourcesSupplier(pack.root());
                Pack.Metadata metadata = new Pack.Metadata(Component.literal(pack.description()),
                        net.minecraft.server.packs.repository.PackCompatibility.COMPATIBLE,
                        net.minecraft.world.flag.FeatureFlagSet.of(), List.of(), false);
                consumer.accept(new Pack(info, supplier, metadata,
                        new PackSelectionConfig(true, Pack.Position.TOP, false)));
            }
        });
        CombinedAssault.LOGGER.debug("{} packs offered as {} ({})", packs.size(), type,
                SharedConstants.getCurrentVersion().getPackVersion(type));
    }
}
