package com.wf.combinedassault.uav.fpv.payload;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.wflib.core.api.WFRegistry;

/** Looked up by name from JSON/NBT only; no NeoForge registry sync. */
public final class FpvPayloadTypes {

    public static final WFRegistry<FpvPayloadType> REGISTRY = WFRegistry.create("combinedassault:fpv_payload_type");

    /** Impact-fuzed warhead; the kamikaze drone's reason for existing. */
    public static final FpvPayloadType WARHEAD = new FpvPayloadType(
            CombinedAssault.modLocation("warhead"),
            WarheadPayload::new,
            type -> new WarheadPayload(type, new com.google.gson.JsonObject())
    );

    public static final FpvPayloadType GRENADE_RACK = new FpvPayloadType(
            CombinedAssault.modLocation("grenade_rack"),
            GrenadeRackPayload::new,
            type -> new GrenadeRackPayload(type, new com.google.gson.JsonObject())
    );

    public static final FpvPayloadType SPEAKER = new FpvPayloadType(
            CombinedAssault.modLocation("speaker"),
            SpeakerPayload::new,
            type -> new SpeakerPayload(type, new com.google.gson.JsonObject())
    );

    private FpvPayloadTypes() {
    }

    public static void registerTypes() {
        for (FpvPayloadType type : new FpvPayloadType[]{WARHEAD, GRENADE_RACK, SPEAKER}) {
            REGISTRY.register(type.id(), type);
        }
    }

    @Nullable
    public static FpvPayloadType get(@Nullable ResourceLocation id) {
        return id == null ? null : REGISTRY.get(id);
    }

}
