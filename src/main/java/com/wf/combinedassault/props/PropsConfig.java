package com.wf.combinedassault.props;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.ModConfigSpec;
import com.wf.combinedassault.CombinedAssault;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class PropsConfig {

    static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue ONLY_CAPTURE_VEHICLE;
    private static volatile boolean onlyCaptureVehicle;
    private static volatile List<String> captureBlacklist = List.of();

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        ONLY_CAPTURE_VEHICLE = builder.comment("手办盒是否只能收纳载具")
                .define("figureBoxOnlyCaptureVehicle", false);
        SPEC = builder.build();
    }

    private PropsConfig() {
    }

    public static boolean onlyCaptureVehicle() {
        return onlyCaptureVehicle;
    }

    public static List<String> captureBlacklist() {
        return captureBlacklist;
    }

    static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            onlyCaptureVehicle = ONLY_CAPTURE_VEHICLE.get();
        }
    }

    static void loadBlacklist() {
        Path dir = FMLPaths.CONFIGDIR.get().resolve("combinedassault");
        Path file = dir.resolve("figure_box_capture_blacklist.txt");
        try {
            Files.createDirectories(dir);
            if (Files.notExists(file)) {
                Files.write(file, List.of("minecraft:ender_dragon", "corpse:corpse", "twilightforest:naga"));
            }
            captureBlacklist = Files.readAllLines(file).stream()
                    .filter(line -> !line.startsWith("#") && !line.trim().isEmpty())
                    .toList();
        } catch (IOException e) {
            CombinedAssault.LOGGER.error("figure box capture blacklist {} unreadable", file, e);
        }
    }
}
