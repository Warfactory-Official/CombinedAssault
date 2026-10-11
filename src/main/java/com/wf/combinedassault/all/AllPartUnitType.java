package com.wf.combinedassault.all;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.part.PartUnitType;
import com.wf.combinedassault.custom.part.PartUnitTypes;
import com.wf.combinedassault.fpv.payload.PayloadUnit;
import com.wf.combinedassault.fpv.payload.PayloadUnitData;
import com.wf.combinedassault.custom.part.data.*;
import com.wf.combinedassault.vehicle.part.*;

public class AllPartUnitType {

    public static final DeferredRegister<PartUnitType<?, ?>> PART_UNIT_TYPE = DeferredRegister.create(ModRegistries.PART_UNIT_TYPE_KEY, CombinedAssault.MOD_ID);

    public static final DeferredHolder<PartUnitType<?, ?>, PartUnitType<PartUnit<PartUnitData>, PartUnitData>> GENERIC = register(PartUnitTypes.GENERIC);

    public static final DeferredHolder<PartUnitType<?, ?>, PartUnitType<SwitchableUnit<PartUnitData>, PartUnitData>> SWITCHABLE = register(PartUnitTypes.SWITCHABLE);

    public static final DeferredHolder<PartUnitType<?, ?>, PartUnitType<RotatableUnit<RotatableUnitData>, RotatableUnitData>> ROTATABLE = register(PartUnitTypes.ROTATABLE);

    public static final DeferredHolder<PartUnitType<?, ?>, PartUnitType<DoorUnit, DoorUnitData>> DOOR = register(PartUnitTypes.DOOR);

    public static final DeferredHolder<PartUnitType<?, ?>, PartUnitType<LandingGearUnit, LandingGearUnitData>> LANDING_GEAR = register(PartUnitTypes.LANDING_GEAR);

    public static final DeferredHolder<PartUnitType<?, ?>, PartUnitType<AirbrakeUnit, AirbrakeUnitData>> AIRBRAKE = register(PartUnitTypes.AIRBRAKE);

    public static final DeferredHolder<PartUnitType<?, ?>, PartUnitType<WeaponBayUnit, PartUnitData>> WEAPON_BAY = register(PartUnitTypes.WEAPON_BAY);

    public static final DeferredHolder<PartUnitType<?, ?>, PartUnitType<WeaponUnit, WeaponUnitData>> WEAPON = register(PartUnitTypes.WEAPON);

    public static final DeferredHolder<PartUnitType<?, ?>, PartUnitType<AutoWeaponUnit, WeaponUnitData>> AUTO_WEAPON = register(PartUnitTypes.AUTO_WEAPON);

    public static final DeferredHolder<PartUnitType<?, ?>, PartUnitType<RadarUnit, RadarUnitData>> RADAR = register(PartUnitTypes.RADAR);

    public static final DeferredHolder<PartUnitType<?, ?>, PartUnitType<MissileHolderUnit, MissileHolderUnitData>> MISSILE_HOLDER = register(PartUnitTypes.MISSILE_HOLDER);

    public static final DeferredHolder<PartUnitType<?, ?>, PartUnitType<PayloadUnit, PayloadUnitData>> PAYLOAD = register(PartUnitTypes.PAYLOAD);

    public static final DeferredHolder<PartUnitType<?, ?>, PartUnitType<ThrustUnit, RotatableUnitData>> THRUST = register(PartUnitTypes.THRUST);

    public static final DeferredHolder<PartUnitType<?, ?>, PartUnitType<VariableSweepUnit, VariableSweepUnitData>> VARIABLE_SWEEP = register(PartUnitTypes.VARIABLE_SWEEP);
    public static final DeferredHolder<PartUnitType<?, ?>, PartUnitType<LinkedHingeUnit, LinkedHingeUnitData>> LINKED_HINGE = register(PartUnitTypes.LINKED_HINGE);
    public static final DeferredHolder<PartUnitType<?, ?>, PartUnitType<FlapUnit, FlapUnitData>> FLAPS = register(PartUnitTypes.FLAPS);
    public static final DeferredHolder<PartUnitType<?, ?>, PartUnitType<RopeUnit, RopeUnitData>> ROPE = register(PartUnitTypes.ROPE);

    public static final DeferredHolder<PartUnitType<?, ?>, PartUnitType<SuspensionUnit<SuspensionUnitData>, SuspensionUnitData>> SUSPENSION = register(PartUnitTypes.SUSPENSION);

    public static final DeferredHolder<PartUnitType<?, ?>, PartUnitType<TrackUnit, TrackUnitData>> TRACK = register(PartUnitTypes.TRACK);

    private static <T extends PartUnit<D>, D extends PartUnitData> DeferredHolder<PartUnitType<?, ?>, PartUnitType<T, D>> register(
            PartUnitType<T, D> type
    ) {
        return PART_UNIT_TYPE.register(type.id().getPath(), () -> type);
    }

    public static void register(IEventBus eventBus) {
        PART_UNIT_TYPE.register(eventBus);
    }

}
