package com.wf.combinedassault.client.render.animation.context;

import net.minecraft.world.entity.Entity;
import com.wf.combinedassault.entity.vehicle.*;

@FunctionalInterface
public interface AnimationContextFactory<E extends Entity, CTX extends EntityContext<E>> {

    CTX create(E entity);

    static <E extends AbstractVehicle> AnimationContextFactory<E, VehicleContext<E>> vehicle() {
        return VehicleContext::new;
    }

    static AnimationContextFactory<WheeledVehicle, WheeledVehicleContext> wheeledVehicle() {
        return WheeledVehicleContext::new;
    }

    static AnimationContextFactory<TrackedVehicle, TrackedVehicleContext> trackedVehicle() {
        return TrackedVehicleContext::new;
    }

    static AnimationContextFactory<VesselVehicle, VesselVehicleContext> vesselVehicle() {
        return VesselVehicleContext::new;
    }

    static AnimationContextFactory<ShipVehicle, ShipVehicleContext> ship() {
        return ShipVehicleContext::new;
    }

    static AnimationContextFactory<RotaryWingVehicle, RotaryWingVehicleContext> rotaryWingVehicle() {
        return RotaryWingVehicleContext::new;
    }

    static AnimationContextFactory<FixedWingVehicle, FixedWingVehicleContext> fixedWingVehicle() {
        return FixedWingVehicleContext::new;
    }

    static <E extends Entity> AnimationContextFactory<E, EntityContext<E>> entity() {
        return EntityContext::new;
    }
}
