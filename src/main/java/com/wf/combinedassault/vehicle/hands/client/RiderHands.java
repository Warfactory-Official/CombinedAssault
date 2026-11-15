package com.wf.combinedassault.vehicle.hands.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.norwood.ahf.client.body.AhfBody;
import com.norwood.ahf.rig.ArmSolver;
import com.wf.combinedassault.ballistics.hit.ModelData;
import com.wf.combinedassault.dynamics.part.PartClipTracks;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.render.animation.context.DriverControls;
import com.wf.combinedassault.render.model.GraftedNodes;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.hands.RiderHand;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.part.data.WeaponUnitData;
import dev.kosmx.playerAnim.core.util.Pair;
import dev.kosmx.playerAnim.impl.IAnimatedPlayer;
import dev.kosmx.playerAnim.impl.animation.AnimationApplier;
import com.wf.gemrender.direct.DirectPass;
import com.wf.wflib.view.api.ArmLimits;
import com.wf.wflib.view.api.ArmViewPose;
import com.wf.wflib.view.api.PlayerArms;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.util.RandomSource;

/**
 * Seat rider arms / legs onto {@link RiderHand} nodes. Third person: AHF {@link ArmSolver} on the drawn body. Own
 * rider in OPERATOR view: wflib {@link PlayerArms} by {@link ArmViewPose} + own body sans head / arms, neck on the
 * view shoulders ({@link #level}). Action contact live => that limb blends rest -> action over {@link #REACH_S}, back
 * over {@link #RELEASE_S}. Limb {@code l}: 0 right arm, 1 left arm, 2 right leg, 3 left leg. Render thread only.
 */
public final class RiderHands {
    public static final float REACH_S = 0.12f;
    public static final float RELEASE_S = 0.16f;
    /** Hand stays on a stopped lever. */
    public static final float LINGER_S = 0.05f;

    private static final ArmSolver[] IK = {new ArmSolver(), new ArmSolver(), new ArmSolver(), new ArmSolver()};
    /** Leg longer than the vanilla 12 px when the pedal needs it, up to this factor. */
    private static final float MAX_STRETCH = 1.3F;
    private static final Map<ModelPart, ArmSolver.Landmarks> SEGMENTS = new WeakHashMap<>();
    private static final Map<Player, Weights> WEIGHTS = new WeakHashMap<>();
    private static final Matrix4f ENTITY_INVERSE = new Matrix4f();
    private static final Matrix4f NODE = new Matrix4f();
    private static final Matrix4f ARM = new Matrix4f();
    private static final Quaternionf ROTATION = new Quaternionf();
    private static final Vector3f REST = new Vector3f();
    private static final Vector3f ACTION = new Vector3f();
    private static final Vector3f TARGET = new Vector3f();
    private static final float[] BEND = new float[2];
    private static final int HAS_REST = 1, HAS_ACTION = 2;
    /** Right elbow pole, PlayerModel space (+Y down, -Z forward, right arm at -X): outward, down, a little back. */
    private static final Vector3f ELBOW_POLE = new Vector3f(-0.7F, 1, 0.15F).normalize();
    /** Right knee pole, PlayerModel space: up, forward, a little outward. */
    private static final Vector3f KNEE_POLE = new Vector3f(-0.15F, -1, -0.6F).normalize();
    private static final Vector3f POLE = new Vector3f();
    private static final Vector3f JOINT_SHOULDER = new Vector3f();
    private static final Vector3f JOINT_ELBOW = new Vector3f();
    private static final Vector3f JOINT_WRIST = new Vector3f();
    /** First person, no rest grip: wrist off the shoulder (torso frame, right side), hand in the lap. */
    private static final Vector3f LAP = new Vector3f(0.05F, -0.45F, -0.25F);
    private static final Quaternionf TORSO = new Quaternionf();
    private static final Quaternionf TORSO_INVERSE = new Quaternionf();
    private static final Vector3f SHOULDER = new Vector3f();
    private static final Vector3f ELBOW = new Vector3f();
    private static final Matrix4f UPPER = new Matrix4f();
    private static final Matrix4f LOWER = new Matrix4f();
    private static final Matrix4f VIEW = new Matrix4f();
    /** View arm radius at the shoulder joint: body neck this far over {@link ArmViewPose#SHOULDER}. */
    private static final float SHOULDER_TOP = 0.06F;
    /** Own body reclined about the hips (rad): chest out of the view, hips (pedal reach) kept. */
    private static final float RECLINE = (float) Math.toRadians(25);
    private static final Quaternionf RECLINE_Q = new Quaternionf();
    private static final Vector3f HIP = new Vector3f();
    private static final Side[] SIDES = {new Side(HumanoidArm.RIGHT, false), new Side(HumanoidArm.LEFT, false),
            new Side(HumanoidArm.RIGHT, true), new Side(HumanoidArm.LEFT, true)};
    private static final PoseStack BODY = new PoseStack();
    /** Own body draw in progress: camera-relative neck goal. */
    private static final Vector3f NECK = new Vector3f();
    private static boolean ownBody;
    @Nullable
    private static LivingEntity armed;

