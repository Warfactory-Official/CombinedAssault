package com.wf.combinedassault.render.animation.context;

import com.wf.combinedassault.ballistics.component.HullComponents;
import com.wf.combinedassault.render.entity.vehicle.VehicleRender;
import com.wf.combinedassault.render.model.VehicleModelInstance;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.RotatableUnit;
import com.wf.combinedassault.vehicle.part.SwitchableUnit;
import com.wf.combinedassault.weapon.AbstractVehicleWeapon;
import com.wf.combinedassault.weapon.VehicleTool;
import com.wf.combinedassault.weapon.VehicleWeaponAgent;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import net.minecraft.util.Mth;

import java.util.List;
import java.util.Optional;

public class VehicleContext<E extends AbstractVehicle> extends EntityContext<E> {

    /** Ground covered in one frame past which this is a teleport, not a roll, in blocks. */
    private static final double MAX_STEP = 2.0;
    /** Seconds to full {@code tool_spin} speed, and back to rest. */
    private static final float SPIN_UP = 1.2f;
    private static final float SPIN_DOWN = 2.0f;

    /** Set the first time a binding asks for the roll, so a vehicle with no wheels never measures one. */
    private boolean rolls;
    private boolean seen;
    private double seenX;
    private double seenZ;
    /** Ground covered along the vehicle's own heading since it was first drawn, in blocks. */
    private double rolled;

    public VehicleContext(E entity) {
        super(entity);
    }

    @Override
    public void tick() {
        super.tick();
        if (rolls) {
            advanceRoll();
        }
        if (spins) {
            advanceSpin(frameSeconds());
        }
    }

    /** From drawn position, not drivetrain: covers trailers on a hitch, hulls on a deck, cable-towed vehicles. */
    private void advanceRoll() {
        E vehicle = getEntity();
        double x = Mth.lerp(partialTick, vehicle.xo, vehicle.getX());
        double z = Mth.lerp(partialTick, vehicle.zo, vehicle.getZ());
        if (seen) {
            double dx = x - seenX;
            double dz = z - seenZ;
            if (dx * dx + dz * dz <= MAX_STEP * MAX_STEP) {
                float yaw = Mth.rotLerp(partialTick, vehicle.yRotO, vehicle.getYRot()) * Mth.DEG_TO_RAD;
                rolled += dz * Mth.cos(yaw) - dx * Mth.sin(yaw);
            }
        }
        seenX = x;
        seenZ = z;
        seen = true;
    }

    /** {@code radius} in blocks. */
    public float getWheelDegrees(float radius) {
        if (radius <= 1.0e-4f) {
            return 0f;
        }
        return (float) (rolled / (2 * Math.PI * radius) * 360.0 % 360.0);
    }

    /** Whether the {@code tool_spin} binding was asked: measured only then. */
    private boolean spins;
    private float spinEase;
    private float spinDegrees;
    /** Degrees per second at full speed: the binding's param. */
    private float spinRate = 360f;

    private void advanceSpin(float seconds) {
        boolean on = false;
        for (PartUnit<?> part : entity.getPartUnits()) {
            if (part instanceof WeaponUnit unit && unit.getCurrentWeapon().orElse(null) instanceof VehicleTool tool && tool.spinning()) {
                on = true;
                break;
            }
        }
        spinEase = Mth.clamp(spinEase + (on ? seconds / SPIN_UP : -seconds / SPIN_DOWN), 0f, 1f);
        spinDegrees = (spinDegrees + spinEase * spinRate * seconds) % 360f;
    }

    @Override
    public float getBindingValue(String source, Float param) {
        if ("tool_spin".equals(source)) {
            spins = true;
            if (param != null) {
                spinRate = param;
            }
            return spinDegrees;
        }
        if ("wheel_rotation".equals(source)) {
            rolls = true;
            return getWheelDegrees(param != null ? param : 0.5f);
        }
        return super.getBindingValue(source, param);
    }

