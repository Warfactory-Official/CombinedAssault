package com.wf.combinedassault.compat.wflib;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.api.BeamSource;
import com.wf.wflib.api.SeekerLockEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec2;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.network.message.ServerVehicleWarn;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.combinedassault.vehicle.part.RadarUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.pojo.WarnType;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which weapon unit guides which WFLib round: the vehicle's side of {@code TargetIlluminator} / {@link BeamSource}.
 * Transient: a round loaded from disk has no guiding unit and flies on its last aim point.
 */
@EventBusSubscriber(modid = CombinedAssault.MOD_ID)
public final class GuidedRounds {

    private static final Map<UUID, WeaponUnit> GUIDING = new ConcurrentHashMap<>();

    private GuidedRounds() {
    }

    static void guide(MissileEntity missile, WeaponUnit unit) {
        GUIDING.put(missile.getUUID(), unit);
    }

    /** Fire-control radar lock of the guiding unit. */
    @Nullable
    public static Entity illuminated(Entity missile) {
        WeaponUnit unit = GUIDING.get(missile.getUUID());
        RadarUnit radar = unit == null ? null : unit.getMainRadarUnit();
        return radar == null ? null : radar.getLockedEntity();
    }

    /** Guiding unit's line of sight. */
    @Nullable
    public static BeamSource.Beam beam(Entity missile) {
        WeaponUnit unit = GUIDING.get(missile.getUUID());
        if (unit == null) {
            return null;
        }
        Vec2 rot = unit.worldRot();
        return new BeamSource.Beam(unit.worldPivotPosition(), VectorUtil.rotToVec(rot.x, rot.y).normalize());
    }

    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof MissileEntity) {
            GUIDING.remove(event.getEntity().getUUID());
        }
    }

    /** Radar seeker on a vehicle => the crew's missile warning. */
    @SubscribeEvent
    public static void onLock(SeekerLockEvent event) {
        PacketDistributor.sendToPlayersTrackingEntity(event.target(), new ServerVehicleWarn(event.missile().getId(),
                event.target().getId(), WarnType.MISSILE_LAUNCH, "MSL"));
    }
}
