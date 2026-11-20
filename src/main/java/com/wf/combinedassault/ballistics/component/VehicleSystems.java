package com.wf.combinedassault.ballistics.component;

import com.wf.combinedassault.ballistics.hit.VehicleHits;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The vehicle as systems: one {@link Cell} per (kind, side), grouping every part, tagged piece or default pool of
 * that kind. HUD cell, probe row, repair step and the death rule all read this.
 */
public final class VehicleSystems {

    public enum Side {
        NONE(""), LEFT("L-"), RIGHT("R-");

        public final String prefix;

        Side(String prefix) {
            this.prefix = prefix;
        }

        static Side of(String id) {
            String s = id.toLowerCase(Locale.ROOT);
            if (s.contains("left") || s.endsWith("_l")) {
                return LEFT;
            }
            if (s.contains("right") || s.endsWith("_r")) {
                return RIGHT;
            }
            return NONE;
        }
    }

    /** Cell display order (HUD grid, probe rows). */
    private static final ComponentKind[] ORDER = {
            ComponentKind.HULL, ComponentKind.ENGINE, ComponentKind.BARREL, ComponentKind.TRACK, ComponentKind.WHEEL,
            ComponentKind.TURRET, ComponentKind.OPTICS, ComponentKind.FUEL, ComponentKind.AMMO, ComponentKind.ROTOR,
            ComponentKind.TAIL_ROTOR, ComponentKind.WING};

    public interface Member {
        float health();

        float maxHealth();

        void setHealth(float health);

        /** World point of this member's geometry, current tick pose; null = none (hull, implied pool). */
        @Nullable
        default Vec3 anchor(AbstractVehicle vehicle) {
            return null;
        }

        /** Model node names whose geometry is this member; empty = none. */
        default List<String> nodes(AbstractVehicle vehicle) {
            return List.of();
        }

        default float ratio() {
            float max = maxHealth();
            return max <= 0 ? 1 : Mth.clamp(health() / max, 0, 1);
        }
    }

    /** @param unit pool unit within the kind ({@code combinedassault.unit}); "" = kind-wide */
    public record Cell(ComponentKind kind, Side side, String unit, List<Member> members) {

        /** Stable across ticks: the probe action's {@code arg}. */
        public int key() {
            return kind.ordinal() | side.ordinal() << 8 | (unit.hashCode() & 0xFFFFF) << 12;
        }

        public String label() {
            String abbrev = kind == ComponentKind.TRACK && side == Side.NONE ? "TRCK" : kind.abbrev;
            return unit.isEmpty() ? side.prefix + abbrev : side.prefix + abbrev + " " + unit.toUpperCase(Locale.ROOT);
        }

        public float ratio() {
            float min = 1;
            for (Member member : members) {
                min = Math.min(min, member.ratio());
            }
            return min;
        }

        public ComponentState state() {
            return ComponentState.of(ratio());
        }

        public boolean failed() {
            return state() == ComponentState.BROKEN;
        }

        public int stepTicks() {
            return Math.round(kind.stepTicks * (failed() ? ComponentKind.BROKEN_TIME_SCALE : 1));
        }

        /** Every member one band up; false when already full. */
        public boolean step() {
            boolean moved = false;
            for (Member member : members) {
                float max = member.maxHealth();
                float target = ComponentState.of(member.ratio()).stepTarget() * max;
                if (target > member.health()) {
                    member.setHealth(target);
                    moved = true;
                }
            }
            return moved;
        }
    }

    private VehicleSystems() {
    }

