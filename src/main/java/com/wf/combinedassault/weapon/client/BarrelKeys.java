package com.wf.combinedassault.weapon.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.wf.combinedassault.weapon.barrel.BarrelPacket;
import com.wf.combinedassault.weapon.barrel.GunBarrel;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.AbstractVehicleWeapon;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/** Seated: reload / clear jam, change barrel on the station's current weapon. 6 / 7: 1-5 = CA vehicle keys, Y taken. */
public final class BarrelKeys {
    public static final KeyMapping RELOAD = key("reload_clear_jam", GLFW.GLFW_KEY_6);
    public static final KeyMapping CHANGE = key("change_barrel", GLFW.GLFW_KEY_7);

    private BarrelKeys() {
    }

    private static KeyMapping key(String name, int key) {
        return new KeyMapping("key.combinedassault." + name + ".desc", KeyConflictContext.IN_GAME, KeyModifier.NONE,
                InputConstants.Type.KEYSYM, key, "key.category.combinedassault");
    }

    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(RELOAD);
        event.register(CHANGE);
    }

    /** Own station's current weapon, the secondary when only it has a barrel. */
    @Nullable
    public static AbstractVehicleWeapon<?> target() {
        WeaponUnit own = LocalVehiclePlayer.instance().onVehicle() ? LocalVehiclePlayer.instance().getWeaponUnit() : null;
        if (own == null) {
            return null;
        }
        AbstractVehicleWeapon<?> main = own.currentWeapon();
        AbstractVehicleWeapon<?> secondary = own.currentSecondaryWeapon();
        return main != null && main.barrel() == null && secondary != null && secondary.barrel() != null ? secondary : main;
    }

    @SubscribeEvent
    static void onKey(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getAction() != GLFW.GLFW_PRESS || mc.screen != null || mc.player == null) {
            return;
        }
        BarrelPacket.Action action = RELOAD.matches(event.getKey(), event.getScanCode()) ? BarrelPacket.Action.RELOAD
                : CHANGE.matches(event.getKey(), event.getScanCode()) ? BarrelPacket.Action.CHANGE : null;
        AbstractVehicleWeapon<?> weapon = action == null ? null : target();
        if (weapon == null) {
            return;
        }
        WeaponUnit holder = weapon.barrel() != null ? weapon.barrel().holder() : GunBarrel.holderOf(weapon);
        PacketDistributor.sendToServer(new BarrelPacket(weapon.getVehicle().getId(), holder.getIndex(),
                holder.indexedWeapons.indexOf(weapon), action));
    }
}
