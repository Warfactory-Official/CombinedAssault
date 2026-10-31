package com.wf.combinedassault.weapon.ordnance;

import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.indirect.RoundSpec;
import com.wf.combinedassault.vehicle.util.VectorUtil;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.pojo.AimContext;
import com.wf.wflib.ammo.api.AmmoItems;
import com.wf.wflib.ammo.api.AmmoVariant;
import com.wf.wflib.countermeasure.api.CountermeasureItem;
import com.wf.wflib.missile.api.MissileItem;
import com.wf.wflib.missile.api.MissilePreset;
import com.wf.wflib.round.api.KineticPreset;
import com.wf.wflib.round.api.KineticPresets;
import com.wf.wflib.round.api.KineticShellItem;
import com.wf.wflib.recon.api.ReconOwners;
import com.wf.wflib.round.api.RocketEntity;
import com.wf.wflib.round.api.Rounds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Every vehicle round is a WFLib item: cartridge / shell / rocket / bomb => kinetic preset, missile or guided bomb
 * => missile preset, dispenser load => countermeasure. Gun = launcher (ammo, cooldown, recoil, laying, spread).
 */
public final class Munitions {

    private Munitions() {
    }

    @Nullable
    public static KineticPreset kinetic(ItemStack round) {
        AmmoVariant variant = AmmoItems.variantOf(round);
        if (variant != null) {
            return KineticPresets.get(variant.id());
        }
        return KineticShellItem.presetOf(round);
    }

    @Nullable
    public static MissilePreset missile(ItemStack round) {
        return round.getItem() instanceof MissileItem item ? item.preset() : null;
    }

    /** mm, for muzzle effects; 0 = unknown round. */
    public static float caliber(ItemStack round) {
        KineticPreset k = kinetic(round);
        return k != null ? k.caliberMm() : 0f;
    }

    /**
     * Firing-solution numbers of a kinetic round; null otherwise. Ammo changes the solution, not just the target.
     */
    @Nullable
    public static RoundSpec spec(ItemStack round) {
        KineticPreset k = kinetic(round);
        if (k == null || k.muzzleSpeed() <= 0.0f) {
            return null;
        }
        return new RoundSpec(k.muzzleSpeed(), k.drag(), k.gravity(), k.lifeTicks(), k.quadraticDrag());
    }

    /**
     * Server. Spread {@code 0.0172275 x inaccuracy} triangle on the heading -> muzzle speed -> vehicle motion.
     *
     * @return false: not a WFLib round, or a missile with nowhere to go
     */
    public static boolean fire(AbstractVehicle vehicle, WeaponUnit unit, AimContext aim, ItemStack round,
                               float inaccuracy, @Nullable Entity locked, @Nullable Entity operator) {
        return fire(vehicle, unit, aim, round, inaccuracy, locked, operator, 0.0f, 1.0f);
    }

    /**
     * {@code lead}: tick fraction elapsed at the shot, gun rounds only; see {@link Rounds#launch}. {@code velocity}:
     * gun round muzzle speed factor (barrel variant).
     */
    public static boolean fire(AbstractVehicle vehicle, WeaponUnit unit, AimContext aim, ItemStack round,
                               float inaccuracy, @Nullable Entity locked, @Nullable Entity operator, float lead,
                               float velocity) {
        if (!(vehicle.level() instanceof ServerLevel level) || aim.from == null || round.isEmpty()) {
            return false;
        }
        if (round.getItem() instanceof MissileItem) {
            return BallisticsMissiles.shootRound(vehicle, unit, aim, round, locked, operator);
        }
        LivingEntity owner = unit.getOwner();
        Entity shooter = owner != null ? owner : vehicle;
        Vec3 heading = VectorUtil.rotToVec(aim.direction.x, aim.direction.y);
        if (round.getItem() instanceof CountermeasureItem cm) {
            cm.deploy(level, aim.from, heading, inaccuracy, vehicle.getDeltaMovement(), shooter, vehicle);
            return true;
        }
        KineticPreset preset = kinetic(round);
        if (preset == null) {
            return false;
        }
        RandomSource random = level.getRandom();
        boolean rocket = preset.motorAccel() > 0.0f;
        // Rocket: RocketEntity adds the preset's own dispersion.
        double s = 0.0172275 * (inaccuracy + (rocket ? 0.0f : preset.dispersion()));
        Vec3 dir = heading.add(random.triangle(0.0, s), random.triangle(0.0, s), random.triangle(0.0, s));
        if (rocket) {
            // Rocket: interceptable entity; launching vehicle = control id.
            RocketEntity.fire(level, preset, aim.from, dir, 0.0f, vehicle.getDeltaMovement(), shooter,
                    ReconOwners.owningEntity(vehicle), vehicle.getUUID());
            return true;
        }
        // Faction = the vehicle's side: the blast honours that side's chunk rules.
        Rounds.launch(level, preset, aim.from, dir.scale(preset.muzzleSpeed() * velocity).add(vehicle.getDeltaMovement()),
                shooter, ReconOwners.owningEntity(vehicle), 1.0f, 0, Rounds.NO_SEQ, lead);
        return true;
    }
}
