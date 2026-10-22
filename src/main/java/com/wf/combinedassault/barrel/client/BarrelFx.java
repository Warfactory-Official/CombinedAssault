package com.wf.combinedassault.barrel.client;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.barrel.GunBarrel;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.particle.SmokeCloudOption;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.pojo.AimContext;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;
import com.wf.wflib.barrel.BarrelState;
import com.wf.wflib.barrel.client.BarrelGlow;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.util.ArrayList;
import java.util.List;

/** Hot barrels: smoke wisps at the muzzle (rate = haze), glow light along the barrel; dry click when blocked. */
@EventBusSubscriber(modid = CombinedAssault.MOD_ID, value = Dist.CLIENT)
public final class BarrelFx {
    /** Camera to vehicle, blocks. */
    static final double RANGE = 96;
    /** Wisps per tick at haze 1. */
    static final float WISPS = 0.25f;
    static final long DRY_MS = 300;

    private record Hot(AbstractVehicleWeapon<?> weapon, float glow) {
    }

    private static final List<Hot> HOT = new ArrayList<>();
    private static long dryAt;

    private BarrelFx() {
    }

    public static void dryClick(AbstractVehicleWeapon<?> weapon) {
        long now = System.currentTimeMillis();
        if (now - dryAt < DRY_MS) {
            return;
        }
        dryAt = now;
        Vec3 at = weapon.getWeaponUnit().worldPivotPosition();
        weapon.getVehicle().level().playLocalSound(at.x, at.y, at.z, SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS,
                0.6f, 1.8f, false);
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        HOT.clear();
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused()) {
            return;
        }
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof AbstractVehicle vehicle) || vehicle.position().distanceToSqr(eye) > RANGE * RANGE) {
                continue;
            }
            for (PartUnit<?> part : vehicle.getPartUnits()) {
                if (!(part instanceof WeaponUnit unit) || unit.isDetached()) {
                    continue;
                }
                for (AbstractVehicleWeapon<?> weapon : unit.indexedWeapons) {
                    GunBarrel barrel = weapon.barrel();
                    if (barrel == null || !unit.carries(weapon)) {
                        continue;
                    }
                    BarrelState state = barrel.state();
                    float haze = state.haze();
                    if (haze > 0 && level.random.nextFloat() < haze * WISPS) {
                        wisp(level, weapon, haze);
                    }
                    if (state.glow() > 0) {
                        HOT.add(new Hot(weapon, state.glow()));
                    }
                }
            }
        }
    }

    private static void wisp(ClientLevel level, AbstractVehicleWeapon<?> weapon, float haze) {
        AimContext aim = weapon.getWeaponUnit().aimContext();
        Vec3 dir = VectorUtil.rotToVec(aim.direction.x, aim.direction.y).normalize();
        float length = weapon.getWeaponUnit().barrelLength(weapon.getWeaponUnit().getCurrentBolt());
        Vec3 at = aim.from.subtract(dir.scale(level.random.nextFloat() * length * 0.4));
        float grey = 0.55f + 0.2f * level.random.nextFloat();
        level.addParticle(new SmokeCloudOption(grey, grey, grey, 0.05f + 0.07f * haze, 0f, 30, 0.04f + 0.03f * haze,
                        0.12f + 0.1f * haze, -0.02f), true, at.x, at.y, at.z,
                (level.random.nextDouble() - 0.5) * 0.01, 0.02 + 0.02 * haze, (level.random.nextDouble() - 0.5) * 0.01);
    }

    @SubscribeEvent
    static void onFrame(RenderFrameEvent.Pre event) {
        if (HOT.isEmpty()) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        for (Hot hot : HOT) {
            AbstractVehicleWeapon<?> weapon = hot.weapon;
            if (weapon.getVehicle().isRemoved()) {
                continue;
            }
            AimContext aim = weapon.getWeaponUnit().aimContext();
            Vec3 dir = VectorUtil.rotToVec(aim.direction.x, aim.direction.y).normalize();
            Vec3 drift = weapon.getVehicle().position(partialTick).subtract(weapon.getVehicle().position());
            Vec3 at = aim.from.add(drift).subtract(dir.scale(weapon.getWeaponUnit().barrelLength(weapon.getWeaponUnit().getCurrentBolt()) / 2));
            BarrelGlow.submit(at.x, at.y, at.z, hot.glow);
        }
    }
}
