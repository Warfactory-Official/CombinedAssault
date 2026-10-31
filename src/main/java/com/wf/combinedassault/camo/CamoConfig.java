package com.wf.combinedassault.camo;

import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class CamoConfig {

    public record Snapshot(int maxPieces, double strokeSpacing, boolean bufferedRender) {
    }

    static final ModConfigSpec SPEC;
    private static final ModConfigSpec.IntValue MAX_PIECES;
    private static final ModConfigSpec.DoubleValue STROKE_SPACING;
    private static final ModConfigSpec.BooleanValue BUFFERED_RENDER;
    private static volatile Snapshot snapshot = new Snapshot(512, 0.30, true);

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        MAX_PIECES = builder.comment("单个载具的伪装碎片上限")
                .defineInRange("maxPieces", 512, 0, 4096);
        STROKE_SPACING = builder.comment("涂刷伪装时相邻碎片的最小间距（方块）")
                .defineInRange("strokeSpacing", 0.30, 0.05, 4.0);
        BUFFERED_RENDER = builder.comment("伪装使用静态顶点缓冲绘制（每个骨骼一次绘制，与碎片数量无关）；关闭则改为逐帧重放已烘焙的顶点")
                .define("bufferedRender", true);
        SPEC = builder.build();
    }

    private CamoConfig() {
    }

    public static Snapshot get() {
        return snapshot;
    }

    static void onLoad(ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            snapshot = new Snapshot(MAX_PIECES.get(), STROKE_SPACING.get(), BUFFERED_RENDER.get());
        }
    }
}
