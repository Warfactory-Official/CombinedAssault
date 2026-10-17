package com.wf.combinedassault.client.fx;

import com.wf.combinedassault.CombinedAssault;
import com.wf.gemrender.asset.GemRenderModels;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.particle.GemRenderParticleTypes;
import com.wf.gemrender.particle.ParticleBuffer;
import com.wf.gemrender.particle.ParticleInstance;
import com.wf.gemrender.particle.ParticleModels;
import com.wf.gemrender.particle.ParticleStyle;
import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.api.model.Model;
import com.wf.wflib.ammo.Casing;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.function.Supplier;

/** Vehicle particle kinds (hits, wakes, spent cases) on GemRender. {@code ring} = emitter capacity per region (oldest overwritten). Units per second. */
enum HitFxKind {
    FLASH(64, GemRenderParticleTypes.BILLBOARD, () -> ParticleStyle.builder()
            .size(1, -0.3F)
            .tint(0xFFC870)
            .alpha(1, 1.5F)
            .glow(1)
            .build(), "flash", true),
    SPARK(1024, GemRenderParticleTypes.STREAK, () -> ParticleStyle.builder()
            .drag(ParticleStyle.dragFromPerTickFactor(0.96F))
            .gravity(14)
            .size(0.045F, 0)
            .tint(0xFFB040)
            .cool(0.35F, 0.6F)
            .streak(0.045F)
            .glow(1)
            .bouncesOnContact(0.4F, 0.6F)
            .build(), "spark", true),
    /** Molten jet droplets (HEAT), cook-off embers: slower, longer, orange. */
    EMBER(512, GemRenderParticleTypes.STREAK, () -> ParticleStyle.builder()
            .drag(ParticleStyle.dragFromPerTickFactor(0.94F))
            .gravity(10)
            .size(0.07F, 0)
            .tint(0xFF7A20)
            .cool(0.25F, 0.8F)
            .streak(0.02F)
            .glow(1)
            .bouncesOnContact(0.2F, 0.7F)
            .build(), "spark", true),
    FIRE(256, GemRenderParticleTypes.BILLBOARD, () -> ParticleStyle.builder()
            .drag(ParticleStyle.dragFromPerTickFactor(0.9F))
            .gravity(-3)
            .size(0.5F, 0.6F)
            .tint(0xFF9040)
            .alpha(0.9F, 1.4F)
            .cool(0.3F, 0.7F)
            .glow(1)
            .spin(1.5F)
            .build(), "flash", true),
    SMOKE(768, GemRenderParticleTypes.BILLBOARD, () -> ParticleStyle.builder()
            .drag(ParticleStyle.dragFromPerTickFactor(0.9F))
            .gravity(-0.8F)
            .size(0.3F, 1.6F)
            .alpha(0.6F, 1.1F)
            .fadeIn(0.05F)
            .spin(0.5F)
            .build(), "smoke", false),
    DUST(256, GemRenderParticleTypes.BILLBOARD, () -> ParticleStyle.builder()
            .drag(ParticleStyle.dragFromPerTickFactor(0.8F))
            .gravity(0.5F)
            .size(0.3F, 1.2F)
            .alpha(0.8F, 1.3F)
            .fadeIn(0.04F)
            .spin(0.8F)
            .build(), "dust", false),
    /** Paint flakes, metal scraps, rubber bits, glass shards: tint by look. */
    CHIP(512, GemRenderParticleTypes.BILLBOARD, () -> ParticleStyle.builder()
            .gravity(18)
            .size(0.09F, 0)
            .spin(9)
            .fadeOut(0.7F)
            .bouncesOnContact(0.25F, 0.4F)
            .build(), "chip", false),
    /** Wake / prop-wash raft on the water: spreads, fades. */
    FOAM(2048, GemRenderParticleTypes.DECAL, () -> ParticleStyle.builder()
            .size(1, 1.6F)
            .tint(0xF2F6F8)
            .alpha(0.8F, 1.4F)
            .fadeIn(0.03F)
            .build(), "foam", false),
    /** Bow wave / jet droplets. */
    SPRAY(1024, GemRenderParticleTypes.BILLBOARD, () -> ParticleStyle.builder()
            .drag(ParticleStyle.dragFromPerTickFactor(0.95F))
            .gravity(16)
            .size(0.25F, 1.2F)
            .tint(0xEEF4F8)
            .alpha(0.7F, 1.2F)
            .spin(0.6F)
            .build(), "foam", false),
    /** Screw bubbles: rise, die under the surface (life set per spawn). */
    BUBBLE(1024, GemRenderParticleTypes.BILLBOARD, () -> ParticleStyle.builder()
            .drag(ParticleStyle.dragFromPerTickFactor(WakeFx.BUBBLE_DRAG))
            .gravity(-WakeFx.BUBBLE_LIFT)
            .size(0.1F, 0.05F)
            .build(), "minecraft:textures/particle/bubble.png", false),
    CASING_PISTOL(64, Casing.PISTOL, 0.00495F),
    CASING_RIFLE(256, Casing.RIFLE, 0.00478F),
    CASING_LARGE(256, Casing.LARGE, 0.0102F),
    CASING_SHOTSHELL(32, Casing.SHOTSHELL, 0.0111F),
    CASING_CANNON(64, Casing.CANNON, 0.056F);

