package com.wf.combinedassault.render.fx;

import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.gemrender.particle.LevelContactProbe;
import com.wf.gemrender.particle.ParticleEmitter;
import com.wf.gemrender.particle.ParticleLook;
import com.wf.wflib.ammo.api.Ammo;
import com.wf.wflib.ammo.api.AmmoItems;
import com.wf.wflib.ammo.api.AmmoVariant;
import com.wf.wflib.ammo.api.Calibre;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Spent cases thrown from a bolt's {@code eject} socket, weapon {@code casing_delay} after the shot. Client thread. */
public final class CasingFx {
    /** Small-arms cases drawn over real size (1 cm brass vanishes past 2 m). */
    static final float SMALL_SCALE = 1.5F;
    /** {@code cannon} glTF case length, mm: drawn at calibre case length / this. */
    static final float CANNON_MM = 571;
    /** Throw speed jitter, fraction; direction jitter, fraction of speed. */
    static final float SPEED_JITTER = 0.2F;
    static final float SPREAD = 0.12F;
    static final int MAX_PENDING = 64;

    private record Pending(WeaponUnit unit, int bolt, HitFxKind kind, float scale, long due) {
    }

    private static final List<Pending> PENDING = new ArrayList<>();
    private static final RandomSource RANDOM = RandomSource.create();
    private static long ticks;
    /** Harness: cases thrown. */
    static int thrown;

    private CasingFx() {
    }

    /** One case per bolt fired; round without a case => none. */
    public static void shot(WeaponUnit unit, int[] bolts, ItemStack round, float delay) {
        AmmoVariant variant = AmmoItems.variantOf(round);
        if (variant == null) {
            return;
        }
        Calibre calibre = Ammo.calibre(variant.calibre());
        HitFxKind kind = HitFxKind.of(calibre.casing());
        if (kind == null) {
            return;
        }
        float scale = kind == HitFxKind.CASING_CANNON ? calibre.caseLengthMm() / CANNON_MM : SMALL_SCALE;
        long due = ticks + Math.round(delay * 20);
        for (int bolt : bolts) {
            if (PENDING.size() >= MAX_PENDING) {
                PENDING.removeFirst();
            }
            PENDING.add(new Pending(unit, bolt, kind, scale, due));
        }
    }

    static void onTick() {
        ticks++;
        if (HitFxWorld.currentLevel() == null) {
            PENDING.clear();
            return;
        }
        PENDING.removeIf(p -> {
            if (p.due > ticks) {
                return false;
            }
            eject(p);
            return true;
        });
    }

    private static void eject(Pending p) {
        ClientLevel level = HitFxWorld.currentLevel();
        if (p.unit.getVehicle().isRemoved() || p.unit.getVehicle().level() != level) {
            return;
        }
        Vec3[] port = p.unit.worldEject(p.bolt);
        if (port == null) {
            return;
        }
        Vec3 at = port[0];
        ParticleEmitter em = HitFxWorld.emitter(p.kind, at.x, at.y, at.z);
        if (em == null) {
            return;
        }
        thrown++;
        Vec3 throwV = port[1];
        double speed = throwV.length();
        Vec3 v = p.unit.getVehicle().getDeltaMovement().scale(20)
                .add(throwV.scale(1 + SPEED_JITTER * (2 * RANDOM.nextFloat() - 1)))
                .add(RANDOM.nextGaussian() * SPREAD * speed, RANDOM.nextGaussian() * SPREAD * speed,
                        RANDOM.nextGaussian() * SPREAD * speed);
        em.spawn(LevelContactProbe.of(level), at.x, at.y, at.z, v.x, v.y, v.z, HitFxKind.CASING_LIFE, p.scale,
                (RANDOM.nextFloat() - 0.5F) * 0.8F, 1, p.kind.radius * p.scale,
                ParticleLook.lit(level, at.x, at.y, at.z, ParticleLook.WHITE));
    }
}
