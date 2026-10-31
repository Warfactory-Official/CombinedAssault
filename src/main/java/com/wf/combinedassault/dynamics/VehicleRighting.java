package com.wf.combinedassault.dynamics;

import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.geom.api.OBB;
import com.wf.combinedassault.dynamics.structure.VehicleCubeOBB;

import java.util.List;

/** Hand-rights tumbled vehicles. Server-only, server-known state only: nothing for a client to send or spoof. */
public final class VehicleRighting {

    /** Blocks beyond the hull a player may stand and still shove it. */
    private static final double REACH = 1.4;
    /** Cosine(look, dir-to-hull) threshold for a square-on shove. */
    private static final double FACING = 0.35;
    /** Hold ticks before the hull goes over. */
    private static final int HOLD_TICKS = 10;
    /** Ticks between the creaks that tell the pushers it is working. */
    private static final int STRAIN_INTERVAL = 4;
    /** Up-axis height below which the hull counts as tumbled: cos 75 degrees. */
    private static final float TUMBLED_UP = 0.2588f;
    /** Above this speed the hull is still going somewhere, not there to be pushed. */
    private static final double REST_SPEED = 0.08;
    /** Above this angular speed the hull is still rolling, not finished falling over. */
    private static final float REST_SPIN = 0.02f;

    private VehicleRighting() {
    }

    /** Tick thread, server side. */
    public static void tick(AbstractVehicle vehicle) {
        // Ordered cheapest-first: righting off => 1 config field read; not tumbled (the common
        // case) => +2 rotation fields. Nothing walks a list until a hull is genuinely over and
        // has stopped moving.
        if (VehicleSettings.current().rightingEffortPerPlayer() <= 0
                || !canBeRighted(vehicle)
                || !pushed(vehicle)) {
            vehicle.rightingHold = 0;
            return;
        }
        if (++vehicle.rightingHold < HOLD_TICKS) {
            strain(vehicle);
            return;
        }
        vehicle.rightingHold = 0;
        right(vehicle);
    }

    /** Creak sound while the shove is held. */
    private static void strain(AbstractVehicle vehicle) {
        // (hold-1)%INTERVAL: first creak lands on the shove's first tick, not after a full interval.
        if ((vehicle.rightingHold - 1) % STRAIN_INTERVAL != 0) {
            return;
        }
        vehicle.level().playSound(null, vehicle.getX(), vehicle.getY(), vehicle.getZ(),
                vehicle.deckSoundType().getHitSound(), SoundSource.NEUTRAL, 0.5f, 0.6f);
    }

    /** Whether the hull is over far enough, still enough, and solid enough to be worth shoving. */
    private static boolean canBeRighted(AbstractVehicle vehicle) {
        if (vehicle.isRemoved() || !vehicle.collision || vehicle.getMainCubeOBB() == null) {
            return false;
        }
        // Up axis, not Euler: a flipped hull may sit on either YXZ branch (pitch past 90 <=> yaw + 180).
        if (vehicle.getMainCubeOBB().obb().getAxes()[1].y > TUMBLED_UP) {
            return false;
        }
        // Mid-roll or still-driven != settled wreck; a shove landing mid-roll would fight the
        // hull's own angular momentum.
        return vehicle.getDeltaMovement().lengthSqr() <= REST_SPEED * REST_SPEED
                && vehicle.physicsEngine.angularVelocity.lengthSquared() <= REST_SPIN * REST_SPIN;
    }