    private RiderHands() {
    }

    private static final class Weights {
        final float[] weight = new float[4];
        @Nullable
        final RiderHand[] action = new RiderHand[4];
        final long[] nanos = new long[4];
    }

    private static final class Side {
        final HumanoidArm arm;
        final boolean leg;
        boolean active;
        ArmSolver.Landmarks skin;
        final Matrix4f upper = new Matrix4f();
        final Matrix4f lower = new Matrix4f();
        final Matrix4f upperPart = new Matrix4f();
        final Matrix4f lowerPart = new Matrix4f();

        Side(HumanoidArm arm, boolean leg) {
            this.arm = arm;
            this.leg = leg;
        }

        void apply() {
            if (active) {
                ArmSolver.partFrames(skin, upper, lower, upperPart, lowerPart);
                if (leg) {
                    AhfBody.overrideLeg(arm, upperPart, lowerPart);
                } else {
                    AhfBody.overrideArm(arm, upperPart, lowerPart);
                }
            }
        }
    }

    /** Some seat of {@code vehicle} with a {@link RiderHand#SHIFT} contact carries a player. */
    public static boolean shiftHand(AbstractVehicle vehicle) {
        List<RiderHand> hands = hands(vehicle);
        if (hands.isEmpty()) {
            return false;
        }
        for (AbstractVehicle.Seat seat : vehicle.seats) {
            if (vehicle.level().getEntity(seat.passengerId) instanceof Player) {
                String kit = kit(seat.partUnit);
                for (RiderHand hand : hands) {
                    if (!hand.foot() && RiderHand.SHIFT.equals(hand.action()) && applies(hand, seat.partUnit.getId(), kit)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Own rider in OPERATOR view (or SCOPE on iron sights) on a seat with contacts. */
    private static boolean firstPerson(Player player) {
        LocalVehiclePlayer local = LocalVehiclePlayer.instance();
        return player == local.getPlayer() && local.seat != null
                && (local.viewType == LocalVehiclePlayer.ViewType.OPERATOR
                || local.viewType == LocalVehiclePlayer.ViewType.SCOPE && local.seat.partUnit instanceof WeaponUnit unit
                && unit.getOpticalSightType() == WeaponUnitData.OpticalSightType.OPERATOR)
                && player.getVehicle() instanceof AbstractVehicle vehicle
                && vehicle.getDetachedBodyAnchor(player) == null && hasContacts(vehicle, local.seat.partUnit);
    }

    /** Own first person on contacts: vanilla hands cancelled (arms drawn in the level, {@link #level}). */
    public static boolean firstPerson(RenderHandEvent event) {
        Player player = Minecraft.getInstance().player;
        if (player == null || !firstPerson(player)) {
            return false;
        }
        event.setCanceled(true);
        return true;
    }

    /**
     * Own first-person arms (AFTER_CUTOUT_BLOCKS) + body (AFTER_ENTITIES: model-view carries the camera rotation only
     * from there), world light, depth against the hull. {@link ArmViewPose} in the torso frame = camera eye + drawn
     * seat yaw (+ hull tilt), not the look. Body = third-person draw shifted so its neck sits {@link #SHOULDER_TOP}
     * over the view shoulders (view shoulders 0.13 m nearer the eye than the body's), then {@link #RECLINE}d about the
     * hips.
     */
    public static void level(RenderLevelStageEvent event) {
        boolean armPass = event.getStage() == RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS;
        if (!armPass && event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            return;
        }
        Player player = Minecraft.getInstance().player;
        PlayerArms arms = PlayerArms.get();
        if (!(player instanceof AbstractClientPlayer local) || arms == null || !firstPerson(player)) {
            return;
        }
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        AbstractVehicle vehicle = (AbstractVehicle) player.getVehicle();
        PartUnit<?> part = LocalVehiclePlayer.instance().seat.partUnit;
        Vec3 camera = event.getCamera().getPosition();
        vehicle.rotYXZ(pt).get(TORSO).rotateY((float) Math.toRadians(vehicle.getViewYRot(pt)))
                .rotateY((float) (Math.PI - Math.toRadians(part.getSeatRot(pt))));
        TORSO.conjugate(TORSO_INVERSE);
        int light = Minecraft.getInstance().getEntityRenderDispatcher().getPackedLightCoords(player, pt);
        if (armPass) {
            arms(arms, local, vehicle, part, camera, pt, light);
        } else if (!player.isInvisible()) {
            body(local, camera, pt, light);
        }
    }

    private static void arms(PlayerArms arms, AbstractClientPlayer player, AbstractVehicle vehicle, PartUnit<?> part,
                             Vec3 camera, float pt, int light) {
        boolean any = false;
        for (int s = 0; s < 2; s++) {
            boolean left = s == 1;
            int targets = targets(player, vehicle, part, s, camera, pt);
            if (targets == 0) {
                continue;
            }
            ArmViewPose.shoulder(left, SHOULDER);
            if ((targets & HAS_REST) != 0) {
                REST.rotate(TORSO_INVERSE);
            } else {
                REST.set(SHOULDER).add(left ? -LAP.x : LAP.x, LAP.y, LAP.z);
            }
            if ((targets & HAS_ACTION) != 0) {
                ACTION.rotate(TORSO_INVERSE);
                float w = weight(player, s);
                REST.lerp(ACTION, w * w * (3 - 2 * w), TARGET);
            } else {
                TARGET.set(REST);
            }
            float twist = ArmViewPose.solve(TARGET, null, SHOULDER, left ? ArmViewPose.LEFT_POLE : ArmViewPose.RIGHT_POLE,
                    arms.lengths, UPPER, LOWER, ELBOW);
            arms.pose(s, UPPER, LOWER, twist, 0, ArmLimits.DEFAULT.twist());
            any = true;
        }
        if (any) {
            VIEW.rotation(TORSO);
            arms.submit(VIEW, light, 0xFFFFFFFF, player, DirectPass.LEVEL);
        }
    }

    private static void body(AbstractClientPlayer player, Vec3 camera, float pt, int light) {
        ArmViewPose.shoulder(false, SHOULDER);
        TORSO.transform(NECK.set(0, SHOULDER.y + SHOULDER_TOP, SHOULDER.z));
        TORSO.mul(RECLINE_Q.rotationX(RECLINE), RECLINE_Q).mul(TORSO_INVERSE);
        BODY.setIdentity();
        BODY.translate(Mth.lerp(pt, player.xo, player.getX()) - camera.x, Mth.lerp(pt, player.yo, player.getY()) - camera.y,
                Mth.lerp(pt, player.zo, player.getZ()) - camera.z);
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        ownBody = true;
        try {
            Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(player)
                    .render(player, Mth.lerp(pt, player.yRotO, player.getYRot()), pt, BODY, buffers, light);
        } finally {
            ownBody = false;
        }
        buffers.endBatch();
    }

    /** Own first-person body draw: layers but armour skipped; armour head / arms hidden. */
    public static boolean ownBody() {
        return ownBody;
    }

    /** Own body: head + arms off (view arms drawn instead). */
    public static void hideHeadAndArms(HumanoidModel<?> model) {
        model.head.visible = false;
        model.hat.visible = false;
        model.rightArm.visible = false;
        model.leftArm.visible = false;
    }

    /**
     * Before the model draw; false => no override. {@code pose}: model -> camera-relative world; own body draw
     * ({@link #level}) => shifted onto {@link #NECK} here.
     */
    public static boolean prepare(Player player, PlayerModel<?> model, PoseStack pose, float partialTick) {
        armed = null;
        for (Side side : SIDES) {
            side.active = false;
        }
        if (ownBody) {
            hideHeadAndArms(model);
            model.rightSleeve.visible = false;
            model.leftSleeve.visible = false;
            Matrix4f m = pose.last().pose();
            m.transformPosition(model.head.x / 16F, model.head.y / 16F, model.head.z / 16F, TARGET);
            m.translateLocal(NECK.x - TARGET.x, NECK.y - TARGET.y, NECK.z - TARGET.z);
            m.transformPosition(model.body.x / 16F, model.body.y / 16F + 0.75F, model.body.z / 16F, HIP);
            m.translateLocal(-HIP.x, -HIP.y, -HIP.z).rotateLocal(RECLINE_Q).translateLocal(HIP);
            pose.last().normal().rotateLocal(RECLINE_Q);
        }
        if (player.isInvisible() || !(player.getVehicle() instanceof AbstractVehicle vehicle)
                || vehicle.getDetachedBodyAnchor(player) != null) {
            return false;
        }
        PartUnit<?> part = hands(vehicle).isEmpty() ? null : seatPart(vehicle, player);
        if (part == null) {
            return false;
        }
        ENTITY_INVERSE.set(pose.last().pose()).invertAffine();
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        boolean any = false;
        for (int l = 0; l < 4; l++) {
            boolean left = (l & 1) == 1;
            boolean leg = l >= 2;
            int targets = targets(player, vehicle, part, l, camera, partialTick);
            ModelPart limb = leg ? left ? model.leftLeg : model.rightLeg : left ? model.leftArm : model.rightArm;
            if (targets == 0 || !limb.visible || limb.skipDraw) {
                continue;
            }
            Side side = SIDES[l];
            side.skin = SEGMENTS.computeIfAbsent(limb, RiderHands::landmarks);
            bend(player, (left ? "left" : "right") + (leg ? "Leg" : "Arm"));
            if ((targets & HAS_REST) != 0) {
                ENTITY_INVERSE.transformPosition(REST);
            } else {
                authoredWrist(limb, side.skin, REST);
            }
            if ((targets & HAS_ACTION) != 0) {
                ENTITY_INVERSE.transformPosition(ACTION);
                float w = weight(player, l);
                REST.lerp(ACTION, w * w * (3 - 2 * w), TARGET);
            } else {
                TARGET.set(REST);
            }
            solve(l, limb, side, left, leg);
            side.active = true;
            any = true;
        }
        if (any) {
            armed = player;
        }
        return any;
    }

    /**
     * Limb {@code s} of {@code player} on {@code part}: action weight advanced; camera-relative world {@code REST}
     * ({@link #HAS_REST}) / {@code ACTION} ({@link #HAS_ACTION}). 0 => limb not driven.
     */
    private static int targets(Player player, AbstractVehicle vehicle, PartUnit<?> part, int s, Vec3 camera, float pt) {
        boolean left = (s & 1) == 1;
        boolean foot = s >= 2;
        String partId = part.getId();
        String kit = kit(part);
        RiderHand rest = null;
        RiderHand live = null;
        for (RiderHand hand : hands(vehicle)) {
            if (hand.left() != left || hand.foot() != foot || !applies(hand, partId, kit)) {
                continue;
            }
            if (hand.action() == null) {
                if (rest == null || hand.kit() != null) {
                    rest = hand;
                }
            } else if (live == null && live(vehicle, part, hand, pt)) {
                live = hand;
            }
        }
        Weights weights = WEIGHTS.computeIfAbsent(player, p -> new Weights());
        long now = System.nanoTime();
        float dt = weights.nanos[s] == 0 ? 0 : Mth.clamp((now - weights.nanos[s]) * 1e-9f, 0, 0.1f);
        weights.nanos[s] = now;
        if (live != null) {
            weights.action[s] = live;
            weights.weight[s] = Math.min(1, weights.weight[s] + dt / REACH_S);
        } else {
            weights.weight[s] = Math.max(0, weights.weight[s] - dt / RELEASE_S);
        }
        RiderHand action = weights.weight[s] > 0 && weights.action[s] != null
                && applies(weights.action[s], partId, kit) ? weights.action[s] : null;
        int out = 0;
        if (rest != null) {
            worldPoint(vehicle, rest, camera, pt, REST);
            out |= HAS_REST;
        }
        if (action != null) {
            worldPoint(vehicle, action, camera, pt, ACTION);
            out |= HAS_ACTION;
        }
        return out;
    }

    private static float weight(Player player, int s) {
        Weights weights = WEIGHTS.get(player);
        return weights == null ? 0 : weights.weight[s];
    }

    /**
     * {@link ArmSolver} on the unbent limb with {@link #ELBOW_POLE} / {@link #KNEE_POLE} (clip bend ignored: grips want
     * elbows out-down). Leg short of {@code TARGET} => both bones stretched along the leg, <= {@link #MAX_STRETCH}.
     */
    private static void solve(int s, ModelPart limb, Side side, boolean left, boolean leg) {
        ArmSolver.Landmarks lm = side.skin;
        ARM.translation(limb.x / 16F, limb.y / 16F, limb.z / 16F).rotateZYX(limb.zRot, limb.yRot, limb.xRot);
        ARM.transformPosition(lm.centerX() / 16F, lm.shoulderY() / 16F, lm.centerZ() / 16F, JOINT_SHOULDER);
        ARM.transformPosition(lm.centerX() / 16F, lm.elbowY() / 16F, lm.centerZ() / 16F, JOINT_ELBOW);
        ARM.transformPosition(lm.centerX() / 16F, lm.wristY() / 16F, lm.centerZ() / 16F, JOINT_WRIST);
        float stretch = 1;
        if (leg) {
            float reach = 0.97F * (JOINT_SHOULDER.distance(JOINT_ELBOW) + JOINT_ELBOW.distance(JOINT_WRIST));
            stretch = Mth.clamp(JOINT_SHOULDER.distance(TARGET) / reach, 1, MAX_STRETCH);
            JOINT_ELBOW.sub(JOINT_SHOULDER).mul(stretch).add(JOINT_SHOULDER);
            JOINT_WRIST.sub(JOINT_SHOULDER).mul(stretch).add(JOINT_SHOULDER);
        }
        side.upper.set(ARM).setTranslation(JOINT_SHOULDER);
        side.lower.set(ARM).setTranslation(JOINT_ELBOW);
        POLE.set(leg ? KNEE_POLE : ELBOW_POLE).mul(left ? -1 : 1, 1, 1);
        IK[s].twoBone(side.upper, side.lower, JOINT_WRIST, TARGET, JOINT_SHOULDER, POLE);
        if (stretch > 1) {
            side.upper.scale(1, stretch, 1);
            side.lower.scale(1, stretch, 1);
        }
    }

    public static void apply() {
        for (Side side : SIDES) {
            side.apply();
        }
    }

    /** Armour layer start: solved arms back on for {@code wearer}. */
    public static void armour(LivingEntity wearer) {
        if (armed == wearer) {
            apply();
        }
    }

    private static List<RiderHand> hands(AbstractVehicle vehicle) {
        ModelData model = CommonAssetsManager.modelData(vehicle.getVehicleId()).orElse(null);
        return model == null ? List.of() : model.hands();
    }

    private static boolean hasContacts(AbstractVehicle vehicle, PartUnit<?> part) {
        String kit = kit(part);
        for (RiderHand hand : hands(vehicle)) {
            if (applies(hand, part.getId(), kit)) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static PartUnit<?> seatPart(AbstractVehicle vehicle, Player player) {
        for (AbstractVehicle.Seat seat : vehicle.seats) {
            if (seat.passengerId == player.getId()) {
                return seat.partUnit;
            }
        }
        return null;
    }

    @Nullable
    private static String kit(PartUnit<?> part) {
        ResourceLocation kit = part instanceof WeaponUnit unit ? unit.kit() : null;
        return kit == null ? null : kit.toString();
    }

    private static boolean applies(RiderHand hand, String part, @Nullable String kit) {
        return hand.part().equals(part) && (hand.kit() == null || hand.kit().equals(kit));
    }

    /** Shift / brake: {@link DriverControls}; else the named clip track mid-play. */
    private static boolean live(AbstractVehicle vehicle, PartUnit<?> part, RiderHand hand, float partialTick) {
        if (RiderHand.SHIFT.equals(hand.action()) || RiderHand.BRAKE.equals(hand.action())) {
            DriverControls controls = DriverControls.of(vehicle);
            return controls != null && (hand.foot() ? controls.braking() : controls.shifting());
        }
        PartClipTracks tracks = vehicle.clipTracks();
        PartClipTracks.Track track = tracks == null ? null
                : tracks.track(PartClipTracks.mountClip(hand.action(), part.getId()));
        if (track == null) {
            return false;
        }
        float p = track.progress(partialTick);
        return p > 0 && p < 1;
    }

    /** Posed node -> camera-relative world; action contact => mount clips posed too. */
    private static void worldPoint(AbstractVehicle vehicle, RiderHand hand, Vec3 camera, float partialTick,
                                   Vector3f out) {
        ModelData model = CommonAssetsManager.modelData(vehicle.getVehicleId()).orElseThrow();
        GraftedNodes.node(vehicle, model, hand.node(), hand.action() != null, partialTick, NODE).getTranslation(out);
        Vec3 c = vehicle.centerOffset;
        vehicle.rotYXZ(partialTick).get(ROTATION);
        out.sub((float) c.x, (float) c.y, (float) c.z).rotate(ROTATION);
        out.add((float) (Mth.lerp(partialTick, vehicle.xo, vehicle.getX()) + c.x - camera.x),
                (float) (Mth.lerp(partialTick, vehicle.yo, vehicle.getY()) + c.y - camera.y),
                (float) (Mth.lerp(partialTick, vehicle.zo, vehicle.getZ()) + c.z - camera.z));
    }

    /** Clip's own wrist / sole (limb pose + PlayerAnimator bend), as {@link ArmSolver#solve} reads it. */
    private static void authoredWrist(ModelPart arm, ArmSolver.Landmarks lm, Vector3f out) {
        float axis = -BEND[0];
        ARM.translation(arm.x / 16F, arm.y / 16F, arm.z / 16F).rotateZYX(arm.zRot, arm.yRot, arm.xRot)
                .translate(lm.centerX() / 16F, lm.elbowY() / 16F, lm.centerZ() / 16F)
                .rotate(ROTATION.identity().rotateAxis(BEND[1], (float) Math.cos(axis), 0, (float) Math.sin(axis)))
                .transformPosition(0, (lm.wristY() - lm.elbowY()) / 16F, 0, out);
    }

    private static void bend(Player player, String part) {
        BEND[0] = 0;
        BEND[1] = 0;
        if (!(player instanceof IAnimatedPlayer animated)) {
            return;
        }
        AnimationApplier anim = animated.playerAnimator_getAnimation();
        if (anim.isActive()) {
            Pair<Float, Float> b = anim.getBend(part);
            BEND[0] = b.getLeft();
            BEND[1] = b.getRight();
        }
    }

    /** Cube top = shoulder / hip, centre = elbow / knee, bottom = fist end / sole. */
    private static ArmSolver.Landmarks landmarks(ModelPart arm) {
        ModelPart.Cube c = arm.getRandomCube(RandomSource.create(0));
        return new ArmSolver.Landmarks((c.minX + c.maxX) * 0.5F, (c.minZ + c.maxZ) * 0.5F, c.minY,
                (c.minY + c.maxY) * 0.5F, c.maxY);
    }
}
