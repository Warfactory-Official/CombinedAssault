package com.wf.combinedassault.energy;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.all.AllConfigs;
import com.wf.combinedassault.all.AllFluids;
import com.wf.combinedassault.vehicle.pojo.EnergyInfo;
import com.wf.combinedassault.vehicle.pojo.FuelEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * The fluids one vehicle burns, resolved from its pack.
 *
 * <p>Entries are matched in declaration order, so a specific fluid listed before a tag it belongs to
 * wins. A pack that declares nothing falls back to the {@code fuelNameWhiteList} config at efficiency
 * 1, which is what every vehicle did before fuels were data-driven.
 */
public final class FuelSpec {

    private static final FuelSpec CONFIG_FALLBACK = new FuelSpec(List.of());

    private final List<Entry> entries;

    private FuelSpec(List<Entry> entries) {
        this.entries = entries;
    }

    public static FuelSpec of(@Nullable EnergyInfo info) {
        if (info == null || info.fuels == null || info.fuels.isEmpty()) {
            return CONFIG_FALLBACK;
        }
        List<Entry> parsed = new ArrayList<>(info.fuels.size());
        for (FuelEntry fuel : info.fuels) {
            Entry entry = Entry.parse(fuel);
            if (entry != null) {
                parsed.add(entry);
            }
        }
        return parsed.isEmpty() ? CONFIG_FALLBACK : new FuelSpec(List.copyOf(parsed));
    }

    /** True when this vehicle declared its own fuels rather than inheriting the config whitelist. */
    public boolean isDeclared() {
        return !entries.isEmpty();
    }

    public boolean accepts(Fluid fluid) {
        if (fluid == null || fluid == Fluids.EMPTY) {
            return false;
        }
        if (entries.isEmpty()) {
            return matchesConfig(fluid);
        }
        for (Entry entry : entries) {
            if (entry.matches(fluid)) {
                return true;
            }
        }
        return false;
    }

    /** Range multiplier for a fluid. 1 for anything this spec does not rate. */
    public float efficiencyOf(Fluid fluid) {
        if (fluid == null || fluid == Fluids.EMPTY) {
            return 1f;
        }
        for (Entry entry : entries) {
            if (entry.matches(fluid)) {
                return entry.efficiency <= 0 ? 1f : entry.efficiency;
            }
        }
        return 1f;
    }

    public float efficiencyOf(String fluidId) {
        return efficiencyOf(fluidOf(fluidId));
    }

    /** What an undeclared tank is assumed to hold, for legacy saves and the infinite-fuel config. */
    public Fluid defaultFuel() {
        for (Entry entry : entries) {
            if (entry.fluid != null) {
                return entry.fluid;
            }
        }
        return AllFluids.FUEL_SOURCE.get();
    }

    /** Fluid ids and tags this vehicle takes, for a refusal message. */
    public List<String> describe() {
        if (entries.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (String fragment : configWhitelist()) {
                names.add("*" + fragment + "*");
            }
            return names;
        }
        List<String> names = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            names.add(entry.tag != null ? "#" + entry.tag.location() : String.valueOf(idOf(entry.fluid)));
        }
        return names;
    }

    private boolean matchesConfig(Fluid fluid) {
        ResourceLocation key = idOf(fluid);
        if (key == null) {
            return false;
        }
        String id = key.toString();
        for (String fragment : configWhitelist()) {
            if (id.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    private static List<? extends String> configWhitelist() {
        if (AllConfigs.common == null) {
            return List.of("fuel");
        }
        return AllConfigs.common.fuelNameWhiteList.get();
    }

    @Nullable
    public static ResourceLocation idOf(@Nullable Fluid fluid) {
        return fluid == null || fluid == Fluids.EMPTY ? null : BuiltInRegistries.FLUID.getKey(fluid);
    }

    /** Registry lookup that answers EMPTY rather than a default for an id nothing registered. */
    public static Fluid fluidOf(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return Fluids.EMPTY;
        }
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            return Fluids.EMPTY;
        }
        return BuiltInRegistries.FLUID.getOptional(key).orElse(Fluids.EMPTY);
    }

    /** One accepted fluid or tag. Exactly one of {@code fluid} and {@code tag} is set. */
    private record Entry(@Nullable Fluid fluid, @Nullable TagKey<Fluid> tag, float efficiency) {

        @Nullable
        static Entry parse(@Nullable FuelEntry fuel) {
            if (fuel == null || fuel.fluid == null || fuel.fluid.isBlank()) {
                return null;
            }
            String raw = fuel.fluid.trim();
            if (raw.startsWith("#")) {
                ResourceLocation key = ResourceLocation.tryParse(raw.substring(1));
                return key == null ? null : new Entry(null, TagKey.create(Registries.FLUID, key), fuel.efficiency);
            }
            // Unregistered ids are kept out rather than resolved to a default, which would accept
            // every unknown fluid at once.
            Fluid fluid = fluidOf(raw);
            return fluid == Fluids.EMPTY ? null : new Entry(fluid, null, fuel.efficiency);
        }

        boolean matches(Fluid candidate) {
            if (fluid != null) {
                return fluid == candidate;
            }
            return tag != null && candidate.builtInRegistryHolder().is(tag);
        }

    }

}
