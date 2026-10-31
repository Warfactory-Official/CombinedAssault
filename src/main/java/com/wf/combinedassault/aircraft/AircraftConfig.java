package com.wf.combinedassault.aircraft;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class AircraftConfig {

    public record Snapshot(boolean arcadeFlightModel) {
    }

    static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue ARCADE_FLIGHT_MODEL;
    private static final ModConfigSpec.IntValue SMOKE_R;
    private static final ModConfigSpec.IntValue SMOKE_G;
    private static final ModConfigSpec.IntValue SMOKE_B;
    private static volatile Snapshot snapshot = new Snapshot(true);

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        ARCADE_FLIGHT_MODEL = builder.comment("飞行器使用街机风格气动模型（贴合机头方向的阻力）")
                .define("arcadeFlightModel", true);
        SMOKE_R = builder.comment("特技飞行烟雾颜色 - 红").defineInRange("aerobaticSmokeR", 255, 0, 255);
        SMOKE_G = builder.comment("特技飞行烟雾颜色 - 绿").defineInRange("aerobaticSmokeG", 0, 0, 255);
        SMOKE_B = builder.comment("特技飞行烟雾颜色 - 蓝").defineInRange("aerobaticSmokeB", 0, 0, 255);
        SPEC = builder.build();
    }

    private AircraftConfig() {
    }

    public static Snapshot get() {
        return snapshot;
    }

    /** Live: the modding tool screen writes them. */
    public static ModConfigSpec.IntValue smokeR() {
        return SMOKE_R;
    }

    public static ModConfigSpec.IntValue smokeG() {
        return SMOKE_G;
    }

    public static ModConfigSpec.IntValue smokeB() {
        return SMOKE_B;
    }

    static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            snapshot = new Snapshot(ARCADE_FLIGHT_MODEL.get());
        }
    }
}
