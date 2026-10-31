package com.wf.combinedassault.uav.fpv.payload;

import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.part.data.PartUnitData;

/** Resolves payload type here, not per-instance: a pack typo logs once at data load, not once per drone. */
public class PayloadUnitData extends PartUnitData {

    @Nullable
    private final FpvPayloadType payloadType;
    private final JsonObject payloadConfig;
    private final Vec3 attachOffset;

    public PayloadUnitData(PayloadUnitPojo pojo) {
        super(pojo);
        this.attachOffset = pojo.attachOffset == null ? Vec3.ZERO : pojo.attachOffset;
        this.payloadConfig = pojo.payload == null ? new JsonObject() : pojo.payload;
        String type = GsonHelper.getAsString(payloadConfig, "type", "");
        ResourceLocation typeId = type.isEmpty() ? null : ResourceLocation.tryParse(type);
        FpvPayloadType resolved = FpvPayloadTypes.get(typeId);
        if (typeId != null && resolved == null) {
            CombinedAssault.LOGGER.warn("Unknown FPV payload type {} on hardpoint {}", typeId, pojo.id);
        }
        this.payloadType = resolved;
    }

    /** A fresh payload as configured, or null for a hardpoint that ships empty. */
    @Nullable
    public FpvPayload createPayload() {
        return payloadType == null ? null : payloadType.fromJson().create(payloadType, payloadConfig);
    }

    public Vec3 getAttachOffset() {
        return attachOffset;
    }

}
