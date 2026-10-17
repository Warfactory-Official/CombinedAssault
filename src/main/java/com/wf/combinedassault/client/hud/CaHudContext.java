package com.wf.combinedassault.client.hud;

import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.FixedWingVehicle;
import com.wf.combinedassault.entity.vehicle.RotaryWingVehicle;
import com.wf.combinedassault.entity.vehicle.ShipVehicle;
import com.wf.combinedassault.fpv.payload.PayloadUnit;
import com.wf.combinedassault.indirect.client.IndirectView;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;
import com.wf.wflib.ui.Subject;
import com.wf.wflib.ui.UiContext;

import java.util.List;

import static com.wf.combinedassault.client.hud.HudSubjects.*;

/** Subjects of the local player's ride, resolved once per frame. */
final class CaHudContext implements UiContext {
    AbstractVehicle vehicle;
    WeaponUnit station;
    AbstractVehicleWeapon<?> primary;
    AbstractVehicleWeapon<?> secondary;
    AbstractVehicleWeapon<?> independent;
    PayloadUnit payload;
    IndirectView indirect;

    void refresh(LocalVehiclePlayer local) {
        vehicle = local.onVehicle() ? local.vehicle : null;
        station = null;
        primary = null;
        secondary = null;
        independent = null;
        payload = null;
        if (vehicle == null) {
            indirect = IndirectView.refresh(null, 0);
            return;
        }
        PartUnit<?> operated = vehicle.getOwnOperatorUnit(local.getPlayer());
        if (operated instanceof WeaponUnit unit) {
            station = unit;
            List<AbstractVehicleWeapon<?>> weapons = HudCache.weapons(unit);
            AbstractVehicleWeapon<?> main = unit.currentWeapon();
            AbstractVehicleWeapon<?> second = unit.currentSecondaryWeapon();
            primary = main != null && weapons.contains(main) ? main
                    : second != null && weapons.contains(second) ? second
                    : weapons.isEmpty() ? null : weapons.get(0);
            if (primary != null) {
                WeaponUnit root = primary.getWeaponUnit().getRootParentWeaponUnit();
                secondary = root.currentSecondaryWeapon();
                independent = root.independentWeapons.isEmpty() ? null : root.independentWeapons.get(0);
            }
        } else {
            // Spotter seat: the sight it looks through, no weapons.
            station = local.sightUnit();
        }
        PayloadUnit hardpoint = PayloadUnit.on(vehicle);
        payload = hardpoint != null && hardpoint.isLoaded() ? hardpoint : null;
        indirect = IndirectView.refresh(station, HudSignals.partialTick());
    }

    @Override
    public Object subject(Subject<?> subject) {
        if (subject == VEHICLE) {
            return vehicle;
        }
        if (subject == STATION) {
            return station;
        }
        if (subject == PRIMARY) {
            return primary;
        }
        if (subject == SECONDARY) {
            return secondary;
        }
        if (subject == INDEPENDENT) {
            return independent;
        }
        if (subject == PAYLOAD) {
            return payload;
        }
        if (subject == IndirectView.SUBJECT) {
            return indirect;
        }
        if (subject == SHIP) {
            return vehicle instanceof ShipVehicle ship ? ship : null;
        }
        if (subject == ROTARY) {
            return vehicle instanceof RotaryWingVehicle rotary ? rotary : null;
        }
        if (subject == FIXED) {
            return vehicle instanceof FixedWingVehicle fixed ? fixed : null;
        }
        return null;
    }
}
