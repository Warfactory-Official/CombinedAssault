package com.wf.combinedassault.hud;

import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.aircraft.FixedWingVehicle;
import com.wf.combinedassault.aircraft.RotaryWingVehicle;
import com.wf.combinedassault.naval.ShipVehicle;
import com.wf.combinedassault.uav.fpv.payload.PayloadUnit;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.AbstractVehicleWeapon;
import com.wf.wflib.ui.api.Subject;

/** CA's UI subjects; supplied per frame by {@link CaHudContext}. */
public final class HudSubjects {
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static final Class<AbstractVehicleWeapon<?>> WEAPON_TYPE = (Class) AbstractVehicleWeapon.class;

    public static final Subject<AbstractVehicle> VEHICLE = Subject.register("vehicle", AbstractVehicle.class);
    public static final Subject<LocalVehiclePlayer> LOCAL = Subject.register("local", LocalVehiclePlayer.class,
            () -> LocalVehiclePlayer.instance());
    public static final Subject<WeaponUnit> STATION = Subject.register("station", WeaponUnit.class);
    public static final Subject<RotaryWingVehicle> ROTARY = Subject.register("rotary", RotaryWingVehicle.class);
    public static final Subject<FixedWingVehicle> FIXED = Subject.register("fixed_wing", FixedWingVehicle.class);
    public static final Subject<ShipVehicle> SHIP = Subject.register("ship", ShipVehicle.class);
    public static final Subject<PayloadUnit> PAYLOAD = Subject.register("payload", PayloadUnit.class);
    public static final Subject<AbstractVehicleWeapon<?>> PRIMARY = Subject.register("primary", WEAPON_TYPE);
    public static final Subject<AbstractVehicleWeapon<?>> SECONDARY = Subject.register("secondary", WEAPON_TYPE);
    public static final Subject<AbstractVehicleWeapon<?>> INDEPENDENT = Subject.register("independent", WEAPON_TYPE);
    public static final Subject<AbstractVehicleWeapon<?>> WEAPON = Subject.register("weapon", WEAPON_TYPE);
    public static final Subject<HudCache.SystemCell> SYSTEM = Subject.register("system", HudCache.SystemCell.class);
    public static final Subject<AbstractVehicle.Seat> SEAT = Subject.register("seat", AbstractVehicle.Seat.class);

    private HudSubjects() {
    }
}
