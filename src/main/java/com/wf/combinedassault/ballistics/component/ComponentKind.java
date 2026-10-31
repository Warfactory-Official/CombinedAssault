package com.wf.combinedassault.ballistics.component;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.dynamics.part.TrackUnit;
import com.wf.combinedassault.weapon.part.WeaponUnit;

import java.util.Locale;

/** A vehicle system: what a hit breaks, a repair fixes. */
public enum ComponentKind {

    /** The vehicle's own pool ({@code AbstractVehicle#getHealth}). */
    HULL("hull", "HULL", 200, 6),
    ENGINE("engine", "ENG", 240, 8),
    TRACK("track", "TR", 200, 6),
    WHEEL("wheel", "WHL", 120, 4),
    TURRET("turret", "TRRT", 200, 6),
    BARREL("gun", "GUN", 160, 5),
    FUEL("fuel", "FUEL", 160, 5),
    AMMO("ammo", "AMMO", 160, 5),
    OPTICS("optics", "OPT", 120, 4),
    /** Main rotor(s): lift and attitude authority. */
    ROTOR("rotor", "ROTR", 240, 8),
    /** Anti-torque: yaw authority; lost => torque yaws the airframe. */
    TAIL_ROTOR("tail_rotor", "TAIL", 200, 6),
    WING("wing", "WING", 240, 8),
    CREW("crew", "CREW", 0, 0),
    GENERIC("generic", "", 0, 0);

    public final String code;
    public final String abbrev;
    public final int stepTicks;
    public final int durability;
    public final TagKey<Item> toolTag;
    public final TagKey<Item> partsTag;

    public static final float BROKEN_TIME_SCALE = 1.5f;

    ComponentKind(String code, String abbrev, int stepTicks, int durability) {
        this.code = code;
        this.abbrev = abbrev;
        this.stepTicks = stepTicks;
        this.durability = durability;
        this.toolTag = tag(code + "_tool");
        this.partsTag = tag(code + "_parts");
    }

    private static TagKey<Item> tag(String path) {
        return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, path));
    }

    /** Has a HUD cell, a repair, and counts toward "all systems failed". */
    public boolean isSystem() {
        return this != CREW && this != GENERIC;
    }

    /** Ground mobility (scales {@link VehicleMobility}); ROTOR/WING excluded: losing them isn't slower driving. */
    public boolean isMobility() {
        return this == TRACK || this == WHEEL || this == ENGINE;
    }

    /** Loss pulls the vehicle to one side. */
    public boolean steers() {
        return this == TRACK || this == WHEEL;
    }

    /** Every name a pack may write (error message: what was allowed). */
    public static String names() {
        StringBuilder out = new StringBuilder();
        for (ComponentKind kind : values()) {
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append(kind.name().toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    public String translationKey() {
        return "component.combinedassault." + name().toLowerCase(Locale.ROOT);
    }

    public static ComponentKind byName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (ComponentKind kind : values()) {
            if (kind.name().equalsIgnoreCase(name)) {
                return kind;
            }
        }
        return null;
    }

    public static ComponentKind infer(PartUnit<?> part) {
        if (part instanceof TrackUnit) {
            return TRACK;
        }
        String id = part.getId().toLowerCase(Locale.ROOT);
        if (id.contains("engine") || id.contains("motor") || id.contains("powerpack")) {
            return ENGINE;
        }
        if (id.contains("wheel")) {
            return WHEEL;
        }
        if (id.contains("sight") || id.contains("camera") || id.contains("optic")) {
            return OPTICS;
        }
        if (id.contains("flare") || id.contains("decoy") || id.contains("smoke") || id.contains("seat")) {
            return GENERIC;
        }
        if (id.contains("gun") || id.contains("cannon")) {
            return BARREL; // BARREL first: a turret-mounted "turret_machine_gun" id must not win as TURRET.
        }
        if (id.contains("turret")) {
            return TURRET;
        }
        if (part instanceof WeaponUnit) {
            return BARREL;
        }
        return GENERIC;
    }

}