    /** Whether enough people are shoving this tick. */
    private static boolean pushed(AbstractVehicle vehicle) {
        int needed = playersNeeded(vehicle);
        if (needed <= 0) {
            return false;
        }
        // level.players(), not getEntitiesOfClass: avoids a per-tick section-walk + allocation for
        // a parked wreck nobody is coming back for. Same trade Level.getNearestPlayer makes.
        List<? extends Player> players = vehicle.level().players();
        if (players.isEmpty()) {
            return false;
        }
        OBB hull = vehicle.getMainCubeOBB().obb();
        Vector3f centre = hull.center();
        double radius = hull.extents().length() + REACH;
        double radiusSq = radius * radius;
        // axes computed once, lazily: getClosestPointOBB re-derives them from the quaternion per
        // call (3 allocations), for a frame identical across every candidate this tick.
        Vector3f[] axes = null;
        int pushing = 0;
        for (int i = 0, size = players.size(); i < size; i++) {
            Player player = players.get(i);
            // Sneak check first: one shared-flag read, false for essentially everyone online.
            if (!player.isShiftKeyDown() || player.isSpectator() || player.isPassenger()
                    || player.isRemoved()) {
                continue;
            }
            double eyeX = player.getX();
            double eyeY = player.getEyeY();
            double eyeZ = player.getZ();
            double offX = eyeX - centre.x;
            double offY = eyeY - centre.y;
            double offZ = eyeZ - centre.z;
            // Bounding-sphere reject before the box: three multiplies, not a clamped per-axis projection.
            if (offX * offX + offY * offY + offZ * offZ > radiusSq) {
                continue;
            }
            if (axes == null) {
                axes = hull.getAxes();
            }
            if (!shoving(player, eyeX, eyeY, eyeZ, hull, axes)) {
                continue;
            }
            if (player.isCreative()) {
                return true;
            }
            if (++pushing >= needed) {
                return true;
            }
        }
        return false;
    }

    /** Whether this player is leaning on the hull right now. */
    private static boolean shoving(Player player, double eyeX, double eyeY, double eyeZ,
                                   OBB hull, Vector3f[] axes) {
        Vector3f centre = hull.center();
        Vector3f extents = hull.extents();
        double offX = eyeX - centre.x;
        double offY = eyeY - centre.y;
        double offZ = eyeZ - centre.z;
        double nearX = centre.x;
        double nearY = centre.y;
        double nearZ = centre.z;
        for (int a = 0; a < 3; a++) {
            Vector3f axis = axes[a];
            double limit = a == 0 ? extents.x : a == 1 ? extents.y : extents.z;
            double along = Mth.clamp(offX * axis.x + offY * axis.y + offZ * axis.z, -limit, limit);
            nearX += along * axis.x;
            nearY += along * axis.y;
            nearZ += along * axis.z;
        }
        double dx = nearX - eyeX;
        double dy = nearY - eyeY;
        double dz = nearZ - eyeZ;
        double distanceSq = dx * dx + dy * dy + dz * dz;
        if (distanceSq > REACH * REACH) {
            return false;
        }
        if (distanceSq < 1.0e-6) {
            // Inside the hull (e.g. under an upturned vehicle); no facing direction to check, presence counts.
            return true;
        }
        Vec3 look = player.getLookAngle();
        return (look.x * dx + look.y * dy + look.z * dz)
                / java.lang.Math.sqrt(distanceSq) >= FACING;
    }

    /** How many survival players it takes to right this vehicle, or 0 if hand righting is switched off. */
    private static int playersNeeded(AbstractVehicle vehicle) {
        double perPlayer = VehicleSettings.current().rightingEffortPerPlayer();
        if (perPlayer <= 0) {
            return 0;
        }
        VehicleCubeOBB cube = vehicle.getMainCubeOBB();
        double volume = cube.getWidth() * cube.getHeight() * cube.getDepth();
        double effort = java.lang.Math.max(0, vehicle.physicsEngine.mass) * volume;
        // min 1: even a hull below the per-player budget still needs someone pushing.
        return (int) java.lang.Math.max(1, java.lang.Math.ceil(effort / perPlayer));
    }

    private static void right(AbstractVehicle vehicle) {
        // heading = nose, unless the hull is nose-up/down (axes[2].y >= 0.9), then roof axis.
        Vector3f[] axes = vehicle.getMainCubeOBB().obb().getAxes();
        Vector3f heading = Math.abs(axes[2].y) < 0.9f ? axes[2] : axes[1];
        vehicle.setYRot((float) Math.toDegrees(Math.atan2(-heading.x, heading.z)));
        vehicle.setXRot(0);
        vehicle.setZRot(0);
        vehicle.physicsEngine.angularVelocity.zero();
        vehicle.triggerPosRotUpdate();
        // deckSoundType: landing sound matches the hull's declared material.
        vehicle.level().playSound(null, vehicle.getX(), vehicle.getY(), vehicle.getZ(),
                vehicle.deckSoundType().getPlaceSound(), SoundSource.NEUTRAL, 1.0f, 0.7f);
    }

}
