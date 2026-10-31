package com.wf.combinedassault.uav;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.wflib.drone.api.DroneNetBlock;
import com.wf.wflib.cam.api.CameraFeed;
import com.wf.wflib.cam.api.CameraMode;
import com.wf.wflib.cam.api.CameraSource;
import com.wf.wflib.cam.api.CameraSources;
import com.wf.wflib.cam.api.CameraSpec;
import net.minecraft.world.phys.Vec3;

/** UAV vehicle as a wflib camera feed (tablet preview): lens = first seat's sight, gimbal free. Small UAVs: drone-net catchable. */
public record UavCamera(AbstractVehicle vehicle) implements CameraSource {

    /** Heaviest UAV a wflib drone net holds, kg. */
    public static final double NET_MAX_KG = 25;
    /** Datalink, blocks. */
    public static final double RANGE = 4000;
    private static final float FOV = 70;
    private static final float SLEW = 9;

    public static void register() {
        DroneNetBlock.catches(entity -> entity instanceof AbstractVehicle vehicle && vehicle.uav
                && vehicle.physicsEngine.mass <= NET_MAX_KG);
        CameraSources.PROVIDERS.register(CombinedAssault.modLocation("uav"), 0, entity -> entity instanceof AbstractVehicle vehicle && vehicle.uav
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
