package com.wf.combinedassault.network.message;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.all.AllConfigs;
import com.wf.combinedassault.api.event.HitVehicleEvent;
import com.wf.combinedassault.client.gui.VehicleHitIndicatorOverlay;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;

import java.util.UUID;

public class ServerHitVehicleEvent implements CustomPacketPayload {

    public static final StreamCodec<FriendlyByteBuf, ServerHitVehicleEvent> STREAM_CODEC = StreamCodec.of((buf, msg) -> msg.encode(buf), ServerHitVehicleEvent::decode);
    public static final CustomPacketPayload.Type<ServerHitVehicleEvent> TYPE = new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, "hit_vehicle_event"));
    public UUID shooterUuid;
    public int entityId;
    public Vec3 hitPosition;
    public Vec3 hitVector;
    public float caliber;
    public float damage;
    public Component message;

    public ServerHitVehicleEvent() {}

    public ServerHitVehicleEvent(HitVehicleEvent hitVehicleEvent) {
        this.shooterUuid = hitVehicleEvent.shooterUuid;
        this.entityId = hitVehicleEvent.entityId;
        this.hitPosition = hitVehicleEvent.hitPosition;
        this.hitVector = hitVehicleEvent.hitVector;
        this.caliber = hitVehicleEvent.caliber;
        this.damage = hitVehicleEvent.damage;
        this.message = hitVehicleEvent.message;
    }

    public static ServerHitVehicleEvent decode(FriendlyByteBuf buf) {
        ServerHitVehicleEvent serverHitVehicleEvent = new ServerHitVehicleEvent();
        serverHitVehicleEvent.shooterUuid = buf.readUUID();
        serverHitVehicleEvent.entityId = buf.readInt();
        serverHitVehicleEvent.hitPosition = new Vec3(buf.readVector3f());
        serverHitVehicleEvent.hitVector = new Vec3(buf.readVector3f());
        serverHitVehicleEvent.caliber = buf.readFloat();
        serverHitVehicleEvent.damage = buf.readFloat();
        serverHitVehicleEvent.message = Component.translatable(buf.readUtf());
        return serverHitVehicleEvent;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(shooterUuid);
        buf.writeInt(entityId);
        buf.writeVector3f(hitPosition.toVector3f());
        buf.writeVector3f(hitVector.toVector3f());
        buf.writeFloat(caliber);
        buf.writeFloat(damage);
        buf.writeUtf(message.getString());
    }

    public static void handle(ServerHitVehicleEvent message, IPayloadContext ctxSupplier) {
        if (!AllConfigs.common.hitIndicator.get()) {
            return;
        }
        ctxSupplier.enqueueWork(() -> {
            if (message.shooterUuid.equals(LocalVehiclePlayer.instance.getPlayer().getUUID())) {
                VehicleHitIndicatorOverlay.lastHitTime = System.currentTimeMillis();
                if (!VehicleHitIndicatorOverlay.events.isEmpty() && VehicleHitIndicatorOverlay.events.get(0).entityId != message.entityId) {
                    VehicleHitIndicatorOverlay.events.clear();
                }
                VehicleHitIndicatorOverlay.events.add(message);
                if (VehicleHitIndicatorOverlay.events.size() > 128) {
                    VehicleHitIndicatorOverlay.events.remove(0);
                }
            }
        });
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

}
