package com.wf.combinedassault.weapon.kit.client;

import com.wf.combinedassault.ballistics.hit.ModelData;
import com.wf.combinedassault.ballistics.hit.ModelMounts;
import com.wf.combinedassault.ballistics.hit.NodeRole;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.render.model.GraftedNodes;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.weapon.AbstractVehicleWeapon;
import com.wf.combinedassault.weapon.kit.HardwareKit;
import com.wf.combinedassault.weapon.kit.HardwareKits;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.gemrender.asset.GemRenderModels;
import com.wf.gemrender.asset.ModelCache;
import com.wf.gemrender.direct.DirectPass;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.wflib.belt.api.BeltChain;
import com.wf.wflib.belt.api.client.BeltRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Fitted kits' {@link HardwareKit#belt}: MA {@code BeltView}, boxed case. {@link NodeRole#BELT} 0 -> 1; box mouth off
 * its rest by more than {@link #SEATED} => stub out of the mouth. Sim space = camera-relative world. Render thread.
 */
public final class KitBelts {
    private static final int STUB = 4;
    /** m. */
    private static final float SEATED = 0.003F;
    /** Feed stroke / fire interval; pending advance cap, pitches. */
    private static final float STROKE = 0.5F, LAG = 2;
    /** Count ramp-in after a reset, s. */
    private static final float DRAG_S = 0.3F;
    private static final long EPOCH = System.nanoTime();
    private static final Map<PartUnit<?>, State> STATES = new WeakHashMap<>();
    private static final Map<ResourceLocation, ModelCache.Handle<GemRenderGltfModel>> LINKS = new HashMap<>();
    private static final Matrix4f NODE = new Matrix4f(), FRAME = new Matrix4f(), WORLD = new Matrix4f();
    private static final Vector3f FEED = new Vector3f(), OUT = new Vector3f(), AXIS = new Vector3f(),
            END = new Vector3f(), INTO = new Vector3f(), REST = new Vector3f();
    private static final Vector3f GRAVITY = new Vector3f(0, -9.81F, 0);

    private KitBelts() {
    }

    private static final class State {
        @Nullable
        BeltChain chain;
        HardwareKit.Belt belt;
        Vec3 origin = Vec3.ZERO;
        float at, shift, resetAt;
        int rounds;
        boolean seated;
    }

    /** {@code pose}: model -> camera-relative world. */
    public static void draw(AbstractVehicle vehicle, Matrix4fc pose, int light, float partialTick) {
        ModelData model = null;
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (!(part instanceof WeaponUnit unit) || part.isDetached()) {
                continue;
            }
            HardwareKit kit = HardwareKits.get(unit.kit());
            if (kit == null || kit.belt() == null) {
                continue;
            }
            if (model == null) {
                model = CommonAssetsManager.modelData(vehicle.getVehicleId()).orElse(null);
                if (model == null) {
                    return;
                }
            }
            draw(vehicle, model, unit, kit, pose, light, partialTick);
        }
    }

    private static void draw(AbstractVehicle vehicle, ModelData model, WeaponUnit unit, HardwareKit kit,
                             Matrix4fc pose, int light, float partialTick) {
        State state = STATES.computeIfAbsent(unit, u -> new State());
        int feed = -1, spawn = -1;
        String kitId = kit.id().toString();
        for (ModelMounts.Mount mount : model.mounts().mounts()) {
            if (mount.role() == NodeRole.BELT && kitId.equals(mount.kit()) && mount.target().equals(unit.getId())) {
                if (mount.index() == 0) {
                    feed = mount.node();
                } else {
                    spawn = mount.node();
                }
            }
        }
        int rounds = unit.getCurrentWeapon().map(AbstractVehicleWeapon::getRemainAmmo).orElse(0);
        GemRenderGltfModel link = LINKS.computeIfAbsent(kit.belt().link(), GemRenderModels::handle).get();
        if (link == null || feed < 0 || spawn < 0 || rounds == 0) {
            state.chain = null;
            return;
        }
        GraftedNodes.node(vehicle, model, spawn, false, partialTick, NODE).getTranslation(REST);
        GraftedNodes.node(vehicle, model, spawn, true, partialTick, NODE);
        boolean seated = NODE.getTranslation(END).distance(REST) < SEATED;
        float now = (System.nanoTime() - EPOCH) * 1e-9F;
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        if (state.chain == null || state.belt != kit.belt() || state.seated != seated || now < state.at) {
            state.chain = new BeltChain(kit.belt().pitch());
            state.belt = kit.belt();
            state.origin = camera;
            state.seated = seated;
            state.at = now;
            state.resetAt = now;
            state.shift = 0;
            state.rounds = rounds;
        }
        float dt = now - state.at;
        float rpm = unit.getCurrentWeapon().map(w -> 60000F / w.getShootInterval()).orElse(0F);
        state.shift = rounds < state.rounds && seated ? Math.min(state.shift + state.rounds - rounds, LAG)
                : rounds > state.rounds ? 0 : Math.max(state.shift - dt * rpm / (60 * STROKE), 0);
        state.rounds = rounds;
        FRAME.set(pose).mul(NODE).getTranslation(END);
        FRAME.getColumn(0, INTO).normalize().negate();
        if (seated) {
            FRAME.set(pose).mul(GraftedNodes.node(vehicle, model, feed, true, partialTick, NODE));
        }
        FRAME.getTranslation(FEED);
        FRAME.getColumn(0, OUT).normalize();
        FRAME.getColumn(2, AXIS).normalize();
        WORLD.translation((float) (camera.x - state.origin.x), (float) (camera.y - state.origin.y),
                (float) (camera.z - state.origin.z));
        float ramp = Math.min(1F, (now - state.resetAt) / DRAG_S);
        int count = Math.min(rounds, Math.round((seated ? rounds : STUB) * ramp));
        state.chain.update(FEED, OUT, AXIS, List.of(), seated ? END : null, seated ? INTO : null, count, GRAVITY, WORLD,
                dt);
        state.at = now;
        BeltRenderer.submit(link, state.chain, state.chain.links(), state.shift, FRAME.identity(), null, light,
                OverlayTexture.NO_OVERLAY, 0xFFFFFFFF, DirectPass.LEVEL);
    }
}
