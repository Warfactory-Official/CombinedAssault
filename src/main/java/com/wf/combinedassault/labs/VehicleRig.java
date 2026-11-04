package com.wf.combinedassault.labs;

import com.wf.combinedassault.ballistics.hit.HitMesh;
import com.wf.combinedassault.ballistics.hit.VehicleHits;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/** One vehicle at rest (yaw 0, unticked) with its aim points. Lab frame: vehicle origin, +Z front, +X port, +Y up. */
public final class VehicleRig {

    public record Aim(String name, Vec3 point) {
    }

    final ResourceLocation id;
    final AbstractVehicle vehicle;
    final VehicleHits hits;
    final List<Aim> aims;
    final double reach;
    final List<Vec3> nodeCentres;

    private VehicleRig(ResourceLocation id, AbstractVehicle vehicle, List<Aim> aims, double reach, List<Vec3> nodeCentres) {
        this.id = id;
        this.vehicle = vehicle;
        this.hits = vehicle.hits();
        this.aims = aims;
        this.reach = reach;
        this.nodeCentres = nodeCentres;
    }

    static VehicleRig spawn(ServerLevel level, ResourceLocation id, BaseVehicleData data, boolean parts) {
        AbstractVehicle vehicle = data.construct(level, new Vec3(0, 64, 0), 0, 0);
        vehicle.setDisplayId(id);
        vehicle.initData();
        return of(id, vehicle, parts);
    }

    /** In the level (chunk loaded, never ticked): paths that call {@code AbstractVehicle.hurt} or carry riders. */
    static VehicleRig spawnLive(ServerLevel level, ResourceLocation id, BaseVehicleData data, boolean parts) {
        net.minecraft.core.BlockPos spawn = level.getSharedSpawnPos();
        level.getChunk(spawn);
        AbstractVehicle vehicle = data.construct(level, Vec3.atBottomCenterOf(spawn.above(40)), 0, 0);
        vehicle.setDisplayId(id);
        vehicle.initData();
        if (!level.addFreshEntity(vehicle)) {
            throw new IllegalStateException(id + " could not be added");
        }
        return of(id, vehicle, parts);
    }

    static VehicleRig of(ResourceLocation id, AbstractVehicle vehicle, boolean parts) {
        VehicleHits hits = vehicle.hits();
        double[] box = bounds(vehicle, hits);
        Vec3 centre = new Vec3((box[0] + box[3]) / 2, (box[1] + box[4]) / 2, (box[2] + box[5]) / 2);
        List<Aim> aims = new ArrayList<>();
        aims.add(new Aim("hull", centre));
        if (parts) {
            for (PartUnit<?> part : vehicle.getPartUnits()) {
                if (part.isSeat() || hits.centre(part) == null) {
                    continue;
                }
                aims.add(new Aim(part.getId(), VehicleHits.anchor(hits, part)));
            }
        }
        double reach = 0.5 * Math.sqrt(Math.pow(box[3] - box[0], 2) + Math.pow(box[4] - box[1], 2)
                + Math.pow(box[5] - box[2], 2)) * 1.15 + 1;
        List<Vec3> centres = new ArrayList<>();
        for (int i = 0; i < hits.model().nodes().size(); i++) {
            HitMesh.Node node = hits.model().nodes().get(i).mesh();
            if (!node.module() && node.bounds()[0] <= node.bounds()[3] && hits.live(i)) {
                centres.add(hits.centre(i));
            }
        }
        return new VehicleRig(id, vehicle, List.copyOf(aims), reach, List.copyOf(centres));
    }

    private static double[] bounds(AbstractVehicle vehicle, VehicleHits hits) {
        Vec3 at = vehicle.position();
        Matrix4f pose = new Matrix4f();
        Vector3f corner = new Vector3f();
        double[] box = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int i = 0; i < hits.model().nodes().size(); i++) {
            HitMesh.Node node = hits.model().nodes().get(i).mesh();
            float[] b = node.bounds();
            if (node.module() || b[0] > b[3] || !hits.live(i)) {
                continue;
            }
            hits.pose(i, at, pose);
            for (int k = 0; k < 8; k++) {
                pose.transformPosition(corner.set((k & 1) == 0 ? b[0] : b[3], (k & 2) == 0 ? b[1] : b[4], (k & 4) == 0 ? b[2] : b[5]));
                for (int a = 0; a < 3; a++) {
                    box[a] = Math.min(box[a], corner.get(a));
                    box[a + 3] = Math.max(box[a + 3], corner.get(a));
                }
            }
        }
        if (box[0] > box[3]) {
            throw new IllegalStateException("no drawn geometry");
        }
        for (int a = 0; a < 3; a++) {
            double origin = a == 0 ? at.x : a == 1 ? at.y : at.z;
            box[a] += origin;
            box[a + 3] += origin;
        }
        return box;
    }

    Vec3 local(Vec3 world) {
        return world.subtract(vehicle.position());
    }

    static Map<ResourceLocation, BaseVehicleData> roster(String glob) {
        Predicate<String> pick = glob(glob);
        Map<ResourceLocation, BaseVehicleData> out = new TreeMap<>();
        CommonAssetsManager.vehicleDataManager().getVehicleData().forEach((id, data) -> {
            if (pick.test(id.toString())) {
                out.put(id, data);
            }
        });
        return out;
    }

    static Predicate<String> glob(String spec) {
        if (spec == null || spec.isBlank()) {
            return s -> true;
        }
        List<Pattern> patterns = new ArrayList<>();
        for (String g : spec.split(",")) {
            patterns.add(Pattern.compile(Pattern.quote(g.trim()).replace("*", "\\E.*\\Q")));
        }
        return id -> {
            String path = id.substring(id.indexOf(':') + 1);
            for (Pattern p : patterns) {
                if (p.matcher(id).matches() || p.matcher(path).matches()) {
                    return true;
                }
            }
            return false;
        };
    }
}
