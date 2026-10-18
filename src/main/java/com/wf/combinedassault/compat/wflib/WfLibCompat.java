package com.wf.combinedassault.compat.wflib;

import com.wf.combinedassault.all.AllConfigs;
import net.minecraft.world.item.ItemStack;
import com.wf.combinedassault.vehicle.part.RadarUnit;

/** WFLib bridge: sensor net, missile launches, racked-round looks. */
public final class WfLibCompat {

    private WfLibCompat() {
    }

    public static void init() {
        ReconBridge.install();
        Ordnance.register();
    }

    /**
     * Keeps radar on shared sensor net; issues search warnings from net's track picture. Server-side,
     * radar's own scan tick.
     * @return true: net took radar over, caller skips private scan. False: bridge off, or level has no net.
     */
    public static boolean tickRadarSensor(RadarUnit unit) {
        return AllConfigs.Cached.wflibRecon && ReconBridge.tickSensor(unit);
    }

    /**
     * Drop this radar's registration. Safe on an unregistered radar; net idles stale sensors out after
     * ~1 min anyway.
     */
    public static void dropRadarSensor(RadarUnit unit) {
        ReconBridge.dropSensor(unit);
    }

    /** Launch a WFLib preset from a point at a point; false for an unknown preset. */
    public static boolean launchMissile(net.minecraft.server.level.ServerLevel level,
                                        net.minecraft.resources.ResourceLocation presetId,
                                        net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 target) {
        return BallisticsMissiles.launch(level, presetId, from, target);
    }

    /** WFLib interceptor round; homes on a lock, else the nearest hostile. */
    public static boolean isInterceptor(ItemStack round) {
        return round.getItem() instanceof com.wf.wflib.item.MissileItem missile && missile.preset().isInterceptor();
    }

    /** Client: round has a baked WFLib airframe. */
    public static boolean canRenderRound(ItemStack round) {
        return !round.isEmpty() && RackedMissiles.baked(round) != null;
    }

    /** Client: airframe centred on the pose origin, nose +Z; longer than {@code length} => scaled down to it. */
    public static void renderRoundCentered(ItemStack round, float length, com.mojang.blaze3d.vertex.PoseStack poseStack,
                                           net.minecraft.client.renderer.MultiBufferSource bufferSource,
                                           int packedLight) {
        RackedMissiles.renderCentered(round, length, poseStack, bufferSource, packedLight);
    }

}
