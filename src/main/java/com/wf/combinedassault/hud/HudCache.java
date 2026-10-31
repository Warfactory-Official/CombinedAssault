package com.wf.combinedassault.hud;

import com.wf.wflib.ammo.api.AmmoItems;
import com.wf.combinedassault.weapon.RoundCount;
import com.wf.wflib.magazine.api.MagFill;
import com.wf.wflib.ammo.api.Ammo;
import com.wf.wflib.ammo.api.AmmoVariant;

import com.wf.combinedassault.ballistics.component.ComponentState;
import com.wf.combinedassault.ballistics.component.VehicleSystems;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.AbstractVehicleWeapon;
import com.wf.combinedassault.weapon.VehicleWeaponAgent;
import com.wf.wflib.ui.api.UiHud;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.scores.Team;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Per-frame / per-object memo behind the HUD signals so getters return the same objects frame after
 * frame: strings built once per weapon, seat occupant, fluid or translation, lists refilled in place.
 */
public final class HudCache {
    /** A systems cell with its label built once. Members are live: state reads current health. */
    public record SystemCell(VehicleSystems.Cell cell, String label) {
        public int state() {
            return cell.state().ordinal();
        }

        public float ratio() {
            return cell.ratio();
        }
    }

    private static final Map<AbstractVehicleWeapon<?>, String> WEAPON_NAMES = new WeakHashMap<>();
    /** Keyed by the synced id instance: {@code fuelFluid()} parses a ResourceLocation per call. */
    private static String fuelLabelId;
    private static String fuelLabel;
    private static final Map<String, String> TRANSLATIONS = new java.util.HashMap<>();
    private static Language translationsFor;

    private static AbstractVehicle systemsFor;
    private static List<SystemCell> systems = List.of();

    private static AbstractVehicle namedVehicle;
    private static long namedAt = Long.MIN_VALUE;
    private static String vehicleName = "";
    private static double nameColour;

    private static final int[] SEAT_IDS = new int[64];
    private static final String[] SEAT_NAMES = new String[64];

    private static final List<AbstractVehicleWeapon<?>> WEAPONS = new ArrayList<>();
    private static WeaponUnit weaponsFor;
    private static long weaponsFrame = -1;

    private HudCache() {
    }

    public static String translate(String key) {
        Language language = Language.getInstance();
        if (language != translationsFor) {
            translationsFor = language;
            TRANSLATIONS.clear();
        }
        String text = TRANSLATIONS.get(key);
        if (text == null) {
            text = language.getOrDefault(key);
            TRANSLATIONS.put(key, text);
        }
        return text;
    }

    /** Systems membership is fixed per vehicle; built once per boarded hull. */
    public static List<SystemCell> systems(AbstractVehicle vehicle) {
        if (vehicle != systemsFor) {
            systemsFor = vehicle;
            List<SystemCell> cells = new ArrayList<>();
            for (VehicleSystems.Cell cell : VehicleSystems.cells(vehicle)) {
                cells.add(new SystemCell(cell, cell.label()));
            }
            systems = cells;
        }
        return systems;
    }

    /**
     * Name and team colour refreshed once a second: either can change, but {@code getTeam()} builds the
     * scoreboard name (UUID string) per call.
     */
    public static String vehicleName(AbstractVehicle vehicle) {
        refreshNaming(vehicle);
        return vehicleName;
    }

    public static double nameColour(AbstractVehicle vehicle) {
        refreshNaming(vehicle);
        return nameColour;
    }

    private static void refreshNaming(AbstractVehicle vehicle) {
        long now = vehicle.level().getGameTime();
        if (vehicle != namedVehicle || now - namedAt >= 20 || now < namedAt) {
            namedVehicle = vehicle;
            namedAt = now;
            vehicleName = vehicle.getDisplayName().getString();
            Team team = vehicle.getTeam();
            Integer colour = team == null ? null : team.getColor().getColor();
            nameColour = colour == null ? 0xDDFFFFFFL : 0xFF000000L | colour;
        }
    }

    public static String weaponName(AbstractVehicleWeapon<?> weapon) {
        String name = WEAPON_NAMES.get(weapon);
        if (name == null) {
            name = weapon.getDisplayName().getString().trim();
            WEAPON_NAMES.put(weapon, name);
        }
        return name;
    }

    /** Key: variant id, else item; bounded by registered kinds. */
    private static final Map<Object, String> ROUND_LABELS = new java.util.HashMap<>();

