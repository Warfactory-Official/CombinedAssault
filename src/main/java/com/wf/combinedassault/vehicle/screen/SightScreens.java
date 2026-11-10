package com.wf.combinedassault.vehicle.screen;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.hud.MathUtil;
import com.wf.combinedassault.indirect.client.GunComputer;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.cam.api.CameraMode;
import com.wf.wflib.cam.api.client.FeedPass;
import com.wf.wflib.cam.api.client.FeedTexture;
import com.wf.wflib.cam.api.client.OffscreenView;
import com.wf.wflib.cam.api.client.SightSensor;
import com.wf.wflib.ui.api.UiInstance;
import com.wf.wflib.ui.api.UiLayouts;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL30;

import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * {@link ScreenInfo.Kind#SIGHT} pictures: the weapon's sight pose, zoom and (operator's) sight mode through a
 * {@link SightSensor} pass into a per-screen target, the screen's layout drawn over it. Passes only for screens drawn
 * last frame, only while the local player rides that vehicle outside SCOPE (there the main view is the sight), at
 * most {@code max_fps}.
 */
@OnlyIn(Dist.CLIENT)
public final class SightScreens {
    /** Picture width, px; height from the screen's aspect. */
    private static final int WIDTH = 512;
    /** Unzoomed sight field, deg (vertical). */
    private static final double BASE_FOV = 70;
    /** Unused this long => buffers freed. */
    private static final long STALE_NANOS = 2_000_000_000L;
    private static final Map<VehicleScreens.State, Feed[]> FEEDS = new IdentityHashMap<>();

    private SightScreens() {
    }

    private static final class Feed {
        final ResourceLocation location;
        final FeedTexture texture = new FeedTexture();
        final SightSensor sensor = new SightSensor(OffscreenView.sharedGrid());
        final int width;
        final int height;
        /** Pass + overlay; GL row 0 = picture bottom. */
        @Nullable
        TextureTarget work;
        /** Shown; {@link #work} flipped => row 0 = picture top, standard glTF V like every other screen texture. */
        @Nullable
        TextureTarget target;
        UiInstance overlay;
        long wanted;
        long rendered = Long.MIN_VALUE / 2;
        boolean drawn;

        Feed(ResourceLocation location, int width, int height) {
            this.location = location;
            this.width = width;
            this.height = height;
        }

        /** Frame start only: the TextureTarget ctor ends in unbindWrite => framebuffer 0 bound => rest of a level pass drawn off-screen. */
        TextureTarget work() {
            if (work == null) {
                work = new TextureTarget(width, height, true, Minecraft.ON_OSX);
                work.setClearColor(0, 0, 0, 1);
                target = new TextureTarget(width, height, false, Minecraft.ON_OSX);
                texture.adopt(target.getColorTextureId());
                Minecraft.getInstance().getTextureManager().register(location, texture);
            }
            return work;
        }

        void close() {
            sensor.close();
            if (work != null) {
                Minecraft.getInstance().getTextureManager().release(location);
                work.destroyBuffers();
                target.destroyBuffers();
            }
        }
    }

    /** Texture for screen {@code i} of {@code state} (null until its first picture); marks it wanted. */
    @Nullable
    public static ResourceLocation texture(VehicleScreens.State state, int i, float width, float height) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.player.getVehicle() != state.vehicle) {
            return null;
        }
        Feed[] feeds = FEEDS.get(state);
        if (FeedPass.active() && (feeds == null || feeds[i] == null)) {
            // Inside onFrame's iteration over FEEDS.
            return null;
        }
        if (feeds == null) {
            feeds = new Feed[state.count];
            FEEDS.put(state, feeds);
        }
        if (feeds[i] == null) {
            int h = Math.max(16, Math.round(WIDTH * height / width));
            feeds[i] = new Feed(CombinedAssault.modLocation("sight_screen/" + state.vehicle.getId() + "_" + i), WIDTH, h);
        }
        Feed feed = feeds[i];
        feed.wanted = System.nanoTime();
        return feed.drawn ? feed.location : null;
    }

    @SubscribeEvent
    public static void onFrame(RenderFrameEvent.Pre e) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        LocalVehiclePlayer local = LocalVehiclePlayer.instance();
        for (Iterator<Map.Entry<VehicleScreens.State, Feed[]>> it = FEEDS.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<VehicleScreens.State, Feed[]> entry = it.next();
            VehicleScreens.State state = entry.getKey();
            Feed[] feeds = entry.getValue();
            boolean live = false;
            for (int i = 0; i < feeds.length; i++) {
                Feed feed = feeds[i];
                if (feed == null) {
                    continue;
                }
                if (now - feed.wanted > STALE_NANOS || state.vehicle.isRemoved()) {
                    feed.close();
                    feeds[i] = null;
                    continue;
                }
                live = true;
                ScreenInfo info = state.infos[i];
                if (mc.player == null || mc.player.getVehicle() != state.vehicle
                        || local.viewType == LocalVehiclePlayer.ViewType.SCOPE
                        || now - feed.rendered < 1_000_000_000L / info.maxFps
                        || !(state.views[i] instanceof GunComputer computer)) {
                    continue;
                }
                render(feed, state, i, computer, e);
                feed.rendered = now;
            }
            if (!live) {
                it.remove();
            }
        }
    }

    private static void render(Feed feed, VehicleScreens.State state, int i, GunComputer computer, RenderFrameEvent.Pre e) {
        Minecraft mc = Minecraft.getInstance();
        WeaponUnit unit = computer.unit();
        float pt = e.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 eye = unit.worldOpticalSightPosition(pt);
        Vec2 rot = unit.worldRot(unit.getViewXRot(pt), unit.getViewYRot(pt));
        double yaw = Math.toRadians(rot.y), pitch = Math.toRadians(rot.x);
        double objective = state.infos[i].objective;
        eye = eye.add(-Math.sin(yaw) * Math.cos(pitch) * objective, -Math.sin(pitch) * objective, Math.cos(yaw) * Math.cos(pitch) * objective);
        float fov = (float) MathUtil.magnificationToFov(unit.getZoom(), BASE_FOV);
        LocalVehiclePlayer local = LocalVehiclePlayer.instance();
        CameraMode mode = local.sightUnit() == unit && unit.fitted(local.sightMode) ? local.sightMode : CameraMode.OPTICAL;
        RenderTarget main = mc.getMainRenderTarget();
        try {
            TextureTarget work = feed.work();
            if (!feed.sensor.render(state.vehicle, eye, rot.y, rot.x, unit.worldZRot(), fov, work,
                    e.getPartialTick(), mode)) {
                return;
            }
            // Pass clears at alpha 0; pixels nothing drew (below horizon past the terrain) => slot shader discards
            // => painted glass shows through.
            GlStateManager._disableScissorTest();
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, work.frameBufferId);
            GlStateManager._colorMask(false, false, false, true);
            GlStateManager._clearColor(0, 0, 0, 1);
            GlStateManager._clear(GL30.GL_COLOR_BUFFER_BIT, false);
            GlStateManager._colorMask(true, true, true, true);
            computer.refresh(pt);
            if (feed.overlay == null) {
                feed.overlay = UiLayouts.get(state.infos[i].layout).instantiate();
            }
            feed.overlay.context(computer::subject);
            float design = UiLayouts.get(state.infos[i].layout).designWidth();
            float uw = design > 0 ? design : state.infos[i].unitsWidth;
            float uh = uw * work.height / work.width;
            Canvas canvas = Canvas.shared().begin(work.frameBufferId, work.width, work.height, uw, uh, true);
            try {
                feed.overlay.render(canvas, 0, 0, uw, uh, 0);
            } finally {
                canvas.end();
            }
            GlStateManager._disableScissorTest();
            GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, work.frameBufferId);
            GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, feed.target.frameBufferId);
            GL30.glBlitFramebuffer(0, 0, work.width, work.height, 0, work.height, work.width, 0,
                    GL30.GL_COLOR_BUFFER_BIT, GL30.GL_NEAREST);
            feed.drawn = true;
        } finally {
            main.bindWrite(true);
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        FEEDS.values().forEach(feeds -> {
            for (Feed feed : feeds) {
                if (feed != null) {
                    feed.close();
                }
            }
        });
        FEEDS.clear();
    }
}