    /** Case tumble rad/s; life s. */
    static final float CASING_SPIN = 30;
    static final float CASING_LIFE = 8;

    final int ring;
    final InstanceType<ParticleInstance> type;
    private final Supplier<ParticleStyle> style;
    private final ResourceLocation texture;
    private final boolean glow;
    /** Case glTF radius, m; 0 => not a case. */
    final float radius;
    private int styleId = -1;

    HitFxKind(int ring, InstanceType<ParticleInstance> type, Supplier<ParticleStyle> style, String texture, boolean glow) {
        this.ring = ring;
        this.type = type;
        this.style = style;
        this.texture = texture.contains(":") ? ResourceLocation.parse(texture)
                : CombinedAssault.modLocation("textures/particle/fx/" + texture + ".png");
        this.glow = glow;
        this.radius = 0;
    }

    HitFxKind(int ring, Casing casing, float radius) {
        this.ring = ring;
        this.type = GemRenderParticleTypes.BODY;
        this.style = () -> ParticleStyle.builder()
                .gravity(16)
                .spin(CASING_SPIN)
                .bouncesOnContact(0.3F, 0.45F)
                .fadeOut(0.9F)
                .build();
        this.texture = ResourceLocation.fromNamespaceAndPath("wflib", "models/casing/" + casing.key + ".gltf");
        this.glow = false;
        this.radius = radius;
    }

    boolean rigid() {
        return radius > 0;
    }

    @Nullable
    static HitFxKind of(Casing c) {
        return switch (c) {
            case NONE -> null;
            case PISTOL -> CASING_PISTOL;
            case RIFLE -> CASING_RIFLE;
            case LARGE -> CASING_LARGE;
            case SHOTSHELL -> CASING_SHOTSHELL;
            case CANNON -> CASING_CANNON;
        };
    }

    /** Registered on first use (256 styles per JVM, never released). */
    int style() {
        if (styleId < 0) {
            styleId = ParticleBuffer.getInstance().registerStyle(style.get());
        }
        return styleId;
    }

    /** Client thread. Null: case glTF not loaded yet (requested). */
    @Nullable
    Model model() {
        if (rigid()) {
            GemRenderGltfModel m = GemRenderModels.get(texture);
            return m == null ? null : ParticleModels.rigid(m);
        }
        return glow ? ParticleModels.glowing(texture) : this == FOAM ? ParticleModels.decal(texture)
                : this == CHIP || this == BUBBLE ? ParticleModels.cutout(texture) : ParticleModels.absorbance(texture);
    }
}
