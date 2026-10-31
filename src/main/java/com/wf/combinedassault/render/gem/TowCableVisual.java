package com.wf.combinedassault.render.gem;

import com.wf.gemrender.rope.GemRenderRopeTypes;
import com.wf.gemrender.rope.RopeInstance;
import com.wf.gemrender.rope.RopeModels;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.tow.TowMode;
import com.wf.combinedassault.tow.client.TowCable;

/**
 * A tow cable as one Flywheel instance, owned by the towed vehicle's visual (it holds the link).
 *
 * <p>Six floats + sag written per frame; curve, tube and draw are GemRender's, draw shared across all ropes.
 */
@OnlyIn(Dist.CLIENT)
final class TowCableVisual {

    /** Rings along the cable and sides around it. Four sides is what a vanilla lead looks like. */
    private static final int RINGS = 16;
    private static final int SIDES = 4;

    /** Blocks of cable per repeat of the texture. */
    private static final float TILING = 0.5f;

    private final VisualizationContext context;
    private final BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();

    @Nullable
    private RopeInstance rope;

    TowCableVisual(VisualizationContext context) {
        this.context = context;
    }

    void update(AbstractVehicle towed, Vec3i origin, float partialTick) {
        Vec3[] ends = TowCable.ends(towed, partialTick);
        if (ends == null) {
            hide();
            return;
        }
        if (rope == null) {
            rope = context.instancerProvider()
                    .instancer(GemRenderRopeTypes.ROPE, RopeModels.cutout(TowCable.TEXTURE, RINGS, SIDES))
                    .createInstance();
            rope.colorArgb(0xFFFFFFFF);
        }
        Vec3 from = ends[0];
        Vec3 to = ends[1];
        rope.between((float) (from.x - origin.getX()), (float) (from.y - origin.getY()),
                        (float) (from.z - origin.getZ()),
                        (float) (to.x - origin.getX()), (float) (to.y - origin.getY()),
                        (float) (to.z - origin.getZ()))
                .sag(TowCable.sag(from, to, paidOut(towed)))
                .radius(TowCable.RADIUS)
                .tiling((float) (from.distanceTo(to) / TILING))
                .lightB(lightAt(towed, to))
                .refresh();
        rope.light(lightAt(towed, from));
        rope.setChanged();
    }

    private static double paidOut(AbstractVehicle towed) {
        int packed = towed.getTowHitch();
        if (packed < 0
                || !(towed.level().getEntity(towed.getTowTractorId()) instanceof AbstractVehicle tractor)) {
            return 0;
        }
        int index = packed / TowMode.COUNT;
        return index < tractor.towing.hitches.size() ? tractor.towing.hitches.get(index).cableLength : 0;
    }

    private int lightAt(AbstractVehicle towed, Vec3 at) {
        lightPos.set(Mth.floor(at.x), Mth.floor(at.y), Mth.floor(at.z));
        return LevelRenderer.getLightColor(towed.level(), lightPos);
    }

    private void hide() {
        if (rope != null) {
            rope.delete();
            rope = null;
        }
    }

    void delete() {
        hide();
    }

}
