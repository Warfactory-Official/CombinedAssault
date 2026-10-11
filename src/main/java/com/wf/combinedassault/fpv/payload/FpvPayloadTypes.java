package com.wf.combinedassault.fpv.payload;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plain map, not a registry: looked up by name from JSON/NBT only, never serialised by raw id or
 * datapack-tagged, so registry sync would buy nothing. New payload kind = one {@link #register} call.
 */
public final class FpvPayloadTypes {

    private static final Map<ResourceLocation, FpvPayloadType> TYPES = new LinkedHashMap<>();

    /** Impact-fuzed warhead; the kamikaze drone's reason for existing. */
    public static final FpvPayloadType WARHEAD = register(new FpvPayloadType(
            CombinedAssault.modLocation("warhead"),
            WarheadPayload::new,
            type -> new WarheadPayload(type, new com.google.gson.JsonObject())
    ));

    private FpvPayloadTypes() {
    }

    public static FpvPayloadType register(FpvPayloadType type) {
        TYPES.put(type.id(), type);
        return type;
    }

    @Nullable
    public static FpvPayloadType get(@Nullable ResourceLocation id) {
        return id == null ? null : TYPES.get(id);
    }

    public static Map<ResourceLocation, FpvPayloadType> all() {
        return Collections.unmodifiableMap(TYPES);
    }

}
