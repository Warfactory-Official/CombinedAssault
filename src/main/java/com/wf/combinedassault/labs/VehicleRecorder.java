package com.wf.combinedassault.labs;

import com.wf.combinedassault.ballistics.event.HitVehicleEvent;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.damage.api.event.ProjectileStrikeEvent;
import com.wf.wflib.labs.api.LabRecorder;
import com.wf.wflib.labs.api.LabTable;
import com.wf.wflib.round.api.KineticPreset;
import com.wf.wflib.round.api.Rounds;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Live vehicle hits as {@code combinedassault:vehicle_shots} rows; dims and measures named as in {@code vehicle}. */
final class VehicleRecorder {

    static final LabTable TABLE = LabTable.of("combinedassault:vehicle_shots")
            .dimStr("vehicle").dimStr("round").dimNum("azimuth_deg", "deg").dimNum("elevation_deg", "deg")
            .measureStr("verdict").measure("pen_mm", "mm_rha").measure("hull_damage", "").measure("speed_mps", "m/s")
            .measure("hit_x_m", "m").measure("hit_y_m", "m").measure("hit_z_m", "m")
            .build();

    private static final ThreadLocal<Strike> LAST = new ThreadLocal<>();

    private record Strike(AbstractVehicle vehicle, String round, double penetration, double speed) {
    }

    private VehicleRecorder() {
    }

    static void strike(ProjectileStrikeEvent e) {
        if (!LabRecorder.active() || !(e.target() instanceof AbstractVehicle vehicle) || !(vehicle.level() instanceof ServerLevel level)) {
            LAST.remove();
            return;
        }
        KineticPreset preset = e.key() instanceof Long key ? Rounds.preset(level, key) : null;
        LAST.set(new Strike(vehicle, preset == null ? "" : preset.id().toString(), e.threat().penetrationMm(),
                e.velocity().length() * 20.0));
    }

    static void hit(HitVehicleEvent e) {
        Strike s = LAST.get();
        LAST.remove();
        if (s == null || s.vehicle.getId() != e.entityId) {
            return;
        }
        AbstractVehicle v = s.vehicle;
        Vector3f toShooter = new Quaternionf(v.rotYXZ()).conjugate().transform(
                new Vector3f((float) -e.hitVector.x, (float) -e.hitVector.y, (float) -e.hitVector.z).normalize());
        double azimuth = Math.toDegrees(Math.atan2(toShooter.x, toShooter.z));
        if (azimuth < 0) {
            azimuth += 360;
        }
        double elevation = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, toShooter.y))));
        Vector3f local = new Quaternionf(v.rotYXZ()).conjugate().transform(
                new Vector3f((float) (e.hitPosition.x - v.getX()), (float) (e.hitPosition.y - v.getY()),
                        (float) (e.hitPosition.z - v.getZ())));
        String key = e.message.getContents() instanceof TranslatableContents t ? t.getKey() : "";
        String verdict = key.startsWith("message.vehicle.ballistics.")
                ? key.substring(key.lastIndexOf('.') + 1).toUpperCase(java.util.Locale.ROOT) : "NO_MODEL";
        LabRecorder.record(TABLE.name(), TABLE, v.getDisplayId().toString(), s.round, azimuth, elevation, verdict,
                s.penetration, e.damage, s.speed, local.x, local.y, local.z);
    }

}
