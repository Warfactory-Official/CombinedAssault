package com.wf.combinedassault.ballistics.client;

import com.wf.wflib.audio.api.AudioCategory;
import com.wf.wflib.audio.api.Blasts;
import com.wf.wflib.audio.api.client.Sounds;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import com.wf.combinedassault.ballistics.message.ServerVehicleExplosion;
import com.wf.combinedassault.hud.FirstPersonHandler;
import com.wf.combinedassault.render.particle.SmokeCloudOption;

public final class VehicleExplosionFx {

    private VehicleExplosionFx() {
    }

    public static void play(ServerVehicleExplosion serverVehicleExplosion) {
        if (serverVehicleExplosion == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null) {
            return;
        }
        Player player = minecraft.player;
        double x = serverVehicleExplosion.x();
        double y = serverVehicleExplosion.y();
        double z = serverVehicleExplosion.z();
        float radius = serverVehicleExplosion.radius();

        if (radius <= 2) {
            smallExplosionEffect(level, player, x, y, z);
        } else if (radius <= 8) {
            mediumExplosionEffect(level, player, x, y, z);
        } else if (radius <= 32) {
            largeExplosionEffect(level, player, x, y, z, radius);
        } else {
            nuclearExplosionEffect(level, player, x, y, z, radius);
        }

        FirstPersonHandler.addExplosionShake(new Vec3(x, y, z), radius);
    }

    /** Same variant + pitch for every listener of one blast. */
    private static long seed(double x, double y, double z) {
        return Double.doubleToLongBits(x) * 31 + Double.doubleToLongBits(y) * 17 + Double.doubleToLongBits(z);
    }

    private static void smallExplosionEffect(Level level, Player player, double x, double y, double z) {
        Sounds.at(Blasts.small(), x, y, z, AudioCategory.EXPLOSIONS, seed(x, y, z));
        level.addParticle(ParticleTypes.FLASH, true, x, y, z, 0, 0, 0);
        addParticles(level, ParticleTypes.EXPLOSION, x, y, z, 2, 0, 0.02, 0, 0);
        addParticlesOnSolid(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y, z, 2, 0.1, 0.1, 0.1, 0.02);
        addParticles(level, ParticleTypes.LARGE_SMOKE, x, y, z, 1, 0.2, 0.2, 0.2, 0.02);
    }

    private static void mediumExplosionEffect(Level level, Player player, double x, double y, double z) {
        Sounds.at(Blasts.large(150), x, y, z, AudioCategory.EXPLOSIONS, seed(x, y, z));
        addParticles(level, ParticleTypes.FLASH, x, y + 0.5, z, 8, 2, 2, 2, 0);
        addParticles(level, ParticleTypes.LARGE_SMOKE, x, y + 1, z, 10, 16, 1, 16, 0.01);
        addParticlesOnSolid(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y + 0.25, z, 40, 16, 0.05, 16, 0);
        for (int i = 0; i < 40; i++) {
            Vec3 v = randomHemisphereDir(level).scale(0.2);
            level.addParticle(new SmokeCloudOption(1f, 0.35f + level.random.nextFloat() * 0.15f, 0f, 6, 2.0f, -0.01f),
                    true, x, y + 0.5, z, v.x, v.y, v.z);
        }
        if (level.getBlockState(BlockPos.containing(x, y, z)).is(Blocks.WATER)) {
            addParticles(level, ParticleTypes.CLOUD, x, y + 3, z, 20, 1, 3, 1, 0.01);
            addParticles(level, ParticleTypes.FALLING_WATER, x, y + 3, z, 50, 1.5, 4, 1.5, 1);
            addParticles(level, ParticleTypes.BUBBLE_COLUMN_UP, x, y, z, 60, 3, 0.5, 3, 0.1);
        }
    }

    private static void largeExplosionEffect(Level level, Player player, double x, double y, double z, double radius) {
        Sounds.at(Blasts.large((float) Math.clamp(radius * 10, 200, 350)), x, y, z, AudioCategory.EXPLOSIONS,
                seed(x, y, z));
        addParticles(level, ParticleTypes.FLASH, x, y + 3, z, 60, radius / 2, 5, radius / 2, 0);
        addParticlesOnSolid(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y + 0.25, z, 120, radius, 0.05, radius, 0);
        for (int i = 0; i < 200; i++) {
            Vec3 v = randomHemisphereDir(level).scale(1.0 + level.random.nextDouble() * 0.5);
            float heat = level.random.nextFloat();
            level.addParticle(new SmokeCloudOption(1f, 0.25f + heat * 0.25f, 0f, 100, 4.5f + heat * 2, -0.003f),
                    true, x, y + 1, z, v.x * 2, v.y, v.z * 2);
        }
        spawnExplosionRing(level, x, y, z, 4, 200, 4.0, 0.5, 0.4, 1.0f, 0.12f, 16, 3.0f, 0.02f, 0f);
        if (level.getBlockState(BlockPos.containing(x, y, z)).is(Blocks.WATER)) {
            addParticles(level, ParticleTypes.CLOUD, x, y + 3, z, 200, 4, 8, 4, 0.01);
            addParticles(level, ParticleTypes.FALLING_WATER, x, y + 3, z, 500, 5, 10, 5, 1);
            addParticles(level, ParticleTypes.BUBBLE_COLUMN_UP, x, y, z, 350, 8, 1, 8, 0.1);
        }
    }

