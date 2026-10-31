package com.wf.combinedassault.radar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class RadarConfig {

    public record Snapshot(boolean recon, double radarRange, double receiverRange, int broadcastInterval) {
    }

    static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue RECON;
    private static final ModConfigSpec.DoubleValue RADAR_RANGE;
    private static final ModConfigSpec.DoubleValue RECEIVER_RANGE;
    private static final ModConfigSpec.IntValue BROADCAST_INTERVAL;
    private static volatile Snapshot snapshot = new Snapshot(true, 512.0, 1024.0, 5);
    private static volatile List<String> broadcastWhitelist = List.of();

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        RECON = builder.comment("Contribute vehicles to the WFLib sensor net, and let a vehicle's radar join it as a sensor.",
                        "Off keeps every radar on its own private scan.")
                .define("recon", true);
        RADAR_RANGE = builder.comment("Ceiling on the base range a vehicle radar contributes to the sensor net, in blocks.",
                        "The net detects a reference target at this range and scales by the fourth root of signature, so a raw max_scan_distance of a few thousand would make one fighter outrange every fixed installation in the game.")
                .defineInRange("radarRange", 512.0, 16.0, 4096.0);
        RECEIVER_RANGE = builder.comment("How far an unaffiliated vehicle looks for a recon hub to adopt as its network, in blocks.",
                        "Only used when the vehicle cannot say whose it is. A vehicle on a team or in a faction is on that network wherever it goes, because a relay carrying a picture beyond its owner's ground presence is what relays are for.")
                .defineInRange("receiverRange", 1024.0, 0.0, 8192.0);
        BROADCAST_INTERVAL = builder.comment("服务端向玩家广播其所在世界超视距实体的时间间隔（单位：tick）")
                .defineInRange("broadcastInterval", 5, 1, 72000);
        SPEC = builder.build();
    }

    private RadarConfig() {
    }

    public static Snapshot get() {
        return snapshot;
    }

    /** Entity-type regexes (config/combinedassault/server_broadcast_entity_whitelist.txt) broadcast beyond the vehicle set. */
    public static List<String> broadcastWhitelist() {
        return broadcastWhitelist;
    }

    static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            snapshot = new Snapshot(RECON.get(), RADAR_RANGE.get(), RECEIVER_RANGE.get(), BROADCAST_INTERVAL.get());
        }
    }

    public static void loadExternal() {
        Path configDir = FMLPaths.CONFIGDIR.get().resolve("combinedassault");
        Path whitelistPath = configDir.resolve("server_broadcast_entity_whitelist.txt");
        try {
            Files.createDirectories(configDir);
            if (Files.notExists(whitelistPath)) {
                Files.write(whitelistPath, Arrays.asList("superbwarfare:.*"));
            }
            broadcastWhitelist = Files.readAllLines(whitelistPath).stream()
                    .filter(line -> !line.startsWith("#") && !line.trim().isEmpty()).toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + whitelistPath, e);
        }
    }
}
