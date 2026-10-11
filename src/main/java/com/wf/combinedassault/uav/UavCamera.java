package com.wf.combinedassault.uav;

import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.wflib.drone.cam.CameraFeed;
import com.wf.wflib.drone.cam.CameraMode;
import com.wf.wflib.drone.cam.CameraSource;
import com.wf.wflib.drone.cam.CameraSources;
import com.wf.wflib.drone.cam.CameraSpec;
import net.minecraft.world.phys.Vec3;

/** UAV vehicle as a wflib camera feed (tablet preview): lens = first seat's sight, gimbal free. */
public record UavCamera(AbstractVehicle vehicle) implements CameraSource {

    /** Datalink, blocks. */
    public static final double RANGE = 4000;
    private static final float FOV = 70;
    private static final float SLEW = 9;

    public static void register() {
        CameraSources.register(entity -> entity instanceof AbstractVehicle vehicle && vehicle.uav
                && !vehicle.isDestroyed() && !vehicle.seats.isEmpty() ? new UavCamera(vehicle) : null);
    }

    private PartUnit<?> station() {
        return vehicle.seats.get(0).partUnit;
    }

    @Override
    public Vec3 eye() {
        return station().worldCameraPosition();
    }

    @Override
    public CameraSpec spec() {
        int modes = 1 << CameraMode.OPTICAL.ordinal();
        float zoom = 4;
        if (station() instanceof WeaponUnit unit) {
            for (CameraMode mode : CameraMode.VALUES) {
                if (unit.fitted(mode)) {
                    modes |= 1 << mode.ordinal();
                }
            }
            zoom = Math.max(zoom, unit.getZoomMax());
        }
        return new CameraSpec(FOV, zoom, -60, 90, CameraSpec.FREE_YAW, SLEW, RANGE, modes, 0);
    }

    @Override
    public float restYaw() {
        return vehicle.getYRot();
    }

    @Override
    public float restPitch() {
        return 10;
    }

    /** Fuel != camera power: battery 0 = unusable feed (wflib), and summoned vehicles start dry. */
    @Override
    public float battery() {
        return 1;
    }

    @Override
    public float speed() {
        return (float) vehicle.getDeltaMovement().length() * 20;
    }

    @Override
    public void bill(int fe, int ticks) {
    }

    @Override
    public byte kind() {
        return CameraFeed.MOUNTED;
    }
}
