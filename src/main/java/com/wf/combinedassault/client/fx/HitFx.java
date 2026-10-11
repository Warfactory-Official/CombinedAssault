package com.wf.combinedassault.client.fx;

import com.wf.combinedassault.ballistics.HitOutcome;
import com.wf.combinedassault.ballistics.component.ComponentKind;
import com.wf.combinedassault.ballistics.component.ComponentState;
import com.wf.combinedassault.ballistics.component.ComponentDamage;
import com.wf.combinedassault.ballistics.component.HullComponents;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.client.particle.BulletHoleParticle;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.network.message.ServerHitEffect;
import com.wf.combinedassault.particle.BulletHoleOption;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.combinedassault.vehicle.hit.HitModel;
import com.wf.combinedassault.vehicle.hit.VehicleHits;
import com.wf.gemrender.particle.LevelContactProbe;
import com.wf.gemrender.particle.ParticleCollision;
import com.wf.gemrender.particle.ParticleEmitter;
import com.wf.gemrender.particle.ParticleLook;
import com.wf.wflib.api.ThreatKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;

/**
 * Vehicle hit surface effects (pack doc §6.6 "Hit effects"): per strike by outcome x material x round kind, scaled by
 * caliber; per tick, smoke / fire / cook-off from damaged systems. Cosmetic; client thread.
 */
public final class HitFx {
    /** Strikes per client tick with particles; excess => decal only (automatic fire). */
    static final int BUDGET = 16;
    static final double RANGE_SQ = 128 * 128;
    /** Damaged-system emitters: every this many ticks, within this range. */
    static final int SYSTEM_PERIOD = 3;
    static final double SYSTEM_RANGE = 160;
    /** Caliber of a scale-1 strike, mm; scale clamped to [SCALE_MIN, SCALE_MAX]. */
    static final float REFERENCE_MM = 30;
    static final float SCALE_MIN = 0.35F;
    static final float SCALE_MAX = 4;
    static final float TAU = Mth.TWO_PI;
    /** {@link ComponentState} band floors: below DAMAGED => smoke; below BROKEN => fire / black. */
    static final float DAMAGED = ComponentState.WORN.minRatio;
    static final float BROKEN = ComponentState.DAMAGED.minRatio;

    private static final int PAINT = 0x56604A;
    private static final int STEEL = 0x8A8A86;
    private static final int RUBBER = 0x1E1E1E;
    private static final int GLASS = 0xDDEEFF;
    private static final int CLOTH = 0x9C8C68;
    private static final int GREY_SMOKE = 0x9A9A9A;
    private static final int BLACK_SMOKE = 0x262626;

    private static final RandomSource RANDOM = RandomSource.create();
    private static int spent;
    private static long ticks;
    /** Harness: strikes drawn, persistent emissions, last strike, ns spent (strikes; system ticks). */
    static int strikes;
    static long strikeNanos;
    static long systemNanos;
    static int systemTicks;
    static ServerHitEffect last;
    static int emissions;

