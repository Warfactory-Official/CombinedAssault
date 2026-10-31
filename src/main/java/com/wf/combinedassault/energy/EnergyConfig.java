package com.wf.combinedassault.energy;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Arrays;
import java.util.List;

public final class EnergyConfig {

    public record Snapshot(boolean infiniteFuel, List<String> fuelNameWhiteList, boolean parkedResupply) {
    }

    static final ModConfigSpec SPEC;
    private static final ModConfigSpec.BooleanValue INFINITE_FUEL;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> FUEL_NAME_WHITE_LIST;
    private static final ModConfigSpec.BooleanValue PARKED_RESUPPLY;
    private static volatile Snapshot snapshot = new Snapshot(false, List.of("fuel", "gas", "lava"), true);

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        INFINITE_FUEL = builder.comment("无需燃油仍可运作")
                .define("infiniteFuel", false);
        FUEL_NAME_WHITE_LIST = builder.comment("允许视作燃油的液体（仅用于未在数据包中声明 fuels 的载具）")
                .defineList("fuelNameWhiteList", Arrays.asList("fuel", "gas", "lava"), obj -> obj instanceof String);
        PARKED_RESUPPLY = builder.comment("载具停在储罐或供电方块上时自动加注燃料/充电（能量系统无法向实体推送，只能由载具向下抽取）")
                .define("parkedResupply", true);
        SPEC = builder.build();
    }

    private EnergyConfig() {
    }

    public static Snapshot get() {
        return snapshot;
    }

    static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            snapshot = new Snapshot(INFINITE_FUEL.get(), List.copyOf(FUEL_NAME_WHITE_LIST.get()), PARKED_RESUPPLY.get());
        }
    }
}
