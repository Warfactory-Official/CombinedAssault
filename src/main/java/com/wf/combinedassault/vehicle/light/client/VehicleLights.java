package com.wf.combinedassault.vehicle.light.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.wf.combinedassault.client.model.VehicleModelInstance;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.hit.ModelData;
import com.wf.combinedassault.vehicle.hit.ModelMounts;
import com.wf.combinedassault.vehicle.light.LightToggleMessage;
import com.wf.combinedassault.vehicle.light.VehicleLight;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.pojo.AimContext;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.gemrender.light.LightCookie;
import com.wf.gemrender.light.LightSink;
import com.wf.gemrender.light.Lights;
import com.wf.wflib.nvg.client.NvgIllumination;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Light nodes -> GemRender spots at the last published pose (one frame behind the turret); not yet drawn => rest
 * pose. Light node absent from the drawn model (part mount graft) => nearest drawn ancestor's pose x rest offset.
 * Key: driver -> {@code HEAD}, other crew -> {@code SEARCH}; a vehicle with one group -> that group.
 * <p>{@link AbstractVehicle#irSights()} stations -> IR sight illuminator along the gun; own station: wflib draws
 * it at the sight camera.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class VehicleLights {

    public static final KeyMapping TOGGLE = new KeyMapping("key.combinedassault.toggle_lights.desc",
            KeyConflictContext.IN_GAME, KeyModifier.NONE, InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_BACKSLASH,
            "key.category.combinedassault");

    private static final Map<ResourceLocation, LightCookie> COOKIES = new HashMap<>();
    private static final Matrix4f WORLD = new Matrix4f();
    private static final Matrix4f NODE = new Matrix4f();
    /** Harness: spots submitted by the last collect. */
    public static int submitted;

    private VehicleLights() {
    }

    public static void register() {
        Lights.register(VehicleLights::collect);
    }

    @SubscribeEvent
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE);
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        while (TOGGLE.consumeClick()) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player == null || player.isSpectator() || !(player.getVehicle() instanceof AbstractVehicle vehicle)) {
                continue;
            }
            boolean head = VehicleLight.has(vehicle, VehicleLight.Kind.HEAD);
            boolean search = VehicleLight.has(vehicle, VehicleLight.Kind.SEARCH);
            if (!head && !search) {
                continue;
            }
            boolean driver = vehicle.getControllingPassenger() == player;
            VehicleLight.Kind kind = head && (driver || !search) ? VehicleLight.Kind.HEAD : VehicleLight.Kind.SEARCH;
            PacketDistributor.sendToServer(new LightToggleMessage(vehicle.getId(), kind));
        }
    }

    private static void collect(LightSink sink, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        submitted = 0;
        if (mc.level == null) {
            return;
        }
        // Camera-relative: float precision far from the origin.
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof AbstractVehicle vehicle)) {
                continue;
            }
            irSights(sink, vehicle, mc.player == null ? -1 : mc.player.getId());
            if (vehicle.lightsOn() == 0) {
                continue;
            }
            int on = vehicle.lightsOn();
            ModelData model = CommonAssetsManager.modelData(vehicle.getVehicleId()).orElse(null);
            if (model == null) {
                continue;
            }
            Vec3 centre = vehicle.centerOffset;
            WORLD.translation((float) (Mth.lerp(partialTick, vehicle.xo, vehicle.getX()) - camera.x),
                            (float) (Mth.lerp(partialTick, vehicle.yo, vehicle.getY()) - camera.y),
                            (float) (Mth.lerp(partialTick, vehicle.zo, vehicle.getZ()) - camera.z))
                    .translate((float) centre.x, (float) centre.y, (float) centre.z)
                    .rotate(vehicle.rotYXZ(partialTick))
                    .translate((float) -centre.x, (float) -centre.y, (float) -centre.z);
            for (VehicleLight light : model.lights()) {
                if ((on & light.kind().bit()) == 0) {
                    continue;
                }
                node(vehicle.getVehicleModelInstance(), model.mounts().links(), light.node(), NODE);
                WORLD.mul(NODE, NODE);
                Vector3f at = NODE.getTranslation(new Vector3f());
                sink.spot(camera.x + at.x, camera.y + at.y, camera.z + at.z,
                        NODE.transformDirection(new Vector3f(0, 0, 1)).normalize(),
                        NODE.transformDirection(new Vector3f(0, 1, 0)).normalize(), light.range(), light.inner(),
                        light.outer(), light.color(), light.intensity(),
                        light.cookie() == null ? null : COOKIES.computeIfAbsent(light.cookie(), Lights::cookie),
                        light.shadow());
                submitted++;
            }
        }
    }

    private static void irSights(LightSink sink, AbstractVehicle vehicle, int self) {
        for (int bits = vehicle.irSights(); bits != 0; bits &= bits - 1) {
            int index = Integer.numberOfTrailingZeros(bits);
            if (index >= vehicle.seats.size()) {
                return;
            }
            AbstractVehicle.Seat seat = vehicle.seats.get(index);
            if (seat.passengerId != self && seat.partUnit instanceof WeaponUnit unit) {
                AimContext aim = unit.aimContext();
                NvgIllumination.sight(sink, unit.worldCameraPosition(),
                        VectorUtil.rotToVec(aim.direction.x, aim.direction.y).normalize());
            }
        }
    }

    /** Model-space frame of glTF node {@code node}. */
    private static void node(VehicleModelInstance instance, List<ModelMounts.Link> links, int node, Matrix4f out) {
        out.set(links.get(node).world());
        if (instance == null || !instance.posed()) {
            return;
        }
        for (int at = node; at >= 0; at = links.get(at).parent()) {
            int bone = instance.boneIndex(links.get(at).name());
            if (bone >= 0) {
                out.set(instance.globalTransform(bone)).mul(new Matrix4f(links.get(at).world()).invert())
                        .mul(links.get(node).world());
                return;
            }
        }
    }
}