    private HitFx() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(HitFxWorld::onTick);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Pre e) -> {
            spent = 0;
            if (++ticks % SYSTEM_PERIOD == 0) {
                long t0 = System.nanoTime();
                systems();
                systemNanos += System.nanoTime() - t0;
                systemTicks++;
            }
        });
    }

    public static void impact(ServerHitEffect hit) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || !(level.getEntity(hit.vehicleId()) instanceof AbstractVehicle vehicle)) {
            return;
        }
        hole(level, vehicle, hit);
        if (spent >= BUDGET || mc.gameRenderer.getMainCamera().getPosition().distanceToSqr(hit.at()) > RANGE_SQ
                || hit.kind() == ThreatKind.HE) {
            return;
        }
        long t0 = System.nanoTime();
        spent++;
        strikes++;
        last = hit;
        draw(level, hit);
        strikeNanos += System.nanoTime() - t0;
    }

    private static void draw(ClientLevel level, ServerHitEffect hit) {
        Vec3 n = hit.normal();
        Vec3 d = hit.direction();
        Vec3 at = hit.at().add(n.scale(0.03));
        float s = Mth.clamp(hit.caliber() / REFERENCE_MM, SCALE_MIN, SCALE_MAX);
        boolean small = hit.kind() == ThreatKind.SMALL_ARMS;
        ParticleCollision.Probe probe = LevelContactProbe.of(level);
        int lit = ParticleLook.lit(level, at.x + n.x * 0.5, at.y + n.y * 0.5, at.z + n.z * 0.5, ParticleLook.WHITE);
        Vec3 reflect = d.subtract(n.scale(2 * d.dot(n)));
        Vec3 spray = n.add(reflect.scale(0.4)).normalize();
        switch (hit.material()) {
            case RUBBER -> {
                burst(HitFxKind.CHIP, probe, at, spray, count(8, s), 2, 5, 0.9F, 0.6F, look(level, at, RUBBER));
                puffs(HitFxKind.DUST, at, spray, count(6, s), 1.4F, look(level, at, 0xA8A49A));
                return;
            }
            case GLASS -> {
                burst(HitFxKind.CHIP, probe, at, spray, count(12, s), 2, 6, 0.8F, 0.6F, look(level, at, GLASS));
                flash(at, 0.25F * Mth.sqrt(s));
                return;
            }
            case SOFT -> {
                puffs(HitFxKind.DUST, at, spray, count(6, s), 1.5F, look(level, at, CLOTH));
                burst(HitFxKind.CHIP, probe, at, spray, count(4, s), 1, 3, 0.7F, 0.4F, look(level, at, CLOTH));
                return;
            }
            default -> {
            }
        }
        if (small) {
            burst(HitFxKind.SPARK, probe, at, spray, count(8, s), 5, 10, 0.3F, 0.2F, ParticleLook.NONE);
            return;
        }
        if (hit.kind().isShapedCharge()) {
            heat(probe, at, n, d, s);
            return;
        }
        HitOutcome outcome = hit.outcome() == null ? HitOutcome.ABSORBED : hit.outcome();
        switch (outcome) {
            case RICOCHET -> {
                Vec3 off = reflect.add(n.scale(0.3)).normalize();
                burst(HitFxKind.SPARK, probe, at, off, count(14, s), 9, 18, 0.35F, 0.35F, ParticleLook.NONE);
                flash(at, 0.3F * Mth.sqrt(s));
            }
            case ABSORBED -> {
                burst(HitFxKind.SPARK, probe, at, spray, count(16, s), 5, 11, 0.4F, 0.35F, ParticleLook.NONE);
                burst(HitFxKind.EMBER, probe, at, spray, count(3, s), 1.5F, 3, 1.2F, 0.6F, ParticleLook.NONE);
                burst(HitFxKind.CHIP, probe, at, spray, count(5, s), 2, 4.5F, 0.9F, 0.5F, look(level, at, PAINT));
                puffs(HitFxKind.SMOKE, at, n, count(2, s), 0.8F, look(level, at, GREY_SMOKE));
                flash(at, 0.45F * Mth.sqrt(s));
            }
            case PARTIAL, PENETRATION, OVERMATCH -> {
                burst(HitFxKind.SPARK, probe, at, spray, count(10, s), 5, 10, 0.35F, 0.3F, ParticleLook.NONE);
                // Spall: inside the plate, along the shot.
                Vec3 inside = hit.at().add(d.scale(0.3));
                int spall = count(outcome == HitOutcome.PARTIAL ? 14 : 22, s);
                burst(HitFxKind.SPARK, probe, inside, d, spall, 8, 18, 0.3F, 0.3F, ParticleLook.NONE);
                if (outcome == HitOutcome.OVERMATCH) {
                    burst(HitFxKind.CHIP, probe, at, spray, count(10, s), 3, 7, 1.2F, 0.6F, look(level, at, STEEL));
                }
                puffs(HitFxKind.SMOKE, at, n, count(4, s), 0.6F, look(level, at, BLACK_SMOKE));
                flash(at, 0.6F * Mth.sqrt(s));
            }
        }
    }

    /** Jet flash, molten droplets round the normal, black smoke ring in the plate. */
    private static void heat(ParticleCollision.Probe probe, Vec3 at, Vec3 n, Vec3 d, float s) {
        flash(at, 1.0F * Mth.sqrt(s));
        burst(HitFxKind.EMBER, probe, at, n, count(30, s), 2, 7, 1.0F, 1.0F, ParticleLook.NONE);
        burst(HitFxKind.SPARK, probe, at.add(d.scale(0.3)), d, count(20, s), 10, 20, 0.3F, 0.3F, ParticleLook.NONE);
        Vec3 u = n.cross(Math.abs(n.y) < 0.9 ? new Vec3(0, 1, 0) : new Vec3(1, 0, 0)).normalize();
        Vec3 v = n.cross(u);
        ParticleEmitter em = HitFxWorld.emitter(HitFxKind.SMOKE, at.x, at.y, at.z);
        if (em == null) {
            return;
        }
        int ring = count(10, s);
        int black = look(Minecraft.getInstance().level, at, BLACK_SMOKE);
        for (int i = 0; i < ring; i++) {
            double a = TAU * i / ring;
            Vec3 out = u.scale(Math.cos(a)).add(v.scale(Math.sin(a))).add(n.scale(0.3));
            em.spawn(at.x, at.y, at.z, out.x * 2.5, out.y * 2.5, out.z * 2.5, 1.8F + RANDOM.nextFloat(),
                    1.2F + 0.6F * RANDOM.nextFloat(), RANDOM.nextFloat() * TAU, 1, black);
        }
    }

    /** Hole decal pinned to the struck bone (all viewers; was indicator-only). */
    private static void hole(ClientLevel level, AbstractVehicle vehicle, ServerHitEffect hit) {
        if (hit.material() != com.wf.combinedassault.vehicle.hit.SurfaceMaterial.STEEL
                || hit.outcome() == HitOutcome.RICOCHET) {
            return;
        }
        Vec3 start = hit.at().subtract(hit.direction().scale(0.5));
        Vec3 end = hit.at().add(hit.direction().scale(2.5));
        VectorUtil.HitBone bone = VectorUtil.hitBone(vehicle, start, end, new Vec3(0, 1, 0));
        if (bone == null) {
            return;
        }
        BulletHoleOption option = new BulletHoleOption(Direction.UP, BlockPos.containing(hit.at()), 1.0F, 0.0F, 0.0F,
                hit.caliber());
        option.withBakedBone(vehicle.getId(), bone.attachmentBoneIndex(), bone.offset(), bone.rotation());
        Particle particle = Minecraft.getInstance().particleEngine.createParticle(option, hit.at().x, hit.at().y,
                hit.at().z, 0, 0, 0);
        if (particle instanceof BulletHoleParticle hole) {
            vehicle.getBulletHoleParticles().add(hole);
        }
    }

    /** Damaged systems: smoke / fire / cook-off at their hit nodes (none: the hull top). */
    private static void systems() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        for (AbstractVehicle vehicle : level.getEntitiesOfClass(AbstractVehicle.class,
                AABB.ofSize(cam, SYSTEM_RANGE * 2, SYSTEM_RANGE * 2, SYSTEM_RANGE * 2))) {
            if (vehicle.isDestroyed()) {
                continue;
            }
            // Per member, health first: VehicleSystems.cells() allocates + sorts per call.
            HullComponents pools = vehicle.hullComponents();
            for (int i = 0; i < pools.size(); i++) {
                float max = pools.maxHealth(i);
                if (max > 0 && pools.health(i) < DAMAGED * max) {
                    emit(level, vehicle, pools.kind(i), pools.health(i) < BROKEN * max);
                }
            }
            for (PartUnit<?> part : vehicle.getPartUnits()) {
                float max = part.getMaxHealth();
                if (max > 0 && part.getHealth() < DAMAGED * max) {
                    emit(level, vehicle, ComponentDamage.kind(part), part.getHealth() < BROKEN * max);
                }
            }
        }
    }

    private static void emit(ClientLevel level, AbstractVehicle vehicle, ComponentKind kind, boolean broken) {
        switch (kind) {
            case ENGINE -> {
                Vec3 at = site(vehicle, ComponentKind.ENGINE);
                smoke(level, at, broken ? BLACK_SMOKE : GREY_SMOKE, broken ? 2 : 1);
                if (broken) {
                    fire(at, 1);
                }
            }
            case FUEL -> {
                if (broken) {
                    Vec3 at = site(vehicle, ComponentKind.FUEL);
                    fire(at, 2);
                    smoke(level, at, BLACK_SMOKE, 1);
                }
            }
            case AMMO -> {
                if (broken && RANDOM.nextInt(4) == 0) {
                    Vec3 at = site(vehicle, ComponentKind.AMMO);
                    burst(HitFxKind.EMBER, LevelContactProbe.of(level), at, new Vec3(0, 1, 0), 6 + RANDOM.nextInt(10),
                            3, 9, 0.8F, 0.8F, ParticleLook.NONE);
                    flash(at, 0.5F);
                    emissions++;
                }
            }
            case ROTOR, TAIL_ROTOR, WING -> smoke(level, site(vehicle, kind), broken ? BLACK_SMOKE : GREY_SMOKE, 1);
            default -> {
            }
        }
    }

    /** World centre of a random live hit node of {@code kind}; none => top centre of the hull box. */
    private static Vec3 site(AbstractVehicle vehicle, ComponentKind kind) {
        VehicleHits hits = vehicle.hits();
        if (hits != null) {
            List<HitModel.Node> nodes = hits.model().nodes();
            int found = 0;
            int pick = -1;
            for (int i = 0; i < nodes.size(); i++) {
                if (nodes.get(i).kind() == kind && hits.live(i) && RANDOM.nextInt(++found) == 0) {
                    pick = i;
                }
            }
            if (pick >= 0) {
                return hits.centre(pick);
            }
        }
        AABB box = vehicle.getBoundingBox();
        return new Vec3(box.getCenter().x, box.maxY, box.getCenter().z);
    }

    private static void smoke(ClientLevel level, Vec3 at, int rgb, int n) {
        ParticleEmitter em = HitFxWorld.emitter(HitFxKind.SMOKE, at.x, at.y, at.z);
        if (em == null) {
            return;
        }
        int look = look(level, at, rgb);
        for (int i = 0; i < n; i++) {
            em.spawn(at.x + RANDOM.nextGaussian() * 0.2, at.y, at.z + RANDOM.nextGaussian() * 0.2,
                    RANDOM.nextGaussian() * 0.3, 1.2 + RANDOM.nextFloat(), RANDOM.nextGaussian() * 0.3,
                    2.5F + 1.5F * RANDOM.nextFloat(), 1.4F + 0.8F * RANDOM.nextFloat(), RANDOM.nextFloat() * TAU, 1, look);
            emissions++;
        }
    }

    private static void fire(Vec3 at, int n) {
        ParticleEmitter em = HitFxWorld.emitter(HitFxKind.FIRE, at.x, at.y, at.z);
        if (em == null) {
            return;
        }
        for (int i = 0; i < n; i++) {
            em.spawn(at.x + RANDOM.nextGaussian() * 0.25, at.y, at.z + RANDOM.nextGaussian() * 0.25,
                    RANDOM.nextGaussian() * 0.2, 0.8 + RANDOM.nextFloat() * 0.6, RANDOM.nextGaussian() * 0.2,
                    0.5F + 0.3F * RANDOM.nextFloat(), 1.0F + 0.5F * RANDOM.nextFloat(), RANDOM.nextFloat() * TAU, 1,
                    ParticleLook.NONE);
            emissions++;
        }
    }

    private static void flash(Vec3 at, float size) {
        ParticleEmitter em = HitFxWorld.emitter(HitFxKind.FLASH, at.x, at.y, at.z);
        if (em != null) {
            em.spawn(at.x, at.y, at.z, 0, 0, 0, 0.12F, size, RANDOM.nextFloat() * TAU, 1, ParticleLook.NONE);
        }
    }

    /** {@code n} along {@code out} (Gaussian spread 0.35), speed in [min, max] b/s, life in [life, life + random]. */
    private static void burst(HitFxKind kind, ParticleCollision.Probe probe, Vec3 at, Vec3 out, int n, float min,
                              float max, float life, float lifeRandom, int look) {
        ParticleEmitter em = HitFxWorld.emitter(kind, at.x, at.y, at.z);
        if (em == null) {
            return;
        }
        for (int i = 0; i < n; i++) {
            double v = min + (max - min) * RANDOM.nextFloat();
            em.spawn(probe, at.x, at.y, at.z, (out.x + RANDOM.nextGaussian() * 0.35) * v,
                    (out.y + RANDOM.nextGaussian() * 0.35) * v + 1, (out.z + RANDOM.nextGaussian() * 0.35) * v,
                    life + lifeRandom * RANDOM.nextFloat(), 0.7F + 0.6F * RANDOM.nextFloat(), RANDOM.nextFloat() * TAU,
                    1, 0, look);
        }
    }

    private static void puffs(HitFxKind kind, Vec3 at, Vec3 out, int n, float life, int look) {
        ParticleEmitter em = HitFxWorld.emitter(kind, at.x, at.y, at.z);
        if (em == null) {
            return;
        }
        for (int i = 0; i < n; i++) {
            double v = 0.8 + 1.6 * RANDOM.nextFloat();
            em.spawn(at.x, at.y, at.z, (out.x + RANDOM.nextGaussian() * 0.3) * v, (out.y + RANDOM.nextGaussian() * 0.3) * v,
                    (out.z + RANDOM.nextGaussian() * 0.3) * v, life + 0.8F * RANDOM.nextFloat(),
                    0.7F + 0.6F * RANDOM.nextFloat(), RANDOM.nextFloat() * TAU, 1, look);
        }
    }

    private static int count(int base, float scale) {
        return Math.max(1, Math.round(base * scale));
    }

    private static int look(ClientLevel level, Vec3 at, int rgb) {
        return ParticleLook.lit(level, at.x, at.y + 0.3, at.z, rgb);
    }
}
