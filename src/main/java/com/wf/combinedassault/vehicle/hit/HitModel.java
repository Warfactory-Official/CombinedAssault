package com.wf.combinedassault.vehicle.hit;

import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.component.ComponentKind;
import com.wf.combinedassault.ballistics.component.NodeSystems;
import com.wf.combinedassault.ballistics.component.VehicleSystems;
import com.wf.combinedassault.vehicle.structure.VehicleCubeGroup;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A {@link HitMesh} bound to one vehicle type's structure: each node rides a structure group (so the server's part
 * poses move it), belongs to a part or to a hull system, and knows its rest transform into that group's frame.
 */
public final class HitModel {

    /**
     * @param group     slot in {@link #groups}
     * @param part      owning part id (hit => that part), or null
     * @param kind      hull system when no part owns it, or null
     * @param systemId  {@code HullComponents} pool id for {@code kind}
     * @param toGroup   node frame -> group frame rotation; {@code offset} its translation
     * @param bounds    node frame
     */
    public record Node(HitMesh.Node mesh, int group, @Nullable String part, @Nullable ComponentKind kind,
                       String systemId, Quaternionf toGroup, Vector3f offset, float[] bounds) {
    }

    private final HitMesh mesh;
    private final List<VehicleCubeGroup> groups;
    private final List<Node> nodes;
    /** Per group slot: rest global transform, inverted. */
    private final Quaternionf[] groupRestInverse;
    private final Vector3f[] groupRestOffset;

    private HitModel(HitMesh mesh, List<VehicleCubeGroup> groups, List<Node> nodes) {
        this.mesh = mesh;
        this.groups = groups;
        this.nodes = nodes;
        groupRestInverse = new Quaternionf[groups.size()];
        groupRestOffset = new Vector3f[groups.size()];
        for (int i = 0; i < groups.size(); i++) {
            VehicleCubeGroup.GlobalTransform rest = groups.get(i).globalTransform();
            groupRestInverse[i] = new Quaternionf(rest.rotation()).conjugate();
            groupRestOffset[i] = new Vector3f((float) rest.offset().x, (float) rest.offset().y, (float) rest.offset().z);
        }
    }

    /** Node frame {@code origin, rotation} (model) -> {@code toGroup, offset} in group slot {@code slot}'s rest frame. */
    public void toGroup(int slot, Vector3f origin, Quaternionf rotation, Quaternionf toGroup, Vector3f offset) {
        groupRestInverse[slot].mul(rotation, toGroup);
        groupRestInverse[slot].transform(offset.set(origin).sub(groupRestOffset[slot]));
    }

    public HitMesh mesh() {
        return mesh;
    }

    /** Type-level groups; a vehicle maps part groups to its own copies. */
    public List<VehicleCubeGroup> groups() {
        return groups;
    }

    public List<Node> nodes() {
        return nodes;
    }

    /** First node of that name, or -1. */
    public int indexOf(String name) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).mesh().name().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @param frameGroups per {@link ModelRig} frame: the type-level group it built
     * @param frameParts  per frame: the part id it frames if the vehicle has that part, else null
     * @param frameParent per frame: parent frame, -1 for the body
     * @param tracked     wheel-kind running gear => TRACK
     */
    public static HitModel bind(String vehicle, HitMesh mesh, List<VehicleCubeGroup> frameGroups,
                                List<String> frameParts, int[] frameParent, boolean tracked) {
        List<VehicleCubeGroup> groups = new ArrayList<>();
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < mesh.nodes().size(); i++) {
            HitMesh.Node node = mesh.nodes().get(i);
            VehicleCubeGroup group = frameGroups.get(node.frame());
            int slot = groups.indexOf(group);
            if (slot < 0) {
                slot = groups.size();
                groups.add(group);
            }
            String part = null;
            for (int frame = node.frame(); frame >= 0 && part == null; frame = frameParent[frame]) {
                part = frameParts.get(frame);
            }
            ComponentKind kind = null;
            VehicleSystems.Side side = VehicleSystems.Side.NONE;
            for (int at = i; at >= 0; at = mesh.nodes().get(at).parent()) {
                String s = mesh.nodes().get(at).side();
                if (!s.isEmpty()) {
                    side = VehicleSystems.Side.valueOf(s.toUpperCase(java.util.Locale.ROOT));
                    break;
                }
            }
            if (part == null || node.module()) {
                for (int at = i; at >= 0; at = mesh.nodes().get(at).parent()) {
                    String component = mesh.nodes().get(at).component();
                    if (!component.isEmpty()) {
                        kind = component.equalsIgnoreCase("none") ? null : ComponentKind.byName(component);
                        break;
                    }
                }
                if (tracked && kind == ComponentKind.WHEEL) {
                    kind = ComponentKind.TRACK;
                }
            }
            VehicleCubeGroup.GlobalTransform rest = group.globalTransform();
            Quaternionf inverse = new Quaternionf(rest.rotation()).conjugate();
            Quaternionf toGroup = inverse.mul(node.rotation(), new Quaternionf());
            Vector3f offset = new Quaternionf(rest.rotation()).conjugate().transform(new Vector3f(node.origin()).sub(
                    (float) rest.offset().x, (float) rest.offset().y, (float) rest.offset().z));
            String systemId = kind == null ? "" : systemId(kind, side);
            nodes.add(new Node(node, slot, part, kind, systemId, toGroup, offset, node.bounds()));
        }
        return new HitModel(mesh, List.copyOf(groups), List.copyOf(nodes));
    }

    /** Pool id: tracks and wheels by side, as the implicit defaults name them; else the code, spaced. */
    public static String systemId(ComponentKind kind, VehicleSystems.Side side) {
        String prefix = switch (side) {
            case LEFT -> "left ";
            case RIGHT -> "right ";
            case NONE -> "";
        };
        return switch (kind) {
            case TRACK -> prefix + "track";
            case WHEEL -> prefix + "wheels";
            default -> kind.code.replace('_', ' ');
        };
    }
}
