package com.wf.combinedassault.client.handler;

import com.wf.combinedassault.ballistics.ArmorProfile;
import com.wf.combinedassault.ballistics.ArmorResolver;
import com.wf.combinedassault.client.render.util.HullRenderer;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.hit.VehicleHits;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import org.apache.commons.lang3.tuple.Pair;
import org.joml.Vector3f;

import java.util.List;
import java.util.Locale;

/**
 * F3 plate under the crosshair, as {@link ArmorResolver} meets a round on the camera ray. Left column: right one runs
 * off-screen under targeted-block tags.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class ArmorDebugText {

    private ArmorDebugText() {}

    @SubscribeEvent
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.getDebugOverlay().showDebugScreen() || mc.player == null) {
            return;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 start = camera.getPosition();
        Vec3 dir = new Vec3(camera.getLookVector());
        Pair<Entity, Vec3> target = VectorUtil.hitObbPosition(mc.player, start,
                start.add(dir.scale(LocalVehiclePlayer.renderDistance())));
        if (target == null || !(target.getLeft() instanceof AbstractVehicle vehicle)) {
            return;
        }
        List<String> lines = event.getLeft();
        lines.add("");
        lines.add("Vehicle armor: " + vehicle.getDisplayName().getString());
        ArmorProfile profile = vehicle.armorProfile;
        if (profile == null || !profile.isDefined()) {
            lines.add("Unarmored (no profile)");
            return;
        }
        if (mc.getEntityRenderDispatcher().shouldRenderHitBoxes()) {
            lines.add(String.format(Locale.ROOT, "Mesh: KE LOS from here, blue %.0f -> red %.0f mm (log), grey 0",
                    HullRenderer.RAMP_LO, HullRenderer.RAMP_HI));
        }
        // Past the outer surface: hitObbPosition can stop short of the mesh.
        Vec3 end = target.getRight().add(dir.scale(0.1));
        VehicleHits hits = vehicle.hits();
        VehicleHits.Hit hit = hits == null ? null : hits.surface(start, end);
        ArmorResolver.Plate ke = hit != null
                ? ArmorResolver.plate(vehicle, profile, hit.node(), hit.point(), hit.normal(), hit.armor(), dir, false)
                : ArmorResolver.plate(vehicle, profile, start, end, dir, false);
        ArmorResolver.Plate ce = hit != null
                ? ArmorResolver.plate(vehicle, profile, hit.node(), hit.point(), hit.normal(), hit.armor(), dir, true)
                : ArmorResolver.plate(vehicle, profile, start, end, dir, true);
        if (hit != null) {
            Vector3f at = hits.restPoint(hit.node(), hit.point());
            lines.add(String.format(Locale.ROOT, "Model %.2f %.2f %.2f%s", at.x, at.y, at.z,
                    ke != null && ke.part() != null ? " (" + ke.part() + " at rest)" : ""));
        }
        if (ke == null || ce == null) {
            lines.add("0 mm");
            return;
        }
        lines.add(String.format(Locale.ROOT, "%s%s%s: KE %.0f / CE %.0f mm %s, wear x%.2f", ke.face(),
                ke.part() == null ? "" : " " + ke.part(), ke.zone() == null ? "" : " zone " + ke.zone(),
                ke.nominal(), ce.nominal(), ke.plate() ? "plate" : "RHAe", ke.wear()));
        lines.add(String.format(Locale.ROOT, "LOS KE %.0f / CE %.0f mm @ %.1f deg, surface %.1f deg%s",
                ke.effective(), ce.effective(), ke.incidence(), ke.surfaceIncidence(),
                ke.surfaceIncidence() > profile.ricochetAngle
                        ? String.format(Locale.ROOT, " > ricochet %.0f", profile.ricochetAngle) : ""));
    }
}
