package com.wf.combinedassault.render.gem;

import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.gltf.NodeTable;
import com.wf.gemrender.texture.VariantUv;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import com.wf.combinedassault.ballistics.component.ComponentKind;
import com.wf.combinedassault.ballistics.component.ComponentState;
import com.wf.combinedassault.ballistics.component.NodeSystems;
import com.wf.combinedassault.ballistics.component.VehicleSystems;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;

import java.util.List;
import java.util.Locale;

/**
 * Per-joint damage tile: variant 0 base, 1 {@code _damaged}, 2 {@code _wrecked} ({@link GemRenderModelLoader}).
 * Node -> system: {@link NodeSystems}; a system the vehicle lacks -> HULL. WORN reads as base; DAMAGED -> 1;
 * BROKEN or destroyed -> 2.
 */
@OnlyIn(Dist.CLIENT)
final class DamageSkin {

    /** Kind bits for "running gear": TRACK or WHEEL, whichever the vehicle has. */
    private static final int RUNNING_GEAR = 0xFF;

    private DamageSkin() {
    }

    /** Per joint: {@code kind.ordinal() (or RUNNING_GEAR) | side << 8}. */
    static int[] slotKeys(GemRenderGltfModel model) {
        NodeTable table = model.layout().nodeTable();
        int[] parents = table.parentSlots();
        int[] keys = new int[model.jointCount()];
        for (int slot = 0; slot < keys.length; slot++) {
            int key = -1;
            VehicleSystems.Side side = VehicleSystems.Side.NONE;
            for (int at = slot; at >= 0 && at < parents.length; at = parents[at]) {
                String raw = table.nodeName(at);
                String name = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
                if (side == VehicleSystems.Side.NONE) {
                    side = NodeSystems.side(name);
                }
                if (key == -1) {
                    key = rule(name);
                }
            }
            if (key == -1) {
                key = ComponentKind.HULL.ordinal();
            }
            keys[slot] = key | side.ordinal() << 8;
        }
        return keys;
    }

    private static int rule(String name) {
        return switch (NodeSystems.match(name)) {
            case NONE -> -1;
            case RUNNING_GEAR -> RUNNING_GEAR;
            case KIND -> NodeSystems.kind(name).ordinal();
        };
    }

    /** Writes {@code (u, v)} per joint into {@code out}; false when every joint wears the base tile. */
    static boolean fill(GemRenderGltfModel model, int[] keys, AbstractVehicle vehicle, float[] out) {
        boolean any = false;
        List<VehicleSystems.Cell> cells = VehicleSystems.cells(vehicle);
        boolean tracked = cells.stream().anyMatch(c -> c.kind() == ComponentKind.TRACK);
        ComponentState hull = state(cells, ComponentKind.HULL, VehicleSystems.Side.NONE, ComponentState.PRISTINE);
        for (int slot = 0; slot < keys.length; slot++) {
            int variant;
            if (vehicle.isDestroyed()) {
                variant = 2;
            } else {
                int kindBits = keys[slot] & 0xFF;
                VehicleSystems.Side side = VehicleSystems.Side.values()[keys[slot] >> 8];
                ComponentKind kind = kindBits == RUNNING_GEAR
                        ? (tracked ? ComponentKind.TRACK : ComponentKind.WHEEL)
                        : ComponentKind.values()[kindBits];
                ComponentState state = state(cells, kind, side, null);
                if (state == null) {
                    state = state(cells, kind, VehicleSystems.Side.NONE, hull);
                }
                variant = switch (state) {
                    case PRISTINE, WORN -> 0;
                    case DAMAGED -> 1;
                    case BROKEN -> 2;
                };
            }
            VariantUv uv = model.variant(variant);
            out[slot * 2] = uv.u();
            out[slot * 2 + 1] = uv.v();
            any |= variant != 0;
        }
        return any;
    }

    private static ComponentState state(List<VehicleSystems.Cell> cells, ComponentKind kind, VehicleSystems.Side side,
                                        ComponentState fallback) {
        for (VehicleSystems.Cell cell : cells) {
            if (cell.kind() == kind && cell.side() == side) {
                return cell.state();
            }
        }
        return fallback;
    }
}
