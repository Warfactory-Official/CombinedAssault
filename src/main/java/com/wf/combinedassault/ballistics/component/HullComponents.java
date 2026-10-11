package com.wf.combinedassault.ballistics.component;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.NoneVehicle;
import com.wf.combinedassault.entity.vehicle.TrackedVehicle;
import com.wf.combinedassault.entity.vehicle.WheeledVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.hit.HitModel;
import com.wf.combinedassault.vehicle.hit.VehicleHits;

import java.util.ArrayList;
import java.util.List;

/**
 * Health pools of systems that are not parts, declared via {@link HitModel.Node#systemId()} or defaulted when
 * a vehicle lacks a kind it needs. Defaults have no geometry: {@code DamageSystem} routes to them by hit height
 * and side. Server authoritative; client reads {@link #toSync()} via entity data.
 */
public final class HullComponents {

    public static final HullComponents NONE = new HullComponents(List.of(), List.of(), new float[0], new boolean[0]);

    private static final String HEALTH_KEY = "HullComponents";
    public static final float DEFAULT_HEALTH_SHARE = 0.3f;

    private final List<String> ids;
    private final List<ComponentKind> kinds;
    private final float[] maxHealth;
    private final float[] health;
    private final boolean[] implicit;
    private boolean dirty;

    private HullComponents(List<String> ids, List<ComponentKind> kinds, float[] maxHealth, boolean[] implicit) {
        this.ids = ids;
        this.kinds = kinds;
        this.maxHealth = maxHealth;
        this.health = maxHealth.clone();
        this.implicit = implicit;
    }

    /** Node with no mesh/disc (failed load, or none declared) counts as absent, not a system. */
    public static HullComponents of(AbstractVehicle vehicle) {
        List<String> ids = new ArrayList<>();
        List<ComponentKind> kinds = new ArrayList<>();
        List<Float> pools = new ArrayList<>();
        float vehicleHealth = vehicle.getMaxHealth();

        VehicleHits hits = vehicle.hits();
        if (hits != null) {
            for (HitModel.Node node : hits.model().nodes()) {
                boolean hittable = node.mesh().mesh() != null || node.mesh().disc() != null;
                if (hittable && node.kind() != null && node.kind().isSystem()
                        && (node.part() == null || node.mesh().module())) {
                    float health = node.mesh().health() > 0 ? node.mesh().health()
                            : Math.max(1, vehicleHealth * DEFAULT_HEALTH_SHARE);
                    pool(node.systemId(), node.kind(), health, ids, kinds, pools);
                }
            }
        }
        int declared = ids.size();
        if (!(vehicle instanceof NoneVehicle) && !declares(vehicle, kinds, ComponentKind.ENGINE)) {
            implied("engine", ComponentKind.ENGINE, vehicleHealth, ids, kinds, pools);
        }
        if (vehicle instanceof TrackedVehicle && !declares(vehicle, kinds, ComponentKind.TRACK)) {
            implied("left track", ComponentKind.TRACK, vehicleHealth, ids, kinds, pools);
            implied("right track", ComponentKind.TRACK, vehicleHealth, ids, kinds, pools);
        }
        if (vehicle instanceof WheeledVehicle && !declares(vehicle, kinds, ComponentKind.WHEEL)) {
            implied("wheels", ComponentKind.WHEEL, vehicleHealth, ids, kinds, pools);
        }
        if (ids.isEmpty()) {
            return NONE;
        }
        float[] maxHealth = new float[pools.size()];
        boolean[] implicit = new boolean[pools.size()];
        for (int i = 0; i < maxHealth.length; i++) {
            maxHealth[i] = pools.get(i);
            implicit[i] = i >= declared;
        }
        return new HullComponents(List.copyOf(ids), List.copyOf(kinds), maxHealth, implicit);
    }

