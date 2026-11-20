package com.wf.combinedassault.dynamics.client;

import com.wf.combinedassault.dynamics.message.ServerCrashImpact;
import com.wf.combinedassault.hud.FirstPersonHandler;
import com.wf.combinedassault.render.particle.SmokeCloudOption;
import com.wf.wflib.explosion.api.client.BlastDebris;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Crater ejecta: chunks of the excavated blocks and dust, thrown along the impact's ricochet. */
public final class CrashImpactFx {

    /** Excavated cells per debris chunk (BlastDebris cell = 2x2x2). */
    private static final int CELLS_PER_CHUNK = 4;
    /** Fewer cells = a slide's furrow: scrape sound, no thud or shake. */
    private static final int THUD_CELLS = 12;

    private CrashImpactFx() {
    }

    public static void play(ServerCrashImpact msg) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        // Ricochet: the impact direction mirrored off the ground, lifted.
        Vector3f out = new Vector3f(msg.dx(), Math.abs(msg.dy()) + 0.6f, msg.dz()).normalize();
        Quaternionf vent = new Quaternionf().rotationTo(new Vector3f(0, 1, 0), out);
        float size = (float) Math.cbrt(msg.cells());
        BlastDebris.schedule(level, msg.x(), msg.y(), msg.z(), Math.max(2, msg.cells() / CELLS_PER_CHUNK),
                Mth.clamp(0.25f + 0.08f * size, 0.3f, 0.9f), vent);
        RandomSource random = level.random;
        for (int i = 0, puffs = Mth.clamp(msg.cells() / 2, 6, 80); i < puffs; i++) {
            float spread = 0.35f * size;
            double vx = out.x * 0.25 + random.nextGaussian() * 0.12, vy = out.y * 0.2 + random.nextDouble() * 0.15,
                    vz = out.z * 0.25 + random.nextGaussian() * 0.12;
            float shade = 0.32f + random.nextFloat() * 0.14f;
            level.addParticle(new SmokeCloudOption(shade, shade * 0.9f, shade * 0.75f, 0.8f, 50 + random.nextInt(50),
                            1.5f + random.nextFloat() * size, 0.004f), true,
                    msg.x() + random.nextGaussian() * spread, msg.y() + random.nextDouble() * 0.5,
                    msg.z() + random.nextGaussian() * spread, vx, vy, vz);
        }
        if (msg.cells() < THUD_CELLS) {
            level.playLocalSound(msg.x(), msg.y(), msg.z(), SoundEvents.GRAVEL_BREAK, SoundSource.NEUTRAL, 2, 0.5f, true);
            return;
        }
        level.playLocalSound(msg.x(), msg.y(), msg.z(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.NEUTRAL,
                Mth.clamp(size, 1, 6), 0.45f, true);
        FirstPersonHandler.addExplosionShake(new Vec3(msg.x(), msg.y(), msg.z()), Mth.clamp(size, 1, 8));
    }
}
