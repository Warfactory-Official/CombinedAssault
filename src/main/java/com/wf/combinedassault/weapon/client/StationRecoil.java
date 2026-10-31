package com.wf.combinedassault.weapon.client;

import com.wf.combinedassault.vehicle.event.VehicleFireEvent;
import com.wf.combinedassault.weapon.ordnance.Munitions;
import com.wf.combinedassault.weapon.part.data.WeaponUnitData;
import com.wf.combinedassault.weapon.data.BaseVehicleWeaponData;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.AbstractVehicleWeapon;
import com.wf.wflib.recoil.api.RecoilProfile;
import com.wf.wflib.recoil.api.RecoilProfile.CurveKey;
import com.wf.wflib.recoil.api.RecoilRing;
import com.wf.wflib.round.api.KineticPreset;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.SplittableRandom;
import java.util.random.RandomGenerator;

/**
 * Local operator's station recoil: {@code handling} manual => aim kick + jolt, remote => jolt only. Divergence from MA:
 * per-shot curves fixed ({@link #PROFILE}), scaled by round momentum, no ADS / posture factors.
 */
public final class StationRecoil {
    /** N s per unit of weapon {@code recoil} when the round is no kinetic preset (missiles, bombs). */
    static final float NON_KINETIC_NS = 100;
    /** {@code backblast} => recoilless: momentum vented rearwards. */
    static final float RECOILLESS = 0.1F;
    static final float MANUAL_OMEGA = 30, MANUAL_ZETA = 0.45F, ROLL_OMEGA = 22, ROLL_ZETA = 0.35F;
    static final float REMOTE_OMEGA = 28, REMOTE_ZETA = 0.35F;
    static final float MAX_DEG = 1, MAX_ROLL_DEG = 1;
    static final float RISE = 0.05F, SETTLE = 0.18F;
    /** Manual peak climb {@code K sqrt(p)} deg, p = N s per shot event. */
    static final float MANUAL_K = 0.025F, MANUAL_MIN = 0.04F, MANUAL_MAX = 0.6F;
    /** Remote jolt {@code K log2(1 + p / P0)} deg. */
    static final float JOLT_K = 0.05F, JOLT_P0 = 10F, JOLT_MIN = 0.02F, JOLT_MAX = 0.4F;
    static final RecoilProfile PROFILE = new RecoilProfile(
            List.of(new CurveKey(0, 0, 0), new CurveKey(RISE, 0.7F, 1), new CurveKey(SETTLE, 0.15F, 0.25F)),
            List.of(new CurveKey(0, 0, 0), new CurveKey(RISE, -0.35F, 0.35F), new CurveKey(SETTLE, -0.14F, 0.14F)),
            0.15F, 5, 1, 1, RecoilProfile.Flip.NONE);

    private static final RecoilRing RING = new RecoilRing();
    private static final SplittableRandom RANDOM = new SplittableRandom();
    private static final float[] DELTA = new float[2];
    @Nullable
    private static WeaponUnit station;
    private static long clock;

    private StationRecoil() {
    }

    private static double now(float partialTick) {
        return (clock + partialTick) / 20.0;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (!Minecraft.getInstance().isPaused()) {
            clock++;
        }
        if (station != null && !LocalVehiclePlayer.instance().onVehicle()) {
            RING.reset();
            station = null;
        }
    }

    static float manualPeak(float impulse) {
        return impulse <= 0 ? 0 : Math.clamp(MANUAL_K * (float) Math.sqrt(impulse), MANUAL_MIN, MANUAL_MAX);
    }

    static float jolt(float impulse) {
        return impulse <= 0 ? 0 : Math.clamp(JOLT_K * (float) (Math.log1p(impulse / JOLT_P0) / Math.log(2)), JOLT_MIN,
                JOLT_MAX);
    }

    /** {@code impulse} N s of one shot event. Draw order {@code uPitch, uYaw, side, spread} (+ remote yaw weight). */
    static void shot(RecoilRing ring, double now, float impulse, boolean manual, RandomGenerator random) {
        float up = random.nextFloat(), uy = random.nextFloat();
        float side = random.nextBoolean() ? 1 : -1, spread = 0.6F + 0.8F * random.nextFloat();
        if (manual) {
            float p = manualPeak(impulse);
            ring.shot(PROFILE, now, up, uy, p, p, 0.5F * p, 0, 0.4F * p * side * spread);
        } else {
            float j = jolt(impulse);
            ring.shot(PROFILE, now, up, uy, 0, 0, j, 0.4F * j * (2 * random.nextFloat() - 1), 0.6F * j * side * spread);
        }
    }