    public static List<Cell> cells(AbstractVehicle vehicle) {
        List<Cell> cells = new ArrayList<>();
        cell(cells, ComponentKind.HULL, Side.NONE, "").members.add(new HullMember(vehicle));
        HullComponents pools = vehicle.hullComponents();
        for (int i = 0; i < pools.size(); i++) {
            ComponentKind kind = pools.kind(i);
            if (kind.isSystem()) {
                cell(cells, kind, sided(kind, pools.id(i)), pools.unit(i)).members.add(new PoolMember(pools, i));
            }
        }
        List<PartUnit<?>> parts = new ArrayList<>();
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (ComponentDamage.kind(part).isSystem() && part.getMaxHealth() > 0) {
                parts.add(part);
            }
        }
        List<String> taken = new ArrayList<>();
        for (PartUnit<?> part : parts) {
            ComponentKind kind = ComponentDamage.kind(part);
            Side side = sided(kind, part.getId());
            int same = 0;
            for (PartUnit<?> other : parts) {
                if (ComponentDamage.kind(other) == kind && sided(kind, other.getId()) == side) {
                    same++;
                }
            }
            // Steering kinds: one cell per side (wheel sets, track runs).
            String unit = same > 1 && !kind.steers() ? unitName(kind, side, part.getId(), taken) : "";
            cell(cells, kind, side, unit).members.add(new PartMember(part));
        }
        cells.sort((a, b) -> a.kind != b.kind ? order(a.kind) - order(b.kind)
                : a.side != b.side ? a.side.compareTo(b.side) : a.unit.compareTo(b.unit));
        return cells;
    }

    @Nullable
    public static Cell cell(AbstractVehicle vehicle, int key) {
        for (Cell cell : cells(vehicle)) {
            if (cell.key() == key) {
                return cell;
            }
        }
        return null;
    }

    public static boolean allFailed(AbstractVehicle vehicle) {
        for (Cell cell : cells(vehicle)) {
            if (!cell.failed()) {
                return false;
            }
        }
        return true;
    }

    /** No ENGINE cell (emplacement, trailer) => true; false only when every engine member failed. Cells kept per pool set ({@link #cells}: 5 us a call). */
    public static boolean engineWorks(AbstractVehicle vehicle) {
        if (vehicle.getY() > vehicle.flameoutY()) {
            return false;
        }
        HullComponents pools = vehicle.hullComponents();
        if (vehicle.engineCellPools != pools) {
            vehicle.engineCells.clear();
            for (Cell cell : cells(vehicle)) {
                if (cell.kind == ComponentKind.ENGINE) {
                    vehicle.engineCells.add(cell);
                }
            }
            vehicle.engineCellPools = pools;
        }
        if (vehicle.engineCells.isEmpty()) {
            return true;
        }
        for (Cell cell : vehicle.engineCells) {
            for (Member member : cell.members()) {
                if (ComponentState.of(member.ratio()) != ComponentState.BROKEN) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Mean effectiveness of every engine pool / part; 1 when none declared. Thrust of multi-engine aircraft. */
    public static float engineScale(AbstractVehicle vehicle) {
        float sum = 0;
        int count = 0;
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (ComponentDamage.kind(part) == ComponentKind.ENGINE) {
                sum += ComponentDamage.effectiveness(part);
                count++;
            }
        }
        HullComponents pools = vehicle.hullComponents();
        for (int i = 0, size = pools.size(); i < size; i++) {
            if (pools.kind(i) == ComponentKind.ENGINE) {
                sum += pools.effectiveness(i);
                count++;
            }
        }
        return count == 0 ? 1 : sum / count;
    }

    /** Hull damage beyond zero, split evenly over non-hull members still above zero. */
    public static void overflow(AbstractVehicle vehicle, float amount) {
        List<Member> standing = new ArrayList<>();
        for (Cell cell : cells(vehicle)) {
            if (cell.kind == ComponentKind.HULL) {
                continue;
            }
            for (Member member : cell.members) {
                if (member.health() > 0) {
                    standing.add(member);
                }
            }
        }
        if (standing.isEmpty() || amount <= 0) {
            return;
        }
        float share = amount / standing.size();
        for (Member member : standing) {
            member.setHealth(Math.max(0, member.health() - share));
        }
    }

    private static final java.util.Set<String> GENERIC = java.util.Set.of("gun", "mg", "machine", "weapon", "part",
            "unit", "left", "right", "l", "r");

    /** Four-letter label of one of several same-kind parts: first non-generic id token; duplicates get a digit. */
    private static String unitName(ComponentKind kind, Side side, String id, List<String> taken) {
        String chosen = id;
        for (String token : id.toLowerCase(Locale.ROOT).split("[_\\- .]+")) {
            if (!token.isEmpty() && !GENERIC.contains(token)) {
                chosen = token;
                break;
            }
        }
        String base = chosen.length() > 4 ? chosen.substring(0, 4) : chosen;
        String name = base;
        for (int n = 2; taken.contains(kind + ":" + side + ":" + name); n++) {
            name = base.substring(0, Math.min(base.length(), 3)) + n;
        }
        taken.add(kind + ":" + side + ":" + name);
        return name;
    }

    private static Side sided(ComponentKind kind, String id) {
        return kind.steers() ? Side.of(id) : Side.NONE;
    }

    private static int order(ComponentKind kind) {
        for (int i = 0; i < ORDER.length; i++) {
            if (ORDER[i] == kind) {
                return i;
            }
        }
        return ORDER.length;
    }

    private static Cell cell(List<Cell> cells, ComponentKind kind, Side side, String unit) {
        for (Cell cell : cells) {
            if (cell.kind == kind && cell.side == side && cell.unit.equals(unit)) {
                return cell;
            }
        }
        Cell cell = new Cell(kind, side, unit, new ArrayList<>());
        cells.add(cell);
        return cell;
    }

    private record HullMember(AbstractVehicle vehicle) implements Member {
        public float health() {
            return vehicle.getHealth();
        }

        public float maxHealth() {
            return vehicle.getMaxHealth();
        }

        public void setHealth(float health) {
            vehicle.setHealth(health);
        }
    }

    private record PoolMember(HullComponents pools, int index) implements Member {
        public float health() {
            return pools.health(index);
        }

        public float maxHealth() {
            return pools.maxHealth(index);
        }

        public void setHealth(float health) {
            pools.setHealth(index, health);
        }

        @Override
        public Vec3 anchor(AbstractVehicle vehicle) {
            VehicleHits hits = vehicle.hits();
            String id = pools.id(index);
            return hits == null ? null : hits.centre(i -> id.equals(hits.model().nodes().get(i).systemId()));
        }

        @Override
        public List<String> nodes(AbstractVehicle vehicle) {
            VehicleHits hits = vehicle.hits();
            String id = pools.id(index);
            return hits == null ? List.of() : hits.model().nodes().stream()
                    .filter(n -> id.equals(n.systemId())).map(n -> n.mesh().name()).toList();
        }
    }

    private record PartMember(PartUnit<?> part) implements Member {
        public float health() {
            return part.isDetached() ? 0 : part.getHealth();
        }

        public float maxHealth() {
            return part.getMaxHealth();
        }

        /** Repair onto a popped-off part of a live vehicle refits it. */
        public void setHealth(float health) {
            if (health > 0 && part.isDetached() && !part.getVehicle().isDestroyed()) {
                part.setDetached(false);
            }
            if (!part.isDetached()) {
                part.setHealth(Mth.clamp(health, 0, part.getMaxHealth()));
            }
        }

        @Override
        public Vec3 anchor(AbstractVehicle vehicle) {
            if (part.isDetached()) {
                return null;
            }
            Vec3 centre = vehicle.hits() == null ? null : vehicle.hits().centre(part);
            return centre != null ? centre : part.worldPivotPosition();
        }

        @Override
        public List<String> nodes(AbstractVehicle vehicle) {
            String bone = part.getRenderBoneName();
            return bone == null || bone.isBlank() ? List.of() : List.of(bone);
        }
    }
}