    private static void nuclearExplosionEffect(Level level, Player player, double x, double y, double z, double radius) {
        double cappedRadius = Math.min(radius, 160.0);
        double stemHeight = Math.min(28.0 + cappedRadius * 0.35, 72.0);
        double capRadius = Math.min(12.0 + cappedRadius * 0.45, 60.0);
        int flashCount = Math.min(180, 80 + (int) (cappedRadius * 1.2));
        int stemCount = Math.min(420, 180 + (int) (cappedRadius * 1.4));
        int capCount = Math.min(720, 320 + (int) (cappedRadius * 2.3));
        int falloutCount = Math.min(260, 120 + (int) cappedRadius);

        Sounds.at(Blasts.large(800), x, y, z, AudioCategory.EXPLOSIONS, seed(x, y, z));
        addParticles(level, ParticleTypes.FLASH, x, y + 4, z, flashCount, cappedRadius * 0.45, 8, cappedRadius * 0.45, 0);
        addParticlesOnSolid(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y + 0.5, z, 180, cappedRadius * 1.2, 0.2, cappedRadius * 1.2, 0.01);
        addParticles(level, ParticleTypes.LARGE_SMOKE, x, y + 1.0, z, 90, cappedRadius * 0.9, 1.2, cappedRadius * 0.9, 0.02);

        spawnExplosionRing(level, x, y, z, 1, 260, cappedRadius * 0.12, -0.3, 0, 0.08f, 0.02f, 400, 2.0f, 0, -0.004f);
        spawnMushroomStem(level, x, y - stemHeight * 0.7, z, stemHeight * 1.4f, capRadius * 0.22, stemCount);
        spawnMushroomCap(level, x, y + stemHeight, z, capRadius, capCount);
        spawnMushroomFallout(level, x, y + stemHeight * 0.82, z, capRadius, falloutCount);

        if (level.getBlockState(BlockPos.containing(x, y, z)).is(Blocks.WATER)) {
            addParticles(level, ParticleTypes.CLOUD, x, y + 8, z, 260, 8, 12, 8, 0.02);
            addParticles(level, ParticleTypes.FALLING_WATER, x, y + 5, z, 700, 8, 16, 8, 1);
            addParticles(level, ParticleTypes.BUBBLE_COLUMN_UP, x, y, z, 500, 10, 2, 10, 0.15);
        }
    }

    private static void spawnExplosionRing(Level level, double x, double y, double z, int layers, int segments,
                                           double baseSpeed, double baseYOffset, double yStep,
                                           float baseBrightness, float endBrightness, int baseLife, float baseSize,
                                           float vyBase, float gravity) {
        for (int h = 0; h < layers; h++) {
            double waveSpeed = Math.max(0.1, baseSpeed - h * 0.35);
            double yOff = baseYOffset + h * yStep;
            float brightness = Math.max(endBrightness, baseBrightness - h * 0.08f);
            int life = Math.max(8, baseLife - h * 2);
            float size = Math.max(1.0f, baseSize - h * 0.35f);
            for (int i = 0; i < segments; i++) {
                double angle = 2 * Math.PI * i / segments;
                level.addParticle(new SmokeCloudOption(brightness, brightness, brightness, endBrightness, endBrightness, endBrightness, life, size, gravity),
                        true, x, y + yOff, z, Math.cos(angle) * waveSpeed, vyBase, Math.sin(angle) * waveSpeed);
            }
        }
    }

    private static void spawnMushroomStem(Level level, double x, double y, double z, double stemHeight, double stemRadius, int count) {
        for (int i = 0; i < count; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2;
            double radial = stemRadius * Math.sqrt(level.random.nextDouble());
            double px = x + Math.cos(angle) * radial;
            double pz = z + Math.sin(angle) * radial;
            double py = y + stemHeight * level.random.nextDouble();
            double vx = Math.cos(angle) * (0.03 + level.random.nextDouble() * 0.08) + (level.random.nextDouble() - 0.5) * 0.02;
            double vz = Math.sin(angle) * (0.03 + level.random.nextDouble() * 0.08) + (level.random.nextDouble() - 0.5) * 0.02;
            double vy = 0.18 + (1.0 - (py - y) / stemHeight) * 0.45 + level.random.nextDouble() * 0.08;
            float heat = level.random.nextFloat();
            level.addParticle(new SmokeCloudOption(
                            0.45f + heat * 0.35f,
                            0.24f + heat * 0.12f,
                            0.12f,
                            0.08f + heat * 0.12f,
                            0.08f + heat * 0.10f,
                            0.08f + heat * 0.10f,
                            350 + level.random.nextInt(200),
                            5.0f + heat * 2.8f,
                            -0.004f),
                    true, px, py, pz, vx, vy, vz);
        }
    }

