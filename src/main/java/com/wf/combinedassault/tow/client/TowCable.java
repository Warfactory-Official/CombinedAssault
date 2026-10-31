package com.wf.combinedassault.tow.client;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.tow.HitchInfo;
import com.wf.combinedassault.tow.TowLinks;
import com.wf.combinedassault.tow.TowMode;

import net.minecraft.resources.ResourceLocation;

/**
 * Cable geometry, derived purely from the two vehicles + synced link (renderer-independent). Hitch mode draws nothing:
 * kingpin sits inside the fifth wheel. Curve = GemRender's parabola {@code A + (B-A)t - 4 sag t(1-t) Y}.
 */
@OnlyIn(Dist.CLIENT)
public final class TowCable {

    public static final ResourceLocation TEXTURE =
            CombinedAssault.modLocation("textures/entity/tow_cable.png");

    /** Blocks; matches vanilla chain's 3/16 width. */
    public static final float RADIUS = 3f / 32f;

    /** Fallback multiplier when paid-out length is unknown (mid-reload): avoids drawing a dead-straight, obviously-wrong rope. */
    private static final float SLACK = 1.06f;

    private TowCable() {
    }

    /** [tractor end, towed end]; null if not on a cable link. */
    @Nullable
    public static Vec3[] ends(AbstractVehicle towed, float partialTick) {
        int packed = towed.getTowHitch();
        int tractorId = towed.getTowTractorId();
        TowMode mode = packed < 0 ? null : TowMode.values()[packed % TowMode.COUNT];
        if (mode == null || tractorId < 0 || !mode.rope()) {
            return null;
        }
        Entity found = towed.level().getEntity(tractorId);
        if (!(found instanceof AbstractVehicle tractor)) {
            return null;
        }
        int index = packed / TowMode.COUNT;
        if (index >= tractor.towing.hitches.size()) {
            return null;
        }
        HitchInfo hitch = tractor.towing.hitches.get(index);
        return new Vec3[]{point(tractor, hitch.offset, partialTick),
                point(towed, mode == TowMode.SLING ? TowLinks.slingOffset(towed) : towed.towing.towable.offset, partialTick)};
    }

    /** Vehicle-frame point -> world, interpolated to {@code partialTick} (not snapped to tick). */
    public static Vec3 point(AbstractVehicle vehicle, Vec3 offset, float partialTick) {
        Vector3f rotated = vehicle.rotYXZ(partialTick)
                .transform(offset.subtract(vehicle.centerOffset).toVector3f());
        return new Vec3(Mth.lerp(partialTick, vehicle.xo, vehicle.getX()),
                Mth.lerp(partialTick, vehicle.yo, vehicle.getY()),
                Mth.lerp(partialTick, vehicle.zo, vehicle.getZ()))
                .add(vehicle.centerOffset)
                .add(rotated.x, rotated.y, rotated.z);
    }

    /** Droop vs. straight line, from paid-out length vs. gap (0 exactly when taut). Near-vertical span: 0 too, sag is along {@code -Y} and would slide points along the rope rather than bow it. */
    public static float sag(Vec3 from, Vec3 to, double paidOut) {
        double gap = from.distanceTo(to);
        if (gap < 1.0E-3) {
            return 0;
        }
        double horizontal = Math.hypot(to.x - from.x, to.z - from.z);
        if (horizontal < 0.25 * gap) {
            return 0;
        }
        double length = paidOut > 1.0E-3 ? paidOut : gap * SLACK;
        // chord c, sag s: length ~ c(1 + 8s^2/3c^2); inverted here.
        double excess = Math.max(0, length / gap - 1);
        return (float) (gap * Math.sqrt(excess * 3.0 / 8.0));
    }

    /** {@code t}=0 at tractor end. */
    public static Vec3 along(Vec3 from, Vec3 to, float sag, double t) {
        double droop = 4 * sag * t * (1 - t);
        return new Vec3(Mth.lerp(t, from.x, to.x),
                Mth.lerp(t, from.y, to.y) - droop,
                Mth.lerp(t, from.z, to.z));
    }

    public static Vec3 cameraPosition() {
        return Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
    }

}
