package com.wf.combinedassault.vehicle.light;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.hit.ModelData;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Role {@code light} node: GemRender spot along the node's +Z, cookie up = +Y; follows the node's posed frame.
 * {@code intensity}: irradiance ~1 at {@code sqrt(intensity)} blocks. Half angles deg.
 *
 * @param node glTF node index
 */
public record VehicleLight(int node, String name, Kind kind, int color, float range, float intensity, float inner,
                           float outer, boolean shadow, @Nullable ResourceLocation cookie) {
    private static final Set<String> KEYS = Set.of("kind", "color", "range", "intensity", "inner", "outer", "shadow",
            "cookie", "index");

    /** Toggle group: {@code HEAD} driver only, {@code SEARCH} any crew. */
    public enum Kind {
        HEAD(0xFFF1D8, 40, 300, 12, 32),
        SEARCH(0xEEF4FF, 96, 4000, 2.5F, 6);

        final int color;
        final float range, intensity, inner, outer;

        Kind(int color, float range, float intensity, float inner, float outer) {
            this.color = color;
            this.range = range;
            this.intensity = intensity;
            this.inner = inner;
            this.outer = outer;
        }

        public int bit() {
            return 1 << ordinal();
        }
    }

    public static List<VehicleLight> of(AbstractVehicle vehicle) {
        return CommonAssetsManager.modelData(vehicle.getVehicleId()).map(ModelData::lights).orElse(List.of());
    }

    public static boolean has(AbstractVehicle vehicle, Kind kind) {
        for (VehicleLight light : of(vehicle)) {
            if (light.kind == kind) {
                return true;
            }
        }
        return false;
    }

    public static VehicleLight read(int node, String name, JsonObject meta) {
        for (String key : meta.keySet()) {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException("light " + name + ": unknown key " + key + "; expected " + KEYS);
            }
        }
        Kind kind = meta.has("kind") ? Kind.valueOf(meta.get("kind").getAsString().toUpperCase(Locale.ROOT)) : Kind.HEAD;
        int color = kind.color;
        if (meta.has("color")) {
            String hex = meta.get("color").getAsString();
            if (!hex.matches("#[0-9a-fA-F]{6}")) {
                throw new IllegalArgumentException("light " + name + ": color " + hex + "; expected #rrggbb");
            }
            color = Integer.parseInt(hex.substring(1), 16);
        }
        float outer = num(meta, "outer", kind.outer, name);
        float inner = num(meta, "inner", Math.min(kind.inner, outer), name);
        if (outer >= 89 || inner > outer) {
            throw new IllegalArgumentException("light " + name + ": need 0 < inner <= outer < 89, got " + inner + ", "
                    + outer);
        }
        JsonElement cookie = meta.get("cookie");
        return new VehicleLight(node, name, kind, color, num(meta, "range", kind.range, name),
                num(meta, "intensity", kind.intensity, name), inner, outer,
                !meta.has("shadow") || meta.get("shadow").getAsBoolean(),
                cookie == null ? null : ResourceLocation.parse(cookie.getAsString()));
    }

    private static float num(JsonObject meta, String key, float fallback, String name) {
        float v = meta.has(key) ? meta.get(key).getAsFloat() : fallback;
        if (!(v > 0) || !Float.isFinite(v)) {
            throw new IllegalArgumentException("light " + name + ": " + key + " " + v + "; expected > 0");
        }
        return v;
    }
}
