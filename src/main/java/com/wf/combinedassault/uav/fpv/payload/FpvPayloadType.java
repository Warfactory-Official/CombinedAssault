package com.wf.combinedassault.uav.fpv.payload;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

/**
 * Two factories: JSON parse on first build, NBT restore on load; only the first has numbers to read.
 *
 * @param id registry id; also the string written to NBT
 */
public record FpvPayloadType(
        ResourceLocation id,
        FromJson fromJson,
        Empty empty
) {

    @FunctionalInterface
    public interface FromJson {
        FpvPayload create(FpvPayloadType type, JsonObject json);
    }

    @FunctionalInterface
    public interface Empty {
        FpvPayload create(FpvPayloadType type);
    }

}