    public void setBoneVisible(String boneName, boolean visible) {
        VehicleModelInstance vehicleModelInstance = entity.getVehicleModelInstance();
        if (vehicleModelInstance != null) {
            vehicleModelInstance.setBoneVisible(boneName, visible);
        }
        VehicleModelInstance cabinModelInstance = entity.getCabinModelInstance();
        if (cabinModelInstance != null) {
            cabinModelInstance.setBoneVisible(boneName, visible);
        }
    }

    public boolean isOwnLensView() {
        return VehicleRender.ownLensView(getEntity());
    }

    public void setBoneIlluminated(String boneName, boolean illuminated) {
        VehicleModelInstance vehicleModelInstance = entity.getVehicleModelInstance();
        if (vehicleModelInstance != null) {
            vehicleModelInstance.setBoneIlluminated(boneName, illuminated);
        }
        VehicleModelInstance cabinModelInstance = entity.getCabinModelInstance();
        if (cabinModelInstance != null) {
            cabinModelInstance.setBoneIlluminated(boneName, illuminated);
        }
    }

    public float getXRot() {
        return getEntity().getXRot();
    }

    public float getYRot() {
        return getEntity().getYRot();
    }

    public float getZRot() {
        return getEntity().getZRot();
    }

    public double getSpeed() {
        return getEntity().getDeltaMovement().length();
    }

    /** Hull system pool {@code id} ({@code "rotor FL"}): 0 broken .. 1; no such pool => 1. */
    public float getSystemEffectiveness(String id) {
        HullComponents systems = entity.hullComponents();
        int at = systems.indexOf(id);
        return at < 0 ? 1 : systems.effectiveness(at);
    }

    public float getPower() {
        return getEntity().getPower();
    }

    public float getEngineSpeed() {
        return getEntity().getEngineSpeed();
    }

    public float getPartXRot(String id) {
        return getEntity().getPartUnit(id).map(part -> {
            if (part instanceof RotatableUnit<?> rotatable) {
                return rotatable.getViewXRot(partialTick);
            }
            return 0f;
        }).orElse(0f);
    }

    public float getPartYRot(String id) {
        return getEntity().getPartUnit(id).map(part -> {
            if (part instanceof RotatableUnit<?> rotatable) {
                return rotatable.getViewYRot(partialTick);
            }
            return 0f;
        }).orElse(0f);
    }

    public boolean isPartOn(String id) {
        return getEntity().getPartUnit(id).map(part -> {
            if (part instanceof SwitchableUnit<?> switchableUnit) {
                return switchableUnit.isOn();
            }
            return false;
        }).orElse(false);
    }

    public int getWeaponRemainAmmo(String id, int weaponIndex) {
        Optional<PartUnit<?>> partUnitOptional = getEntity().getPartUnit(id);
        if (partUnitOptional.isEmpty()) {
            return 0;
        }
        if (partUnitOptional.get() instanceof WeaponUnit weaponUnit) {
            List<AbstractVehicleWeapon<?>> indexedWeapons = weaponUnit.getIndexedWeapons();
            if (weaponIndex >= 0 && weaponIndex < indexedWeapons.size()) {
                AbstractVehicleWeapon<?> weapon = indexedWeapons.get(weaponIndex);
                if (weapon instanceof VehicleWeaponAgent weaponAgent) {
                    return weaponAgent.getWeaponUnit().getCurrentWeapon().get().getRemainAmmo();
                }
                return weapon.getRemainAmmo();
            }
        }
        return 0;
    }

    public double getContainerFillRate() {
        return getEntity().getContainerFillRate();
    }

    public int tickCount() {
        return getEntity().tickCount;
    }

    public long lastRenderTime() {
        return getEntity().lastRenderTime;
    }

    /** Seconds since the vehicle was last drawn; 0 before its first draw (epoch-sized step => float accumulators lose all precision). */
    public float frameSeconds() {
        long last = lastRenderTime();
        return last == 0 ? 0f : (currentTimeMillis() - last) / 1000f;
    }

}
