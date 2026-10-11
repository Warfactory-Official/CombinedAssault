package com.wf.combinedassault.vehicle.passenger;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import com.wf.combinedassault.audio.VehicleSounds;
import com.wf.wflib.audio.AudioCategory;
import com.wf.wflib.audio.client.AlertChannel;
import com.wf.wflib.audio.client.Cue;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.network.message.ServerVehicleWarn;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.pojo.WarnType;

import java.util.concurrent.ConcurrentHashMap;

public class WarningReceiver {

    public AbstractVehicle vehicle;
    /** Launch > lock; client only. */
    private AlertChannel alerts;
    public ConcurrentHashMap<Integer, WarnTarget> targets = new ConcurrentHashMap<>();

    public WarningReceiver(AbstractVehicle vehicle) {
        this.vehicle = vehicle;
    }

    @OnlyIn(Dist.CLIENT)
    public static void handle(ServerVehicleWarn message) {
        Level level = Minecraft.getInstance().level;
        if (level.getEntity(message.toEntityId) instanceof AbstractVehicle toVehicle
                && toVehicle.equals(LocalVehiclePlayer.instance.vehicle)) {
            // RWR vertical FOV: |pitch| > 45 deg ignored.
            Entity entity = level.getEntity(message.fromEntityId);
            if (entity == null) {
                LocalVehiclePlayer.ServerEntity serverEntity = LocalVehiclePlayer.instance.serverEntities.get(message.fromEntityId);
                if (serverEntity == null || serverEntity.entity == null) {
                    return;
                }
                entity = serverEntity.entity;
            }
            Vec3 direction = toVehicle.relativeRotDirection(entity.position().subtract(toVehicle.position()), true);
            if (Math.abs(VectorUtil.vecToRot(direction).x) > 45) {
                return;
            }
            WarningReceiver warningReceiver = toVehicle.warningReceiver;
            if (warningReceiver != null) {
                if (message.warnType == WarnType.RADAR_SEARCH) {
                    WarnTarget warnTarget = warningReceiver.targets.get(message.fromEntityId);
                    if (warnTarget != null && warnTarget.warnType != WarnType.RADAR_SEARCH) {
                        return;
                    }
                    Minecraft.getInstance().getSoundManager().play(
                            new Cue(VehicleSounds.RADAR_SEARCH_WARN, AudioCategory.ALERTS, false));
                    warningReceiver.targets.put(message.fromEntityId, new WarnTarget(message.warnType, message.info, System.currentTimeMillis()));
                }
                warningReceiver.targets.put(message.fromEntityId, new WarnTarget(message.warnType, message.info, System.currentTimeMillis()));
            }
        }
    }

    public void tick() {
        targets.values().removeIf(warnTarget -> warnTarget.receivedTime + 500 < System.currentTimeMillis());
        if (alerts == null) {
            alerts = new AlertChannel();
        }
        for (WarnTarget t : targets.values()) {
            if (t.warnType == WarnType.MISSILE_LAUNCH) {
                alerts.want(VehicleSounds.MISSILE_LAUNCH_WARN, 2);
            } else if (t.warnType == WarnType.RADAR_LOCK) {
                alerts.want(VehicleSounds.RADAR_LOCK_WARN, 1);
            }
        }
        alerts.tick();
    }

    public void clear() {
        targets.clear();
        if (alerts != null) {
            alerts.stop();
        }
    }

    public record WarnTarget(WarnType warnType, String info, Long receivedTime) {}

}
