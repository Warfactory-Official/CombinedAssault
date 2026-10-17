package com.wf.combinedassault.client.render.animation.context;

import net.minecraft.util.Mth;
import com.wf.combinedassault.client.model.VehicleModelInstance;
import com.wf.combinedassault.client.render.entity.vehicle.VehicleRender;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.control.ControlUnit;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.RotatableUnit;
import com.wf.combinedassault.vehicle.part.SwitchableUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;
import com.wf.combinedassault.vehicle.weapon.VehicleWeaponAgent;

import java.util.List;
import java.util.Optional;

public class VehicleContext<E extends AbstractVehicle> extends EntityContext<E> {

    /** Ground covered in one frame past which this is a teleport, not a roll, in blocks. */
    private static final double MAX_STEP = 2.0;

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

    @Override
    public float getBindingValue(String source, Float param) {
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

    public boolean hasOwner(String id) {
        return getEntity().getPartUnit(id).map(part -> part.getOwner() != null).orElse(false);
    }

    public double getSpeed() {
        return getEntity().getDeltaMovement().length();
    }

    public ControlUnit getControlUnit() {
        return getEntity().controlUnit;
    }

    /** Hull system pool {@code id} ({@code "rotor FL"}): 0 broken .. 1; no such pool => 1. */
    public float getSystemEffectiveness(String id) {
        com.wf.combinedassault.ballistics.component.HullComponents systems = entity.hullComponents();
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

    public boolean isPartDestroyed(String id) {
        return getEntity().getPartUnit(id).map(PartUnit::isDestroyed).orElse(false);
    }

    public boolean isPartDetached(String id) {
        return getEntity().getPartUnit(id).map(PartUnit::isDetached).orElse(false);
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

    public String getWeaponName(String id, int weaponIndex) {
        Optional<PartUnit<?>> partUnitOptional = getEntity().getPartUnit(id);
        if (partUnitOptional.isEmpty()) {
            return "";
        }
        if (partUnitOptional.get() instanceof WeaponUnit weaponUnit) {
            List<AbstractVehicleWeapon<?>> indexedWeapons = weaponUnit.getIndexedWeapons();
            if (weaponIndex >= 0 && weaponIndex < indexedWeapons.size()) {
                AbstractVehicleWeapon<?> weapon = indexedWeapons.get(weaponIndex);
                if (weapon instanceof VehicleWeaponAgent weaponAgent) {
                    return weaponAgent.getWeaponUnit().getCurrentWeapon().get().getDisplayName().getString();
                }
                return weapon.getDisplayName().getString();
            }
        }
        return "";
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

}
