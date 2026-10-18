package com.wf.combinedassault.client.tooltip;

import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.part.PartUnitEntry;
import com.wf.combinedassault.custom.part.data.WeaponUnitData;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.custom.weapon.data.BaseVehicleWeaponData;
import com.wf.combinedassault.vehicle.hardware.HardwareKit;
import com.wf.combinedassault.vehicle.hardware.HardwareKits;
import com.wf.combinedassault.vehicle.part.client.PartNames;
import com.wf.combinedassault.ballistics.ArmorProfile;
import com.wf.combinedassault.vehicle.pojo.EnergyInfo;
import com.wf.combinedassault.vehicle.pojo.WeaponInfo;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Spawn item: stats; Shift: weapon stations and hardpoints. Hardware item: fits, weapons, ammo / magazines. */
@OnlyIn(Dist.CLIENT)
public final class VehicleTooltips {

    private static final String KEY = "tooltip.combinedassault.";
    /** Ammo / magazine items listed per weapon; rest counted. */
    private static final int LISTED = 3;

    private VehicleTooltips() {
    }

    public static void vehicle(BaseVehicleData<?> data, List<Component> out) {
        stat(out, "mass", String.format(Locale.ROOT, "%.1f t", data.getPhysicsInfo().mass / 1000f));
        stat(out, "health", String.format(Locale.ROOT, "%.0f", data.getMaxHealth()));
        ArmorProfile armor = data.getArmor();
        if (armor != null && armor.isDefined()) {
            stat(out, "armor", String.format(Locale.ROOT, "%.0f / %.0f / %.0f mm", armor.front, armor.side, armor.rear));
        }
        long seats = data.getParts().stream().filter(e -> e.data().isSeat()).count();
        stat(out, "seats", Long.toString(seats));
        EnergyInfo energy = data.getEnergyInfo();
        if (energy != null) {
            stat(out, energy.isElectric() ? "battery" : "fuel", String.format(Locale.ROOT, "%.0f", energy.capacityUnits()));
        }
        if (data.getCargo().enabled()) {
            stat(out, "cargo", String.format(Locale.ROOT, "%.0f kg", data.getCargo().maxMass));
        }
        List<WeaponUnitData> stations = data.getParts().stream().map(PartUnitEntry::data)
                .filter(d -> d instanceof WeaponUnitData unit && (unit.getHardpoint() != null
                        || unit.getWeapons().stream().anyMatch(info -> info.id != null)))
                .map(d -> (WeaponUnitData) d).toList();
        if (stations.isEmpty()) {
            return;
        }
        if (!Screen.hasShiftDown()) {
            out.add(Component.translatable(KEY + "shift", stations.size()).withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        for (WeaponUnitData unit : stations) {
            out.add(PartNames.name(unit).copy().withStyle(ChatFormatting.GOLD));
            HardwareKits.Hardpoint hardpoint = unit.getHardpoint();
            if (hardpoint != null) {
                out.add(line(Component.translatable(KEY + "hardpoint", String.join(", ", hardpoint.accepts().stream()
                        .sorted().toList()))));
                for (HardwareKit kit : hardpoint.kits()) {
                    MutableComponent name = Component.translatable(kit.nameKey());
                    out.add(line(kit.id().equals(hardpoint.fitted())
                            ? Component.translatable(KEY + "fitted", name).withStyle(ChatFormatting.GREEN) : name));
                }
                continue;
            }
            for (WeaponInfo info : unit.getWeapons()) {
                BaseVehicleWeaponData weapon = weapon(info.id);
                if (weapon != null) {
                    out.add(line(Component.translatable(weapon.getName())));
                }
            }
        }
    }

    public static void kit(HardwareKit kit, List<Component> out) {
        stat(out, "fits", String.join(", ", kit.fits().stream().sorted().toList()));
        stat(out, "mount_time", String.format(Locale.ROOT, "%.1f s", kit.installTicks() / 20f));
        for (var entry : kit.weapons()) {
            ResourceLocation id = ResourceLocation.parse(entry.isJsonObject()
                    ? entry.getAsJsonObject().get("id").getAsString() : entry.getAsString());
            BaseVehicleWeaponData weapon = weapon(id);
            if (weapon == null) {
                continue;
            }
            out.add(Component.translatable(weapon.getName()).withStyle(ChatFormatting.GOLD));
            BaseVehicleWeaponData.Reload reload = weapon.getReload();
            if (reload == null) {
                continue;
            }
            if (reload.getAmmo() != null && reload.takesLoose()) {
                out.add(line(Component.translatable(KEY + "ammo", items(reload.getAmmo()))));
            }
            if (reload.getMagazines() != null) {
                out.add(line(Component.translatable(KEY + "magazines", items(reload.getMagazines()))));
            }
        }
    }

    @Nullable
    private static BaseVehicleWeaponData weapon(@Nullable ResourceLocation id) {
        return id == null ? null : CommonAssetsManager.vehicleWeaponManager().getIndex(id)
                .map(index -> (BaseVehicleWeaponData) index.data()).orElse(null);
    }

    private static Component items(Ingredient ingredient) {
        Set<String> names = new LinkedHashSet<>();
        for (ItemStack stack : ingredient.getItems()) {
            names.add(stack.getHoverName().getString());
        }
        List<String> listed = new ArrayList<>(names);
        MutableComponent out = Component.literal(String.join(", ", listed.subList(0, Math.min(LISTED, listed.size()))));
        if (listed.size() > LISTED) {
            out.append(Component.translatable(KEY + "more", listed.size() - LISTED));
        }
        return out;
    }

    private static void stat(List<Component> out, String key, String value) {
        out.add(Component.translatable(KEY + key, Component.literal(value).withStyle(ChatFormatting.WHITE))
                .withStyle(ChatFormatting.GRAY));
    }

    private static Component line(Component text) {
        return Component.literal("  ").append(text).withStyle(ChatFormatting.GRAY);
    }
}
