package com.wf.combinedassault.ads;

import com.mojang.blaze3d.systems.RenderSystem;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.wflib.client.sight.PipLens;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * {@link StationAds} station's {@code optic} through wflib {@link PipLens}: pass from the eye at full ADS, picture on
 * the fitted model's {@code sight_lens} after the level's particles. Lens pose = {@code VehicleGemVisual}'s instance.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class StationPip {
    /** Below this ADS progress no pass runs. */
    private static final float MIN_ADS = 0.02F;
    static final int SIZE = 2048;
    private static final PipLens LENS = new PipLens();
    private static final float[] FLEX = new float[2];
    private static final Vector3f EULER = new Vector3f();
    private static final Vector3f FORWARD = new Vector3f();
    private static final PipLens.Pose POSE = out -> {
        WeaponUnit unit = StationAds.station();
        float pt = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
        Vec3 eye = unit.worldSightEye(pt);
        Quaternionf rot = LocalVehiclePlayer.instance.frameCameraRotation(pt);
        rot.transform(FORWARD.set(0, 0, unit.optic().objective()));
        rot.getEulerAnglesYXZ(EULER);
        out[0] = eye.x + FORWARD.x;
        out[1] = eye.y + FORWARD.y;
        out[2] = eye.z + FORWARD.z;
        out[3] = Math.toDegrees(-EULER.y);
        out[4] = Math.toDegrees(EULER.x);
        out[5] = Math.toDegrees(EULER.z);
    };

    /** This frame's posed lens model (render origin relative), {@link #mount}. */
    @Nullable
    private static GemRenderGltfModel model;
    private static int lens = -1;
    private static final Matrix4f POSED = new Matrix4f();
    private static Vec3i origin = Vec3i.ZERO;

    private static final Matrix4f VIEW = new Matrix4f();
    private static final Matrix4f MODEL = new Matrix4f();
    private static final Matrix4f EYE = new Matrix4f();
    private static final Matrix4f MODEL_F = new Matrix4f();
    private static final Matrix4f F_VIEW = new Matrix4f();
    private static final Matrix4f MODEL_VIEW = new Matrix4f();
    private static final Quaternionf ROT = new Quaternionf();

    private StationPip() {
    }

    static boolean shown() {
        return LENS.shown();
    }

    static float magnification() {
        return LENS.magnification();
    }

    /** {@code VehicleGemVisual}: a part's mount instance this frame, pose relative to {@code renderOrigin}. */
    public static void mount(PartUnit<?> part, GemRenderGltfModel gltf, Matrix4fc pose, Vec3i renderOrigin) {
        WeaponUnit unit = StationAds.station();
        if (part != unit || LENS.optic() == null) {
            return;
        }
        int slot = gltf.layout().nodeTable().slotOfName(unit.lensNode());
        if (slot >= 0) {
            model = gltf;
            lens = slot;
            POSED.set(pose);
            origin = renderOrigin;
        }
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Pre e) {
        Minecraft mc = Minecraft.getInstance();
        WeaponUnit unit = StationAds.station();
        float pt = e.getPartialTick().getGameTimeDeltaPartialTick(false);
        boolean aiming = StationAds.aiming(unit);
        LENS.select(unit, aiming ? unit.optic() : null, null, null);
        LENS.frame(e.getPartialTick(), aiming && mc.player != null && StationAds.progress(pt) >= MIN_ADS, SIZE,
                mc.player, POSE);
    }

    @SubscribeEvent
    public static void onStage(RenderLevelStageEvent e) {
        if (e.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        GemRenderGltfModel m = model;
        model = null;
        WeaponUnit unit = StationAds.station();
        if (m == null || unit == null || LENS.optic() == null) {
            LENS.miss();
            return;
        }
        Camera cam = e.getCamera();
        Vec3 c = cam.getPosition();
        VIEW.rotation(cam.rotation().conjugate(ROT));
        MODEL.translation((float) (origin.getX() - c.x), (float) (origin.getY() - c.y), (float) (origin.getZ() - c.z))
                .mul(POSED);
        Vec3 eye = unit.worldSightEye(e.getPartialTick().getGameTimeDeltaPartialTick(false));
        EYE.translation((float) (eye.x - c.x), (float) (eye.y - c.y), (float) (eye.z - c.z)).rotate(cam.rotation());
        F_VIEW.set(VIEW).mul(EYE);
        MODEL_F.set(EYE).invertAffine().mul(MODEL);
        MODEL_VIEW.set(VIEW).mul(MODEL);
        LENS.draw(m, lens, MODEL_F, F_VIEW, MODEL_VIEW, RenderSystem.getProjectionMatrix(), FLEX);
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        LENS.close();
    }
}