    /** N s of one fire event; {@code shoot_interval < 50} fires {@code 50 / interval} rounds per event. */
    static float impulse(AbstractVehicleWeapon<?> weapon) {
        BaseVehicleWeaponData data = weapon.getData();
        KineticPreset k = Munitions.kinetic(weapon.solutionRound());
        float p = k != null ? k.mass() * k.muzzleSpeed() * 20 : NON_KINETIC_NS * data.getRecoil();
        if (data.getBackblast() != null) {
            p *= RECOILLESS;
        }
        return weapon.getShootInterval() < 50 ? p * 50F / weapon.getShootInterval() : p;
    }

    @SubscribeEvent
    public static void onFire(VehicleFireEvent.Post event) {
        if (!event.isClientSide()) {
            return;
        }
        Player player = Minecraft.getInstance().player;
        WeaponUnit unit = event.getWeapon().getWeaponUnit();
        PartUnit<?> own = player == null ? null : event.getVehicle().getOwnOperatorUnit(player);
        if (!(own instanceof WeaponUnit ownUnit) || own != unit && own != unit.getRootParentWeaponUnit()) {
            return;
        }
        if (own != station) {
            RING.reset();
            station = ownUnit;
        }
        shot(RING, now(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false)),
                impulse(event.getWeapon()), ownUnit.getData().getHandling() == WeaponUnitData.Handling.MANUAL, RANDOM);
    }

    /** Before the view and aim are derived from the look ({@code LocalVehiclePlayer#tickAim}). */
    public static void tick(LocalVehiclePlayer local, PartUnit<?> own) {
        if (own != station) {
            RING.reset();
            station = null;
            return;
        }
        RING.step(PROFILE, now(0), DELTA);
        float yaw = DELTA[0], pitch = DELTA[1];
        if (yaw == 0 && pitch == 0) {
            return;
        }
        if (local.viewType == LocalVehiclePlayer.ViewType.SCOPE) {
            WeaponUnit unit = station;
            unit.setXAimRot(Mth.clamp(unit.getXAimRot() + pitch, unit.xRotMin - unit.xSelfRot,
                    unit.xRotMax - unit.xSelfRot));
            unit.setYAimRot(Mth.wrapDegrees(unit.getYAimRot() + yaw));
            unit.updateWorldAimVec();
        } else if (local.aircraftCockpit()) {
            local.playerLocalXRot += pitch;
            local.playerLocalYRot += yaw;
        } else {
            Player player = local.getPlayer();
            float x = Mth.clamp(player.getXRot() + pitch, -90, 90);
            player.xRotO += x - player.getXRot();
            player.yRotO += yaw;
            player.setXRot(x);
            player.setYRot(player.getYRot() + yaw);
        }
    }

    /** Visual jolt {@code [yaw, pitch, roll]} deg at {@code partialTick}, camera-angle signs; zero off a station. */
    public static float[] jolt(float partialTick, float[] out) {
        out[0] = out[1] = out[2] = 0;
        if (station == null) {
            return out;
        }
        double now = now(partialTick);
        boolean manual = station.getData().getHandling() == WeaponUnitData.Handling.MANUAL;
        float omega = manual ? MANUAL_OMEGA : REMOTE_OMEGA, zeta = manual ? MANUAL_ZETA : REMOTE_ZETA;
        out[0] = Math.clamp(RING.spring(now, RecoilRing.YAW, omega, zeta), -MAX_DEG, MAX_DEG);
        out[1] = -Math.clamp(RING.spring(now, RecoilRing.PITCH, omega, zeta), -MAX_DEG, MAX_DEG);
        out[2] = Math.clamp(manual ? RING.spring(now, RecoilRing.ROLL, ROLL_OMEGA, ROLL_ZETA)
                : RING.spring(now, RecoilRing.ROLL, omega, zeta), -MAX_ROLL_DEG, MAX_ROLL_DEG);
        return out;
    }
}