    private static boolean declares(AbstractVehicle vehicle, List<ComponentKind> kinds, ComponentKind kind) {
        if (kinds.contains(kind)) {
            return true;
        }
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part.getMaxHealth() > 0 && ComponentDamage.kind(part) == kind) { // = VehicleSystems.cells
                return true;
            }
        }
        return false;
    }

    private static void implied(String id, ComponentKind kind, float vehicleHealth,
                                List<String> ids, List<ComponentKind> kinds, List<Float> pools) {
        ids.add(id);
        kinds.add(kind);
        pools.add(Math.max(1, vehicleHealth * DEFAULT_HEALTH_SHARE));
    }

    private static void pool(String id, ComponentKind kind, float health,
                             List<String> ids, List<ComponentKind> kinds, List<Float> pools) {
        int at = ids.indexOf(id);
        if (at >= 0) {
            // Larger pool wins, no sum: an engine split into block + radiator is not twice as tough.
            pools.set(at, Math.max(pools.get(at), health));
            return;
        }
        ids.add(id);
        kinds.add(kind);
        pools.add(health);
    }

    public boolean isEmpty() {
        return ids.isEmpty();
    }

    public int size() {
        return ids.size();
    }

    public String id(int index) {
        return ids.get(index);
    }

    public ComponentKind kind(int index) {
        return kinds.get(index);
    }

    public boolean isImplicit(int index) {
        return implicit[index];
    }

    public float health(int index) {
        return health[index];
    }

    public float maxHealth(int index) {
        return maxHealth[index];
    }

    public void setHealth(int index, float value) {
        float clamped = Mth.clamp(value, 0, maxHealth[index]);
        if (clamped != health[index]) {
            health[index] = clamped;
            dirty = true;
        }
    }

    public int indexOf(String id) {
        return ids.indexOf(id);
    }

    public ComponentState state(int index) {
        float max = maxHealth[index];
        return max <= 0 ? ComponentState.PRISTINE
                : ComponentState.of(Mth.clamp(health[index] / max, 0, 1));
    }

    /** Interpolated inside its band; BROKEN = 0. */
    public float effectiveness(int index) {
        float max = maxHealth[index];
        if (max <= 0) {
            return 1;
        }
        float ratio = Mth.clamp(health[index] / max, 0, 1);
        ComponentState state = ComponentState.of(ratio);
        if (state == ComponentState.BROKEN) {
            return 0;
        }
        ComponentState above = switch (state) {
            case DAMAGED -> ComponentState.WORN;
            default -> ComponentState.PRISTINE;
        };
        float span = above.minRatio - state.minRatio;
        float within = span <= 0 ? 1 : (ratio - state.minRatio) / span;
        return Mth.lerp(Mth.clamp(within, 0, 1), state.effectiveness, above.effectiveness);
    }

    /** False when this vehicle has no such system. */
    public boolean hurt(String id, float amount) {
        int at = indexOf(id);
        if (at < 0 || amount <= 0) {
            return false;
        }
        setHealth(at, health[at] - amount);
        return true;
    }

    /** Changed since the last {@link #toSync()}. */
    public boolean dirty() {
        return dirty;
    }

    /** Every pool by id; the client's copy is rebuilt from this. */
    public CompoundTag toSync() {
        dirty = false;
        CompoundTag tag = new CompoundTag();
        for (int i = 0; i < health.length; i++) {
            tag.putFloat(ids.get(i), health[i]);
        }
        return tag;
    }

    public void fromSync(CompoundTag tag) {
        for (int i = 0; i < health.length; i++) {
            if (tag.contains(ids.get(i))) {
                health[i] = Mth.clamp(tag.getFloat(ids.get(i)), 0, maxHealth[i]);
            }
        }
    }

    /** Damaged pools only. */
    public void save(CompoundTag compound) {
        if (isEmpty()) {
            return;
        }
        CompoundTag stored = new CompoundTag();
        for (int i = 0; i < health.length; i++) {
            if (health[i] < maxHealth[i]) {
                stored.putFloat(ids.get(i), health[i]);
            }
        }
        if (!stored.isEmpty()) {
            compound.put(HEALTH_KEY, stored);
        }
    }

    /** A saved id this vehicle no longer has is dropped; one it lacks stays full. */
    public void load(@Nullable CompoundTag compound) {
        if (compound == null || !compound.contains(HEALTH_KEY)) {
            return;
        }
        CompoundTag stored = compound.getCompound(HEALTH_KEY);
        for (int i = 0; i < health.length; i++) {
            health[i] = stored.contains(ids.get(i))
                    ? Mth.clamp(stored.getFloat(ids.get(i)), 0, maxHealth[i])
                    : maxHealth[i];
        }
        dirty = true;
    }

}
