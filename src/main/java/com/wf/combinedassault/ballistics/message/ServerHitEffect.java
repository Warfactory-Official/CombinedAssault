package com.wf.combinedassault.ballistics.message;

import com.wf.combinedassault.CombinedAssault;
import com.wf.wflib.armor.api.hull.HullOutcome;
import com.wf.combinedassault.render.fx.HitFx;
import com.wf.combinedassault.ballistics.hit.SurfaceMaterial;
import com.wf.wflib.damage.api.ThreatKind;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.Nullable;

/**
 * One round strike on a vehicle, for its surface effects; every tracking player, hit indicator config regardless.
 *
 * @param outcome null: no armour model answer (no profile, no penetration figure)
 * @param plate   mm met, nominal; 0 = unknown
 */
public record ServerHitEffect(int vehicleId, Vec3 at, Vec3 direction, Vec3 normal, @Nullable HullOutcome outcome,
                              SurfaceMaterial material, ThreatKind kind, float caliber, float plate)
        implements CustomPacketPayload {

    private static final HullOutcome[] OUTCOMES = HullOutcome.values();
    private static final ThreatKind[] KINDS = ThreatKind.values();

    public static final StreamCodec<FriendlyByteBuf, ServerHitEffect> STREAM_CODEC = StreamCodec.of(
            (buf, msg) -> {
                buf.writeVarInt(msg.vehicleId);
                buf.writeDouble(msg.at.x);
                buf.writeDouble(msg.at.y);
                buf.writeDouble(msg.at.z);
                buf.writeVector3f(msg.direction.toVector3f());
                buf.writeVector3f(msg.normal.toVector3f());
                buf.writeByte(msg.outcome == null ? -1 : msg.outcome.ordinal());
                buf.writeByte(msg.material.ordinal());
                buf.writeByte(msg.kind.ordinal());
                buf.writeFloat(msg.caliber);
                buf.writeFloat(msg.plate);
            },
            buf -> {
                int vehicle = buf.readVarInt();
                Vec3 at = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
                Vec3 direction = new Vec3(buf.readVector3f());
                Vec3 normal = new Vec3(buf.readVector3f());
                int outcome = buf.readByte();
                return new ServerHitEffect(vehicle, at, direction, normal, outcome < 0 ? null : OUTCOMES[outcome],
                        SurfaceMaterial.byId(buf.readByte()), KINDS[buf.readByte()], buf.readFloat(), buf.readFloat());
            });
    public static final CustomPacketPayload.Type<ServerHitEffect> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, "hit_effect"));

    public static void handle(ServerHitEffect message, IPayloadContext ctx) {
        ctx.enqueueWork(() -> HitFx.impact(message));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
