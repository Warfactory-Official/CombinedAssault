package com.wf.combinedassault.ballistics.addon;

import com.wf.wflib.round.api.KineticPreset;
import com.wf.wflib.round.api.KineticPresets;
import com.wf.wflib.round.api.RocketEntity;
import com.wf.wflib.round.api.Rounds;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;

import java.util.Locale;

/** {@code /combinedassault ballistics addon ...}: nearest vehicle within 32 blocks. */
public final class AddonCommand {

    private static final double SEARCH = 32;
    /** Muzzle this far out along the (tilted) panel normal. */
    private static final double RANGE = 12;

    private AddonCommand() {
    }

    @Nullable
    private static AbstractVehicle nearest(CommandSourceStack source) {
        Vec3 at = source.getPosition();
        AbstractVehicle best = null;
        double bestDistance = Double.MAX_VALUE;
        for (AbstractVehicle vehicle : source.getLevel().getEntitiesOfClass(AbstractVehicle.class,
                new AABB(at, at).inflate(SEARCH), candidate -> !candidate.addonArmor().isEmpty())) {
            double distance = vehicle.distanceToSqr(at);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = vehicle;
            }
        }
        if (best == null) {
            source.sendFailure(Component.literal("no vehicle with addon_armor within " + (int) SEARCH + " blocks"));
        }
        return best;
    }

    public static int fill(CommandSourceStack source, String fitName) {
        AddonFit fit;
        try {
            fit = AddonFit.valueOf(fitName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            source.sendFailure(Component.literal("fit: era_light | era_heavy | slat"));
            return 0;
        }
        AbstractVehicle vehicle = nearest(source);
        if (vehicle == null || fit == AddonFit.NONE) {
            return 0;
        }
        AddonArmorLayer layer = vehicle.addonArmor();
        int filled = 0;
        for (int i = 0; i < layer.mounts().size(); i++) {
            if (layer.mounts().get(i).info.kind == fit.kind) {
                layer.state(i).install(layer.mounts().get(i), fit);
                filled++;
            }
        }
        layer.changed();
        int count = filled;
        source.sendSuccess(() -> Component.literal("fitted " + count + " mounts with " + fit), false);
        return count;
    }

    public static int clear(CommandSourceStack source) {
        AbstractVehicle vehicle = nearest(source);
        if (vehicle == null) {
            return 0;
        }
        AddonArmorLayer layer = vehicle.addonArmor();
        for (int i = 0; i < layer.mounts().size(); i++) {
            layer.state(i).clear();
        }
        layer.changed();
        source.sendSuccess(() -> Component.literal("cleared"), false);
        return 1;
    }

    public static int stats(CommandSourceStack source) {
        AbstractVehicle vehicle = nearest(source);
        if (vehicle == null) {
            return 0;
        }
        AddonArmorLayer layer = vehicle.addonArmor();
        for (int i = 0; i < layer.mounts().size(); i++) {
            AddonMount mount = layer.mounts().get(i);
            AddonMountState state = layer.state(i);
            String line = switch (state.fit) {
                case NONE -> "empty";
                case ERA_LIGHT, ERA_HEAVY -> state.fit + " " + state.liveCells() + "/" + mount.cells() + " live";
                case SLAT -> "SLAT " + state.intactBars() + "/" + mount.bars + " bars, " + state.bentBars() + " bent";
            };
            Vec3 centre = layer.pose(vehicle, i).centre();
            String text = String.format(Locale.ROOT, "%s [%s] %s @ %.2f %.2f %.2f", mount.info.id,
                    mount.info.kind, line, centre.x, centre.y, centre.z);
            source.sendSuccess(() -> Component.literal(text), false);
        }
        return layer.mounts().size();
    }

    /**
     * Fire {@code round} at a mount's centre from {@link #RANGE} out, tilted {@code obliquity} degrees off the
     * normal about the panel's up axis. WFLib kinetic preset id.
     */
    public static int shoot(CommandSourceStack source, String mountId, ResourceLocation round, float obliquity) {
        AbstractVehicle vehicle = nearest(source);
        if (vehicle == null) {
            return 0;
        }
        AddonArmorLayer layer = vehicle.addonArmor();
        int index = -1;
        for (int i = 0; i < layer.mounts().size(); i++) {
            if (layer.mounts().get(i).info.id.equals(mountId)) {
                index = i;
            }
        }
        if (index < 0) {
            source.sendFailure(Component.literal("no mount " + mountId));
            return 0;
        }
        if (launch(vehicle, index, round, obliquity, 0, 0) == null) {
            source.sendFailure(Component.literal(round + ": not a WFLib kinetic preset"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("fired " + round + " at " + mountId), false);
        return 1;
    }

    /** A fired round: a rocket entity or a WFLib round key. */
    public record Shot(@Nullable Entity entity, long round) {

        /** Hand-stepped up to 10 ticks: lane chunks are loaded, not necessarily entity-ticking. */
        public void tickUntilGone(ServerLevel level) {
            if (this.entity == null) {
                for (int i = 0; i < 10 && Rounds.step(level, this.round); i++) {
                }
                Rounds.destroy(level, this.round);
                return;
            }
            for (int i = 0; i < 10 && !this.entity.isRemoved(); i++) {
                this.entity.tick();
            }
            this.entity.discard();
        }
    }

    /** Also the gametests' gun; aims at panel point {@code (x, y, 0)}. @return null for an unknown round */
    @Nullable
    public static Shot launch(AbstractVehicle vehicle, int mount, ResourceLocation round, float obliquity,
                              float x, float y) {
        ServerLevel level = (ServerLevel) vehicle.level();
        AddonArmorLayer.Pose pose = vehicle.addonArmor().pose(vehicle, mount);
        Vector3f out = new Quaternionf(pose.rotation())
                .transform(new Quaternionf().rotationY((float) Math.toRadians(obliquity)).transform(new Vector3f(0, 0, 1)));
        Vec3 target = pose.toWorld(x, y, 0);
        Vec3 from = target.add(out.x * RANGE, out.y * RANGE, out.z * RANGE);
        Vec3 direction = target.subtract(from).normalize();
        KineticPreset preset = KineticPresets.exists(round) ? KineticPresets.get(round) : null;
        if (preset == null) {
            return null;
        }
        if (preset.motorAccel() > 0) {
            return new Shot(RocketEntity.fire(level, preset, from, direction, 0.0f, Vec3.ZERO, null, null, null), 0L);
        }
        return new Shot(null, Rounds.launch(level, preset, from, direction.scale(preset.muzzleSpeed()), null, null));
    }
}
