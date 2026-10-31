package com.wf.combinedassault.dynamics;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class DynamicsConfig {

    static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue RESTING_VEHICLE_SLEEP;
    private static final ModConfigSpec.BooleanValue DECK_HARNESS;
    private static final ModConfigSpec.BooleanValue VEHICLE_MOUNTS;
    private static final ModConfigSpec.BooleanValue PREDICT_DRIVING;
    private static final ModConfigSpec.BooleanValue CAN_DESTROY_BLOCK;
    private static final ModConfigSpec.DoubleValue RAM_BREAK_MOMENTUM;
    private static final ModConfigSpec.DoubleValue RIGHTING_EFFORT_PER_PLAYER;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        CAN_DESTROY_BLOCK = builder.comment("载具是否能破坏方块")
                .define("canDestroyBlock", true);
        RAM_BREAK_MOMENTUM = builder.comment("撞击破坏方块所需的动量（kg·格/tick，每点方块硬度），数值越小越容易撞碎")
                .defineInRange("ramBreakMomentum", 125.0, 0.0, Double.MAX_VALUE);
        RIGHTING_EFFORT_PER_PLAYER = builder.comment("每名潜行玩家能推翻的车体量（质量kg×主碰撞体积），需要 ceil(质量×体积/此值) 人同时推；0为禁用手动翻正")
                .defineInRange("rightingEffortPerPlayer", 30000.0, 0.0, Double.MAX_VALUE);
        DECK_HARNESS = builder.comment("停放在甲板上的载具进入休眠，位姿直接由母舰推导（纯性能优化）")
                .define("deckHarness", true);
        VEHICLE_MOUNTS = builder.comment("载具可安装到其他载具声明的挂载点上（如皮卡车斗上的迫击炮）")
                .define("vehicleMounts", true);
        RESTING_VEHICLE_SLEEP = builder.comment("静止且无人的载具降低物理求解频率（纯性能优化）")
                .define("restingVehicleSleep", true);
        PREDICT_DRIVING = builder.comment("Client: predict the vehicle you drive (no input round trip); server stays authoritative and corrects")
                .define("predictDriving", true);
        SPEC = builder.build();
    }

    private DynamicsConfig() {
    }

    static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            VehicleSettings.configure(new VehicleSettings(RESTING_VEHICLE_SLEEP.get(), DECK_HARNESS.get(),
                    VEHICLE_MOUNTS.get(), PREDICT_DRIVING.get(), CAN_DESTROY_BLOCK.get(), RAM_BREAK_MOMENTUM.get(),
                    RIGHTING_EFFORT_PER_PLAYER.get()));
        }
    }
}
