package com.wf.combinedassault.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.all.AllConfigs;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.item.CamoKitItem;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.combinedassault.vehicle.camo.CamoKind;
import com.wf.combinedassault.vehicle.camo.CamoPlacement;
import com.wf.combinedassault.vehicle.pojo.CamoAction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client sends rays only; server traces them against hull collision cubes and derives bone/offset/normal.
 * Material/size/kind come from the held kit, not the packet: client only influences aim.
 * Stroke = list of rays per packet, not one packet per ray: avoids a packet-per-sample flood.
 */
public class ClientCamoAction implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClientCamoAction> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, "client_camo_action"));
    public static final StreamCodec<FriendlyByteBuf, ClientCamoAction> STREAM_CODEC =
            StreamCodec.of(ClientCamoAction::encode, ClientCamoAction::decode);

    /** Cap on one packet's rays; a stroke segment is dozens. Bounds allocation against an untrusted wire count. */
    private static final int MAX_RAYS = 1024;
    private static final double REACH = 8;
    /**
     * Claimed ray origin vs last-seen eye pos; loose on purpose.
     * Server trails client ~1 tick on a moving hull; this only screens cross-map casts. REACH bounds range.
     */
    private static final double ORIGIN_SLACK = 3;
    private static final float ERASE_RADIUS = 0.6f;

    public CamoAction.Action action = CamoAction.Action.PAINT;
    public int vehicleId;
    public List<Ray> rays = new ArrayList<>();

    /** One aim, in world space. */
    public record Ray(Vec3 from, Vec3 to) {
    }

    public static void encode(FriendlyByteBuf buf, ClientCamoAction message) {
        buf.writeEnum(message.action);
        buf.writeInt(message.vehicleId);
        int count = Math.min(message.rays.size(), MAX_RAYS);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            Ray ray = message.rays.get(i);
            buf.writeVec3(ray.from());
            buf.writeVec3(ray.to());
        }
    }

    public static ClientCamoAction decode(FriendlyByteBuf buf) {
        ClientCamoAction message = new ClientCamoAction();
        message.action = buf.readEnum(CamoAction.Action.class);
        message.vehicleId = buf.readInt();
        int count = Math.min(buf.readVarInt(), MAX_RAYS);
        for (int i = 0; i < count; i++) {
            message.rays.add(new Ray(buf.readVec3(), buf.readVec3()));
        }
        return message;
    }

    public static void handle(ClientCamoAction message, IPayloadContext ctx) {
        if (!(ctx.player() instanceof ServerPlayer player)) {
            return;
        }
        if (message.rays.isEmpty()
                || !(player.level().getEntity(message.vehicleId) instanceof AbstractVehicle vehicle)) {
            return;
        }
        // Box inflate, not flat distance: reach must scale with hull, or carrier deck's far end is unreachable.
        if (!vehicle.getBoundingBox().inflate(REACH).contains(player.getEyePosition())) {
            return;
        }
        ItemStack itemStack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (!(itemStack.getItem() instanceof CamoKitItem)) {
            return;
        }
        CamoAction resolved = switch (message.action) {
            case PAINT -> paint(vehicle, message.rays, player, itemStack);
            case ERASE -> erase(vehicle, message.rays.get(0), player);
            // CLEAR: command-only; no kit triggers it.
            case CLEAR -> null;
        };
        if (resolved == null) {
            return;
        }
        int touched = vehicle.getCamoLayer()
                .apply(player.registryAccess(), resolved, AllConfigs.Cached.camoMaxPieces);
        if (touched <= 0) {
            return;
        }
        if (resolved.action == CamoAction.Action.PAINT) {
            itemStack.hurtAndBreak(touched, player, EquipmentSlot.MAINHAND);
        }
        // Broadcasts resolved (trimmed) action: painter applied nothing locally, needs the trimmed result too.
        vehicle.broadcastCamo(resolved);
    }

    private static CamoAction paint(AbstractVehicle vehicle, List<Ray> rays, ServerPlayer player, ItemStack itemStack) {
        CamoKind kind = CamoKitItem.getKind(itemStack);
        float scale = CamoKitItem.getScale(itemStack);
        float jitter = CamoKitItem.getJitter(itemStack);
        double spacing = AllConfigs.Cached.camoStrokeSpacing;
        RandomSource random = vehicle.getRandom();

        CamoAction action = new CamoAction();
        action.action = CamoAction.Action.PAINT;
        action.vehicleId = vehicle.getId();
        action.palette = new ArrayList<>(List.of(CamoKitItem.getBlockId(itemStack)));
        action.bones = new ArrayList<>();
        action.pieces = new ArrayList<>();
        Map<String, Integer> boneIds = new HashMap<>();
        Vec3 lastPoint = null;
        for (Ray ray : rays) {
            if (!reaches(player, ray)) {
                continue;
            }
            VectorUtil.HitBone hit = VectorUtil.hitHull(vehicle, ray.from(), ray.to(), CamoPlacement.PLATE_NORMAL);
            if (hit == null) {
                continue;
            }
            // Spacing enforced server-side: client no longer knows where its ray landed.
            // Held button with no movement => one piece, not sixty.
            if (lastPoint != null && lastPoint.distanceToSqr(hit.position()) < spacing * spacing) {
                continue;
            }
            lastPoint = hit.position();
            int bone = boneIds.computeIfAbsent(hit.boneName(), name -> {
                action.bones.add(name);
                return action.bones.size() - 1;
            });
            action.pieces.add(CamoPlacement.from(hit, bone, 0, kind, scale, jitter, random));
        }
        return action.pieces.isEmpty() ? null : action;
    }

    private static CamoAction erase(AbstractVehicle vehicle, Ray ray, ServerPlayer player) {
        if (!reaches(player, ray)) {
            return null;
        }
        VectorUtil.HitBone hit = VectorUtil.hitHull(vehicle, ray.from(), ray.to(), CamoPlacement.PLATE_NORMAL);
        if (hit == null) {
            return null;
        }
        CamoAction action = new CamoAction();
        action.action = CamoAction.Action.ERASE;
        action.vehicleId = vehicle.getId();
        action.eraseBone = hit.boneName();
        action.eraseX = (float) hit.offset().x;
        action.eraseY = (float) hit.offset().y;
        action.eraseZ = (float) hit.offset().z;
        action.eraseRadius = ERASE_RADIUS;
        return action;
    }

    private static boolean reaches(ServerPlayer player, Ray ray) {
        return ray.from().distanceToSqr(player.getEyePosition()) <= ORIGIN_SLACK * ORIGIN_SLACK
                && ray.from().distanceToSqr(ray.to()) <= (REACH + 1) * (REACH + 1);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
