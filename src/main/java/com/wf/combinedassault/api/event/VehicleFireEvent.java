package com.wf.combinedassault.api.event;

import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;

public abstract class VehicleFireEvent extends VehicleEvent {

    private final AbstractVehicleWeapon<?> weapon;
    @Nullable
    private final LivingEntity operator;

    private VehicleFireEvent(AbstractVehicle vehicle, AbstractVehicleWeapon<?> weapon, @Nullable LivingEntity operator) {
        super(vehicle);
        this.weapon = weapon;
        this.operator = operator;
    }

    /** Client: fires on fire-button press, before server notified (operator's client only). Server: before weapon fires. Cancel => fire blocked. */
    public static class Pre extends VehicleFireEvent {
        public Pre(AbstractVehicle vehicle, AbstractVehicleWeapon<?> weapon, @Nullable LivingEntity operator) {
            super(vehicle, weapon, operator);
        }
    }

    /** Client: fires on server's fire-complete callback. Server: after weapon fires. */
    public static class Post extends VehicleFireEvent {
        public Post(AbstractVehicle vehicle, AbstractVehicleWeapon<?> weapon, @Nullable LivingEntity operator) {
            super(vehicle, weapon, operator);
        }
    }

    public AbstractVehicleWeapon<?> getWeapon() {
        return weapon;
    }

    public boolean isClientSide() {
        return this.getVehicle().level().isClientSide();
    }

    @Nullable
    public LivingEntity getOperator() {
        return operator;
    }

}
