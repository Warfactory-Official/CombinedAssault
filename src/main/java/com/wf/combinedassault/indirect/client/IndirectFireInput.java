package com.wf.combinedassault.indirect.client;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import com.wf.combinedassault.indirect.GunLaying;
import com.wf.combinedassault.indirect.IndirectFire;
import com.wf.combinedassault.indirect.LayingMode;
import com.wf.combinedassault.indirect.RoundSpec;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.weapon.part.WeaponUnit;

/** Repeats honoured, not just presses: a held key moves the mount many steps. */
public final class IndirectFireInput {

    private IndirectFireInput() {
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        int action = event.getAction();
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_REPEAT) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.isSpectator() || mc.screen != null) {
            return;
        }
        WeaponUnit unit = gunnerUnit();
        if (unit == null) {
            return;
        }
        GunLaying laying = unit.indirectFire();
        int key = event.getKey();
        int scan = event.getScanCode();

        if (action == GLFW.GLFW_PRESS && matches(IndirectFireKeys.TOGGLE_INDIRECT, key, scan)) {
            cycleMode(player, unit, laying);
            return;
        }
        if (!laying.isIndirect()) {
            return;
        }
        // success says nothing (board shows it; an action bar message would cover the sky); refusal is
        // said, since a stuck gun looks exactly like a key that didn't register.
        if (matches(IndirectFireKeys.ELEVATION_UP, key, scan)) {
            correct(player, unit, laying.elevate(IndirectFire.specOf(unit), muzzle(unit, laying), 1));
        } else if (matches(IndirectFireKeys.ELEVATION_DOWN, key, scan)) {
            correct(player, unit, laying.elevate(IndirectFire.specOf(unit), muzzle(unit, laying), -1));
        } else if (matches(IndirectFireKeys.TRAVERSE_LEFT, key, scan)) {
            correct(player, unit, laying.traverse(IndirectFire.specOf(unit), muzzle(unit, laying), -1));
        } else if (matches(IndirectFireKeys.TRAVERSE_RIGHT, key, scan)) {
            correct(player, unit, laying.traverse(IndirectFire.specOf(unit), muzzle(unit, laying), 1));
        } else if (action == GLFW.GLFW_PRESS && matches(IndirectFireKeys.CYCLE_STEP, key, scan)) {
            laying.cycleStep();
        } else if (action == GLFW.GLFW_PRESS && matches(IndirectFireKeys.TOGGLE_ARC, key, scan)) {
            RoundSpec spec = IndirectFire.specOf(unit);
            if (spec == null || !laying.toggleArc(spec, IndirectFire.muzzleOf(unit))) {
                say(player, "tips.indirect.no_solution");
            }
        }
    }

    /** DIRECT -> MANUAL -> AUTO -> DIRECT; a self-guided round has nothing to lay by hand. */
    private static void cycleMode(LocalPlayer player, WeaponUnit unit, GunLaying laying) {
        if (unit.getIndirectFireSpec() == null) {
            say(player, "tips.indirect.unavailable");
            return;
        }
        LayingMode next = switch (laying.mode()) {
            case DIRECT -> IndirectFire.selfGuided(unit) ? LayingMode.AUTO : LayingMode.MANUAL;
            case MANUAL -> LayingMode.AUTO;
            case AUTO -> LayingMode.DIRECT;
        };
        if (laying.mode() == LayingMode.DIRECT) {
            // from the barrel's current angle: taking it off the sight must not slew it.
            Vec2 rot = unit.worldRot();
            laying.setElevation(-rot.x);
            laying.setAzimuth(rot.y);
        }
        laying.setMode(next);
        switch (next) {
            case MANUAL -> say(player, "tips.indirect.indirect");
            case AUTO -> player.displayClientMessage(Component.translatable("tips.indirect.auto",
                    com.wf.combinedassault.vehicle.client.VehicleKeys.PRESET_COORDINATE_INPUT.getTranslatedKeyMessage()), true);
            case DIRECT -> {
                laying.clearTarget();
                FireMissions.cancel(unit);
                unit.getCurrentWeapon().ifPresent(weapon -> {
                    if (unit.getFireControlSensorType()
                            != com.wf.combinedassault.weapon.part.data.WeaponUnitData.FireControlSensorType.LOC) {
                        weapon.setTargetLocation(null);
                    }
                });
                say(player, "tips.indirect.direct");
            }
        }
    }

    /** Only a correction in blocks needs the muzzle, and only a correction can be refused. */
    private static Vec3 muzzle(WeaponUnit unit, GunLaying laying) {
        return laying.step().inBlocks() ? IndirectFire.muzzleOf(unit) : Vec3.ZERO;
    }

    private static void correct(LocalPlayer player, WeaponUnit unit, boolean moved) {
        if (!moved) {
            say(player, IndirectFire.specOf(unit) == null
                    ? "tips.indirect.no_round" : "tips.indirect.no_correction");
        }
    }

    /** The weapon station the local player is actually manning, or null. */
    @Nullable
    private static WeaponUnit gunnerUnit() {
        LocalVehiclePlayer instance = LocalVehiclePlayer.instance();
        if (!instance.onVehicle()) {
            return null;
        }
        return instance.getWeaponUnit();
    }

    private static void say(LocalPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }

    private static boolean matches(KeyMapping mapping, int key, int scanCode) {
        return mapping.matches(key, scanCode) || mapping.matchesMouse(key);
    }

}