    /** Variant only ({@code APFSDS}); not a cartridge => full name. */
    public static String roundLabel(ItemStack round) {
        if (round.isEmpty()) {
            return "";
        }
        return ROUND_LABELS.computeIfAbsent(roundKey(round), key -> {
            if (MagFill.isMagazine(round)) {
                return String.join(" / ", RoundCount.variants(round).stream().map(v -> Ammo.variant(v).name()).toList());
            }
            AmmoVariant variant = AmmoItems.variantOf(round);
            return variant != null ? variant.name() : round.getHoverName().getString();
        });
    }

    /** Magazine: item + its variants (any fill). */
    private static Object roundKey(ItemStack round) {
        if (MagFill.isMagazine(round)) {
            return List.of(round.getItem(), RoundCount.variants(round));
        }
        ResourceLocation variant = round.get(AmmoItems.VARIANT.get());
        return variant != null ? variant : round.getItem();
    }

    private static final Map<Object, String> PAYLOAD_NAMES = new WeakHashMap<>();

    private static final Map<net.minecraft.client.KeyMapping, com.mojang.blaze3d.platform.InputConstants.Key> BOUND =
            new java.util.IdentityHashMap<>();
    private static final Map<net.minecraft.client.KeyMapping, String> KEY_NAMES = new java.util.IdentityHashMap<>();

    /** Bound key's display name; re-read on rebind. */
    public static String keyName(net.minecraft.client.KeyMapping mapping) {
        if (BOUND.get(mapping) != mapping.getKey()) {
            BOUND.put(mapping, mapping.getKey());
            KEY_NAMES.put(mapping, mapping.getTranslatedKeyMessage().getString());
        }
        return KEY_NAMES.get(mapping);
    }

    /** Keyed by stack identity: a sync replaces the stack. */
    public static String payloadName(net.minecraft.world.item.ItemStack stack) {
        String name = PAYLOAD_NAMES.get(stack);
        if (name == null) {
            name = stack.getHoverName().getString();
            PAYLOAD_NAMES.put(stack, name);
        }
        return name;
    }

    public static String fuelLabel(AbstractVehicle vehicle) {
        if (vehicle.isElectric()) {
            return "CHARGE";
        }
        String id = vehicle.fuelId();
        if (id != fuelLabelId) {
            Fluid fluid = vehicle.fuelFluid();
            fuelLabelId = id;
            fuelLabel = fluid == Fluids.EMPTY ? "FUEL"
                    : new FluidStack(fluid, 1).getHoverName().getString().toUpperCase(Locale.ROOT);
        }
        return fuelLabel;
    }

    /** Occupant display name; "" for an empty seat. */
    public static String seatName(AbstractVehicle.Seat seat) {
        Integer id = seat.passengerId;
        int index = seat.seatIndex & 63;
        if (id == null || id < 0) {
            return "";
        }
        if (SEAT_IDS[index] == id && SEAT_NAMES[index] != null) {
            return SEAT_NAMES[index];
        }
        Entity entity = Minecraft.getInstance().level == null ? null : Minecraft.getInstance().level.getEntity(id);
        String name = entity == null ? "" : entity.getDisplayName().getString();
        if (entity != null) {
            SEAT_IDS[index] = id;
            SEAT_NAMES[index] = name;
        }
        return name;
    }

    /** Station weapons in tab order (other kits' left out), agents resolved to the weapon they currently carry. */
    public static List<AbstractVehicleWeapon<?>> weapons(WeaponUnit unit) {
        long frame = UiHud.frame();
        if (unit == weaponsFor && frame == weaponsFrame) {
            return WEAPONS;
        }
        weaponsFor = unit;
        weaponsFrame = frame;
        WEAPONS.clear();
        add(unit, unit.weapons);
        add(unit, unit.secondaryWeapons);
        add(unit, unit.independentWeapons);
        return WEAPONS;
    }

    private static void add(WeaponUnit unit, List<? extends AbstractVehicleWeapon<?>> list) {
        for (int i = 0; i < list.size(); i++) {
            AbstractVehicleWeapon<?> weapon = list.get(i);
            if (!unit.carries(weapon)) {
                continue;
            }
            if (weapon instanceof VehicleWeaponAgent agent) {
                AbstractVehicleWeapon<?> current = agent.getWeaponUnit().currentWeapon();
                if (current != null) {
                    weapon = current;
                }
            }
            WEAPONS.add(weapon);
        }
    }

    static ComponentState hullState(AbstractVehicle vehicle) {
        float max = vehicle.getMaxHealth();
        return ComponentState.of(max <= 0 ? 1 : Math.max(0, vehicle.getHealth() / max));
    }
}
