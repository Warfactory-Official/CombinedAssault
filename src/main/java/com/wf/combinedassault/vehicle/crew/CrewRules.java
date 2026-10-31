package com.wf.combinedassault.vehicle.crew;

import com.wf.combinedassault.vehicle.VehicleConfig;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.weapon.part.WeaponUnit;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** {@code crew.required} gate: off => unrestricted. Server config, synced: valid on the client too. */
public final class CrewRules {

    private CrewRules() {
    }

    public static boolean enabled() {
        return VehicleConfig.crewRequired().get();
    }

    /** Pack {@code crew_roles}, else seat 0 = driver (+ gunner on a weapon seat), weapon seat = gunner, other = commander. */
    public static Set<CrewRole> roles(AbstractVehicle.Seat seat) {
        List<CrewRole> declared = seat.partUnit.getData().getCrewRoles();
        if (declared != null && !declared.isEmpty()) {
            return EnumSet.copyOf(declared);
        }
        EnumSet<CrewRole> roles = EnumSet.noneOf(CrewRole.class);
        if (seat.seatIndex == 0) {
            roles.add(CrewRole.DRIVER);
        }
        roles.add(seat.partUnit instanceof WeaponUnit ? CrewRole.GUNNER : CrewRole.COMMANDER);
        return roles;
    }

    /** True iff seat's occupant should be the {@code ControlUnit} operator. */
    public static boolean drives(AbstractVehicle vehicle, AbstractVehicle.Seat seat) {
        if (!roles(seat).contains(CrewRole.DRIVER)) {
            // Uncrewed: no driver seat occupied => seat 0 drives (solo from the gun).
            return !enabled() && seat.seatIndex == 0 && vehicle.seats.stream()
                    .noneMatch(other -> other.passengerId != -1 && roles(other).contains(CrewRole.DRIVER));
        }
        // First occupied driver seat wins; later driver seats are relief drivers.
        for (AbstractVehicle.Seat other : vehicle.seats) {
            if (other == seat) {
                return true;
            }
            if (other.passengerId != -1 && roles(other).contains(CrewRole.DRIVER)) {
                return false;
            }
        }
        return false;
    }

    @Nullable
    private static AbstractVehicle.Seat seatOf(AbstractVehicle vehicle, Entity entity) {
        for (AbstractVehicle.Seat seat : vehicle.seats) {
            if (seat.passengerId == entity.getId()) {
                return seat;
            }
        }
        return null;
    }

    public static boolean has(AbstractVehicle vehicle, Entity entity, CrewRole role) {
        if (!enabled()) {
            return true;
        }
        AbstractVehicle.Seat seat = seatOf(vehicle, entity);
        return seat != null && roles(seat).contains(role);
    }

    /** Whether entity may aim/fire/switch {@code unit}: the gunner of its seat. */
    public static boolean mayOperate(Entity entity, PartUnit<?> unit) {
        return !enabled() || has(unit.getVehicle(), entity, CrewRole.GUNNER);
    }

    public static boolean loaderAboard(AbstractVehicle vehicle) {
        return loadersAboard(vehicle) > 0;
    }

    public static int loadersAboard(AbstractVehicle vehicle) {
        if (!enabled()) {
            return 0;
        }
        int count = 0;
        for (AbstractVehicle.Seat seat : vehicle.seats) {
            if (seat.passengerId != -1 && roles(seat).contains(CrewRole.LOADER)) {
                count++;
            }
        }
        return count;
    }

    public static WeaponLoading loading(WeaponUnit unit) {
        return enabled() ? unit.getLoading() : WeaponLoading.SELF;
    }

    /** Reload may progress this tick. */
    public static boolean canLoad(WeaponUnit unit) {
        return loading(unit) != WeaponLoading.MANUAL || loaderAboard(unit.getVehicle());
    }

    /** Autoloader: a loader speeds it up. Manual: a second loader does. */
    public static int reloadTicks(WeaponUnit unit, int base) {
        int loaders = loadersAboard(unit.getVehicle());
        boolean faster = switch (loading(unit)) {
            case AUTOLOADER -> loaders >= 1;
            case MANUAL -> loaders >= 2;
            case SELF -> false;
        };
        return faster
                ? Math.max(1, Math.round(base * (float) VehicleConfig.server().crewLoaderReloadMultiplier()))
                : base;
    }

    /** Ammo-type switch on a loader-served station: the loader's, while one is aboard. */
    public static boolean maySwitchAmmo(Entity entity, WeaponUnit unit) {
        if (loading(unit) == WeaponLoading.SELF || !loaderAboard(unit.getVehicle())) {
            return mayOperate(entity, unit);
        }
        return has(unit.getVehicle(), entity, CrewRole.LOADER);
    }
}
