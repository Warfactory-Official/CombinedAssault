package com.wf.combinedassault.indirect.client;

import com.wf.combinedassault.indirect.FireMission;
import com.wf.combinedassault.indirect.GunLaying;
import com.wf.combinedassault.indirect.IndirectFire;
import com.wf.combinedassault.indirect.IndirectFireSpec;
import com.wf.combinedassault.indirect.LayingMode;
import com.wf.combinedassault.network.message.ClientSelectRound;
import com.wf.combinedassault.vehicle.part.MissileHolderUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;
import com.wf.combinedassault.vehicle.weapon.VehicleMissile;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/** Operator-side fire missions: lay AUTO on the point, pick the round, fire as each round is ready. */
public final class FireMissions {

    /** Degrees; a self-guided round corrects its own bearing after launch. */
    private static final double SELF_GUIDED_TOLERANCE = 1.0;
    private static final double GUN_TOLERANCE = 0.1;

    private FireMissions() {
    }

    /** @return refusal message key, or null when the mission stands */
    @Nullable
    public static String order(WeaponUnit unit, Vec3 target, Item round, int rounds) {
        IndirectFireSpec spec = unit.getIndirectFireSpec();
        AbstractVehicleWeapon<?> weapon = unit.getCurrentWeapon().orElse(null);
        if (spec == null || weapon == null) {
            return "tips.indirect.unavailable";
        }
        if (rack(weapon) != null && rack(weapon).count(round) == 0) {
            return "tips.fire_mission.no_round";
        }
        GunLaying laying = unit.indirectFire();
        LayingMode was = laying.mode();
        laying.setMode(LayingMode.AUTO);
        if (!IndirectFire.solveTo(unit, target)) {
            laying.setMode(was);
            return "tips.indirect.no_solution";
        }
        weapon.setTargetLocation(target);
        select(unit, weapon, round);
        unit.setFireMission(new FireMission(target, round, rounds));
        return null;
    }

    public static void cancel(WeaponUnit unit) {
        unit.setFireMission(null);
    }

    /** Client tick of the operator's station, after laying. */
    public static void tick(WeaponUnit unit) {
        FireMission mission = unit.fireMission();
        AbstractVehicleWeapon<?> weapon = unit.getCurrentWeapon().orElse(null);
        if (mission == null || weapon == null) {
            return;
        }
        MissileHolderUnit rack = rack(weapon);
        if (rack != null) {
            if (rack.count(mission.round()) == 0) {
                finish(unit, "tips.fire_mission.out");
                return;
            }
            if (weapon.getSelectedRound().getItem() != mission.round()) {
                return;
            }
        }
        GunLaying laying = unit.indirectFire();
        Vec2 rot = unit.worldRot();
        double tolerance = IndirectFire.selfGuided(unit) ? SELF_GUIDED_TOLERANCE : GUN_TOLERANCE;
        if (laying.error(-rot.x, rot.y) > tolerance || weapon.isReloading()) {
            return;
        }
        if (weapon.doClientShoot() && mission.fired()) {
            finish(unit, "tips.fire_mission.complete");
        }
    }

    private static void finish(WeaponUnit unit, String key) {
        unit.setFireMission(null);
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable(key), true);
        }
    }

    private static void select(WeaponUnit unit, AbstractVehicleWeapon<?> weapon, Item round) {
        if (rack(weapon) != null && weapon.getSelectedRound().getItem() != round) {
            ItemStack pick = round == Items.AIR ? ItemStack.EMPTY : round.getDefaultInstance();
            weapon.predictSelectedRound(pick);
            PacketDistributor.sendToServer(new ClientSelectRound(unit.getVehicle().getId(), unit.getIndex(),
                    weapon.getIndex(), pick));
        }
    }

    /** Round kinds an operator can order (not interceptors), or {AIR} for a station with no rack. */
    public static java.util.List<Item> orderable(WeaponUnit unit) {
        AbstractVehicleWeapon<?> weapon = unit.getCurrentWeapon().orElse(null);
        MissileHolderUnit rack = weapon == null ? null : rack(weapon);
        return rack == null ? java.util.List.of(Items.AIR) : rack.kinds().stream()
                .filter(kind -> !com.wf.combinedassault.compat.wflib.WfLibCompat.isInterceptor(kind.getDefaultInstance()))
                .toList();
    }

    public static int loaded(WeaponUnit unit, Item round) {
        AbstractVehicleWeapon<?> weapon = unit.getCurrentWeapon().orElse(null);
        MissileHolderUnit rack = weapon == null ? null : rack(weapon);
        return rack != null ? rack.count(round) : weapon == null ? 0 : weapon.getRemainAmmo();
    }

    @Nullable
    private static MissileHolderUnit rack(AbstractVehicleWeapon<?> weapon) {
        return weapon instanceof VehicleMissile missile ? missile.missileHolder() : null;
    }
}
