package com.wf.combinedassault.uav.fpv.payload;

import com.google.gson.JsonObject;
import com.wf.combinedassault.CombinedAssault;
import com.wf.wflib.core.api.WFRegistry;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/** Looked up by name from JSON/NBT only; no NeoForge registry sync. */
public final class FpvPayloadTypes {

    public static final WFRegistry<FpvPayloadType> REGISTRY = WFRegistry.create("combinedassault:fpv_payload_type");

    /** Impact-fuzed warhead; the kamikaze drone's reason for existing. */
    public static final FpvPayloadType WARHEAD = new FpvPayloadType(
            CombinedAssault.modLocation("warhead"),
            WarheadPayload::new,
            type -> new WarheadPayload(type, new JsonObject())
    );

    public static final FpvPayloadType GRENADE_RACK = new FpvPayloadType(
            CombinedAssault.modLocation("grenade_rack"),
            GrenadeRackPayload::new,
            type -> new GrenadeRackPayload(type, new JsonObject())
    );

    public static final FpvPayloadType SPEAKER = new FpvPayloadType(
            CombinedAssault.modLocation("speaker"),
            SpeakerPayload::new,
            type -> new SpeakerPayload(type, new JsonObject())
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
