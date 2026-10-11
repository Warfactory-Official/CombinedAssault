package com.wf.combinedassault.vehicle;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Vehicles too long for vanilla's entity queries to find: {@code EntitySectionStorage} only checks sections within
 * two blocks of the query box, entity lives in its position's section. Hull longer than that => invisible to a
 * {@code Level.getEntities} call that misses its centre (every bow-on projectile). Mixin on
 * {@code Level.getEntities} appends what is registered here; list copy-on-write, nearly always empty => one field
 * read per query.
 */
public final class OversizedVehicles {

    /** Structure length past which a vehicle registers. Below it the two-block margin covers the miss. */
    public static final double THRESHOLD = 24.0;

    private static final Map<Level, List<AbstractVehicle>> BY_LEVEL = new ConcurrentHashMap<>();

    private OversizedVehicles() {
    }

    public static boolean qualifies(AbstractVehicle vehicle) {
        return vehicle.getStructureLength() > THRESHOLD;
    }

    public static void setRegistered(AbstractVehicle vehicle, boolean registered) {
        Level level = vehicle.level();
        synchronized (BY_LEVEL) {
            List<AbstractVehicle> current = BY_LEVEL.get(level);
            boolean present = current != null && current.contains(vehicle);
            if (registered == present) {
                return;
            }
            List<AbstractVehicle> next = current == null ? new ArrayList<>(2) : new ArrayList<>(current);
            if (registered) {
                next.add(vehicle);
            } else {
                next.remove(vehicle);
            }
            if (next.isEmpty()) {
                BY_LEVEL.remove(level);
            } else {
                BY_LEVEL.put(level, List.copyOf(next));
            }
        }
    }

    public static void forget(Level level) {
        BY_LEVEL.remove(level);
    }

    public static List<AbstractVehicle> in(Level level) {
        List<AbstractVehicle> vehicles = BY_LEVEL.get(level);
        return vehicles == null ? Collections.emptyList() : vehicles;
    }

    public static void append(Level level, Entity except, AABB box, Predicate<? super Entity> predicate,
                              List<Entity> out) {
        List<AbstractVehicle> vehicles = BY_LEVEL.get(level);
        if (vehicles == null) {
            return;
        }
        for (int i = 0, size = vehicles.size(); i < size; i++) {
            AbstractVehicle vehicle = vehicles.get(i);
            if (vehicle == except || vehicle.isRemoved() || !vehicle.getBoundingBox().intersects(box)
                    || out.contains(vehicle) || !predicate.test(vehicle)) {
                continue;
            }
            out.add(vehicle);
        }
    }

    public static <T extends Entity> void append(Level level, EntityTypeTest<Entity, T> test, AABB box,
                                                 Predicate<? super T> predicate, List<? super T> out, int max) {
        List<AbstractVehicle> vehicles = BY_LEVEL.get(level);
        if (vehicles == null) {
            return;
        }
        for (int i = 0, size = vehicles.size(); i < size && out.size() < max; i++) {
            AbstractVehicle vehicle = vehicles.get(i);
            if (vehicle.isRemoved() || !vehicle.getBoundingBox().intersects(box)) {
                continue;
            }
            T cast = test.tryCast(vehicle);
            if (cast == null || out.contains(cast) || !predicate.test(cast)) {
                continue;
            }
            out.add(cast);
        }
    }

}
