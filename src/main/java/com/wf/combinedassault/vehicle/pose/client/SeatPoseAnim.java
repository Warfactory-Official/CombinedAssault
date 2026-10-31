package com.wf.combinedassault.vehicle.pose.client;

import com.norwood.ahf.hook.AhfHooks;
import com.norwood.ahf.pose.GunPoses;
import com.norwood.ahf.pose.PoseClip;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.pose.SeatPoses;
import dev.kosmx.playerAnim.api.TransformType;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.data.gson.GeckoLibSerializer;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Seat pose sets on PlayerAnimator: lower 90, upper 91, rotation 92 (below MineAssault's 93-96). Active only while
 * {@link AhfHooks#poseSet} is a seat set: the rig then plays the same clips (AHF {@code HumanoidRig.libraryPose}).
 */
public final class SeatPoseAnim {
    private static final ResourceLocation LOWER = CombinedAssault.modLocation("seat_lower");
    private static final ResourceLocation UPPER = CombinedAssault.modLocation("seat_upper");
    private static final ResourceLocation ROTATION = CombinedAssault.modLocation("seat_rotation");
    private static final int FADE_TICKS = 8;

    private static final Map<ResourceLocation, Map<String, KeyframeAnimation>> SETS = new HashMap<>();

    private SeatPoseAnim() {
    }

    /** Client setup (main thread). */
    public static void register() {
        for (ResourceLocation id : SeatPoses.setIds()) {
            Map<String, KeyframeAnimation> clips = new HashMap<>();
            for (KeyframeAnimation a : GeckoLibSerializer.deserialize(SeatPoses.file(id))) {
                clips.put(((String) a.extraData.get("name")).toLowerCase(Locale.ENGLISH), a);
            }
            SETS.put(id, clips);
        }
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LOWER, 90, p -> new ModifierLayer<>());
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(UPPER, 91, p -> new ModifierLayer<>());
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(ROTATION, 92, RotationLayer::new);
        NeoForge.EVENT_BUS.addListener(SeatPoseAnim::onRenderPlayer);
    }

    private static void onRenderPlayer(RenderPlayerEvent.Pre e) {
        AbstractClientPlayer p = (AbstractClientPlayer) e.getEntity();
        if (Minecraft.getInstance().isPaused()) {
            return;
        }
        ResourceLocation set = AhfHooks.poseSet(p);
        GunPoses.Selection sel = set != null && SETS.containsKey(set)
                ? GunPoses.select(p, Math.min(p.walkAnimation.speed(e.getPartialTick()), 1.0F)) : null;
        if (PlayerAnimationAccess.getPlayerAssociatedData(p).get(ROTATION) instanceof RotationLayer r) {
            r.sel = sel;
        }
        play(p, LOWER, sel == null ? null : clip(sel.set(), sel.lower()));
        play(p, UPPER, sel == null ? null : clip(sel.set(), sel.upper()));
    }

    @Nullable
    private static KeyframeAnimation clip(ResourceLocation set, @Nullable String name) {
        return name == null ? null : SETS.get(set).get(name);
    }

    @SuppressWarnings("unchecked")
    private static void play(AbstractClientPlayer p, ResourceLocation id, @Nullable KeyframeAnimation a) {
        if (!(PlayerAnimationAccess.getPlayerAssociatedData(p).get(id) instanceof ModifierLayer<?> raw)) {
            return;
        }
        ModifierLayer<IAnimation> layer = (ModifierLayer<IAnimation>) raw;
        if (a == null) {
            if (layer.isActive()) {
                layer.replaceAnimationWithFade(fade(), null);
            }
            return;
        }
        if (!(layer.getAnimation() instanceof KeyframeAnimationPlayer cur && cur.isActive() && cur.getData() == a)) {
            layer.replaceAnimationWithFade(fade(), new KeyframeAnimationPlayer(a));
        }
    }

    private static AbstractFadeModifier fade() {
        return AbstractFadeModifier.standardFadeIn(FADE_TICKS, Ease.INOUTSINE);
    }

    /** Rig's rotation layer ({@link GunPoses#adjust}): head + arms + pitch, body none while riding. */
    static final class RotationLayer implements IAnimation {
        private final AbstractClientPlayer player;
        /** {@link PoseClip#CHANNELS} of one bone: {@link GunPoses#adjust} reads and writes the rotation at 3..5. */
        private final double[] r = new double[PoseClip.CHANNELS];
        @Nullable
        GunPoses.Selection sel;

        RotationLayer(AbstractClientPlayer player) {
            this.player = player;
        }

        @Override
        public boolean isActive() {
            return sel != null;
        }

        @Override
        public void setupAnim(float tickDelta) {
        }

        @Override
        public Vec3f get3DTransform(String part, TransformType type, float tickDelta, Vec3f value) {
            if (sel == null || type != TransformType.ROTATION) {
                return value;
            }
            int bone = PoseClip.bone(part);
            switch (bone) {
                case PoseClip.BODY -> java.util.Arrays.fill(r, 0);
                case PoseClip.HEAD, PoseClip.RIGHT_ARM, PoseClip.LEFT_ARM -> {
                    java.util.Arrays.fill(r, 0);
                    r[3] = value.getX();
                    r[4] = value.getY();
                    r[5] = value.getZ();
                }
                default -> {
                    return value;
                }
            }
            GunPoses.adjust(player, tickDelta, sel.lying() ? 1 : 0, bone, r, null);
            return new Vec3f((float) r[3], (float) r[4], (float) r[5]);
        }
    }
}
