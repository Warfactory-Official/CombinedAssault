package com.wf.combinedassault.vehicle.control;

import com.mojang.blaze3d.platform.InputConstants;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.all.AllKeys;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.network.message.ClientSelectRound;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.crew.CrewRole;
import com.wf.combinedassault.vehicle.crew.CrewRules;
import com.wf.combinedassault.vehicle.crew.WeaponLoading;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;
import com.wf.combinedassault.vehicle.weapon.RoundCount;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Switch-ammo key: press => list opens, cursor on the next kind; held => wheel moves the cursor; release commits
 * ({@link ClientSelectRound}). List stays {@link #SHOW_MS} after the last change, then fades.
 */
@EventBusSubscriber(modid = CombinedAssault.MOD_ID, value = Dist.CLIENT)
public final class AmmoSelect {
    private static final long SHOW_MS = 3000;
    private static final long FADE_MS = 400;
    @Nullable
    private static AbstractVehicleWeapon<?> weapon;
    private static boolean held;
    private static int cursor;
    private static long changedAt = Long.MIN_VALUE / 2;

    private AmmoSelect() {
    }

    /** Own station's current weapon; loader => first served gun's. */
    @Nullable
    public static AbstractVehicleWeapon<?> target() {
        LocalVehiclePlayer local = LocalVehiclePlayer.instance;
        AbstractVehicle vehicle = local.vehicle;
        Player player = local.getPlayer();
        if (vehicle == null || player == null) {
            return null;
        }
        WeaponUnit own = local.getWeaponUnit();
        if (own != null) {
            return own.currentWeapon();
        }
        if (!CrewRules.has(vehicle, player, CrewRole.LOADER)) {
            return null;
        }
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part instanceof WeaponUnit unit && CrewRules.loading(unit) != WeaponLoading.SELF
                    && unit.currentWeapon() != null && unit.currentWeapon().getData().getReload() != null) {
                return unit.currentWeapon();
            }
        }
        return null;
    }

    @Nullable
    public static AbstractVehicleWeapon<?> weapon() {
        return weapon;
    }

    public static int cursor() {
        return cursor;
    }

    /** Wall-clock ms of the last cursor move; marquee restarts from it. */
    public static long changedAt() {
        return changedAt;
    }

    /** 1 while held or within {@link #SHOW_MS}, fading to 0; 0 once the target weapon changed. */
    public static float alpha() {
        if (weapon == null || weapon != target()) {
            return 0;
        }
        if (held) {
            return 1;
        }
        long since = System.currentTimeMillis() - changedAt - SHOW_MS;
        return since <= 0 ? 1 : Math.max(0, 1 - since / (float) FADE_MS);
    }

    /** Index of the weapon's pick in {@code options}; -1 none. */
    public static int selectedIndex(AbstractVehicleWeapon<?> weapon, List<RoundCount> options) {
        ItemStack selected = weapon.getSelectedRound();
        for (int i = 0; i < options.size(); i++) {
            if (ItemStack.isSameItemSameComponents(options.get(i).round(), selected)) {
                return i;
            }
        }
        return -1;
    }

    static void press() {
        weapon = target();
        if (weapon == null) {
            return;
        }
        List<RoundCount> options = weapon.roundOptions();
        cursor = options.isEmpty() ? 0 : (selectedIndex(weapon, options) + 1) % options.size();
        held = true;
        changedAt = System.currentTimeMillis();
    }

    /** True when the wheel moved the cursor (event consumed). */
    static boolean scroll(double delta) {
        if (!held || weapon == null || delta == 0) {
            return false;
        }
        int size = weapon.roundOptions().size();
        if (size > 0) {
            cursor = Mth.positiveModulo(cursor + (delta < 0 ? 1 : -1), size);
            changedAt = System.currentTimeMillis();
        }
        return true;
    }

    private static void release() {
        held = false;
        changedAt = System.currentTimeMillis();
        if (weapon == null || weapon != target()) {
            return;
        }
        List<RoundCount> options = weapon.roundOptions();
        if (cursor >= options.size()) {
            return;
        }
        ItemStack pick = options.get(cursor).round();
        if (ItemStack.isSameItemSameComponents(pick, weapon.getSelectedRound())) {
            return;
        }
        for (PartUnit<?> part : weapon.getVehicle().getPartUnits()) {
            if (part instanceof WeaponUnit unit && unit.indexedWeapons.contains(weapon)) {
                // Refused pick never echoes (SyncDataHolder.predict TODO): predict only what the server allows.
                if (CrewRules.maySwitchAmmo(LocalVehiclePlayer.instance.getPlayer(), unit)) {
                    weapon.predictSelectedRound(pick);
                }
                PacketDistributor.sendToServer(new ClientSelectRound(weapon.getVehicle().getId(), unit.getIndex(),
                        unit.indexedWeapons.indexOf(weapon), pick));
                return;
            }
        }
    }

    /** Release read off GLFW: a screen opened while held swallows the key-up event. */
    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (!held) {
            return;
        }
        long window = Minecraft.getInstance().getWindow().getWindow();
        InputConstants.Key key = AllKeys.SWITCH_AMMO.getKey();
        boolean down = key.getType() == InputConstants.Type.MOUSE
                ? GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS
                : InputConstants.isKeyDown(window, key.getValue());
        if (!down) {
            release();
        }
    }
}