    private static void spawnMushroomCap(Level level, double x, double y, double z, double capRadius, int count) {
        for (int i = 0; i < count; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2;
            double radial = capRadius * Math.sqrt(level.random.nextDouble());
            double rimBias = 0.45 + 0.55 * level.random.nextDouble();
            double px = x + Math.cos(angle) * radial;
            double pz = z + Math.sin(angle) * radial;
            double py = y + (level.random.nextDouble() - 0.5) * capRadius * 0.35 + Math.max(0, 1.0 - radial / capRadius) * capRadius * 0.22;
            double outward = 0.12 + rimBias * 0.22;
            double vx = Math.cos(angle) * outward + (level.random.nextDouble() - 0.5) * 0.04;
            double vz = Math.sin(angle) * outward + (level.random.nextDouble() - 0.5) * 0.04;
            double vy = 0.06 + Math.max(0, 1.0 - radial / capRadius) * 0.18 + (level.random.nextDouble() - 0.5) * 0.04;
            float heat = level.random.nextFloat();
            float startR = 0.45f + heat * 0.45f;
            float startG = 0.24f + heat * 0.18f;
            float startB = 0.12f + heat * 0.04f;
            float end = 0.06f + heat * 0.08f;
            level.addParticle(new SmokeCloudOption(startR, startG, startB, end, end, end, 550 + level.random.nextInt(250), 7.0f + heat * 4.5f, -0.002f),
                    true, px, py, pz, vx, vy, vz);
        }
    }

    private static void spawnMushroomFallout(Level level, double x, double y, double z, double capRadius, int count) {
        for (int i = 0; i < count; i++) {
            double angle = level.random.nextDouble() * Math.PI * 2;
            double radial = capRadius * (0.6 + level.random.nextDouble() * 0.55);
            double px = x + Math.cos(angle) * radial;
            double pz = z + Math.sin(angle) * radial;
            double py = y + (level.random.nextDouble() - 0.5) * capRadius * 0.3;
            double vx = Math.cos(angle) * (0.03 + level.random.nextDouble() * 0.06);
            double vz = Math.sin(angle) * (0.03 + level.random.nextDouble() * 0.06);
            double vy = -0.02 - level.random.nextDouble() * 0.05;
            float shade = 0.14f + level.random.nextFloat() * 0.16f;
            level.addParticle(new SmokeCloudOption(shade, shade, shade, 0.02f, 0.02f, 0.02f, 450 + level.random.nextInt(200), 4.5f + level.random.nextFloat() * 2.5f, 0.002f),
                    true, px, py, pz, vx, vy, vz);
        }
    }

    private static Vec3 randomHemisphereDir(Level level) {
        return new Vec3(
                level.random.nextDouble() - 0.5,
                level.random.nextDouble() * 0.6,
                level.random.nextDouble() - 0.5
        ).normalize();
    }

    private static void addParticles(Level level, net.minecraft.core.particles.ParticleOptions particle,
                                     double x, double y, double z,
                                     int count, double xSpread, double ySpread, double zSpread, double speed) {
        for (int i = 0; i < count; i++) {
            double ox = (level.random.nextDouble() - 0.5) * 2 * xSpread;
            double oy = (level.random.nextDouble() - 0.5) * 2 * ySpread;
            double oz = (level.random.nextDouble() - 0.5) * 2 * zSpread;
            level.addParticle(particle, true, x + ox, y + oy, z + oz, ox * speed, oy * speed + 0.02, oz * speed);
        }
    }

    private static void addParticlesOnSolid(Level level, net.minecraft.core.particles.ParticleOptions particle,
                                            double x, double y, double z,
                                            int count, double xSpread, double ySpread, double zSpread, double speed) {
        for (int i = 0; i < count; i++) {
            double ox = (level.random.nextDouble() - 0.5) * 2 * xSpread;
            double oy = (level.random.nextDouble() - 0.5) * 2 * ySpread;
            double oz = (level.random.nextDouble() - 0.5) * 2 * zSpread;
            double px = x + ox;
            double py = y + oy;
            double pz = z + oz;
            if (level.getBlockState(BlockPos.containing(px, py, pz).below()).isSolid()) {
                level.addParticle(particle, true, px, py, pz, ox * speed, oy * speed + 0.02, oz * speed);
            }
        }
    }
}
