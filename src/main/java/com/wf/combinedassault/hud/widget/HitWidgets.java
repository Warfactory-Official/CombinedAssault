package com.wf.combinedassault.hud.widget;

import com.wf.combinedassault.vehicle.VehicleConfig;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.hud.AimPoint;
import com.wf.combinedassault.hud.CaDraw;
import com.wf.combinedassault.hud.HitFeedback;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.ballistics.message.ServerHitVehicleEvent;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.gemrender.ui.Canvas;
import com.wf.gemrender.ui.TextBuffer;
import com.wf.wflib.ui.api.Binder;
import com.wf.wflib.ui.api.UiEval;
import com.wf.wflib.ui.api.Widget;
import com.wf.wflib.ui.api.WidgetDef;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;

/** Hit confirmation: the marker at the aim point and the damage panel with the hit model. */
public final class HitWidgets {
    private static final ResourceLocation HIT_COMMON = CombinedAssault.modLocation("textures/ui/hit_common.png");
    private static final ResourceLocation HIT_VEHICLE = CombinedAssault.modLocation("textures/ui/hit_vehicle.png");
    private static final float MAX_OFFSET = 0.6f;
    private static final long KEEP_TIME = 300;

    private HitWidgets() {
    }

    /** Four quadrants of the marker texture flying apart and fading; red and twice as far on a kill. */
    public record HitMarker() implements WidgetDef {
        @Override
        public Widget create(Binder binder) {
            return new Widget() {
                @Override
                public void measure(UiEval ev) {
                }

                @Override
                public void draw(UiEval ev, float bx, float by, float w, float h) {
                    long now = System.currentTimeMillis();
                    long sinceHit = now - HitFeedback.hitTimestamp();
                    long sinceKill = now - HitFeedback.killTimestamp();
                    float fade;
                    float diffusion = 1f;
                    boolean kill = false;
                    if (sinceKill > KEEP_TIME) {
                        if (sinceHit > KEEP_TIME) {
                            return;
                        }
                        fade = sinceHit;
                    } else {
                        fade = sinceKill;
                        diffusion = 2f;
                        kill = true;
                    }
                    float progress = Math.min(fade / (KEEP_TIME * diffusion), 1.0f);
                    float eased = 1.0f - (float) Math.pow(1.0f - progress, 3);
                    float offset = eased * MAX_OFFSET * diffusion;
                    float alpha = 1.0f - (float) Math.pow(progress, 1.5);
                    int argb = CaDraw.withAlpha(kill ? 0xFF0000 : 0xFFFFFF, alpha);
                    float size = 32;
                    float half = size / 2;
                    float x = ev.fromHost((float) AimPoint.x(ev.partialTick)) - half;
                    float y = ev.fromHost((float) AimPoint.y(ev.partialTick)) - half;
                    ResourceLocation texture = HitFeedback.hitVehicle() ? HIT_VEHICLE : HIT_COMMON;
                    Canvas canvas = ev.canvas;
                    canvas.sprite(texture, x - offset, y - offset, x - offset + half, y - offset + half, 0, 0, 0.5f, 0.5f, argb);
                    canvas.sprite(texture, x + half + offset, y - offset, x + size + offset, y - offset + half, 0.5f, 0, 1, 0.5f, argb);
                    canvas.sprite(texture, x - offset, y + half + offset, x - offset + half, y + size + offset, 0, 0.5f, 0.5f, 1, argb);
                    canvas.sprite(texture, x + half + offset, y + half + offset, x + size + offset, y + size + offset, 0.5f, 0.5f, 1, 1, argb);
                }
            };
        }
    }

    /**
     * Damage panel for the target last hit: message, summed damage, the target's model posed to face
     * the hit with red hit vectors. Clears 3 s after the last hit. Origin = panel centre.
     */
    public record HitPanel() implements WidgetDef {
        @Override
        public Widget create(Binder binder) {
            return new Widget() {
                private final TextBuffer text = new TextBuffer(24);

                @Override
                public void measure(UiEval ev) {
                }

                @Override
                public void draw(UiEval ev, float x, float y, float w, float h) {
                    if (!VehicleConfig.common().hitIndicator()) {
                        return;
                    }
                    List<ServerHitVehicleEvent> events = HitFeedback.events;
                    if (System.currentTimeMillis() - HitFeedback.lastHitTime() > 3000) {
                        events.clear();
                    }
                    if (events.isEmpty() || ev.graphics == null) {
                        return;
                    }
                    Player player = LocalVehiclePlayer.instance().getPlayer();
                    if (player == null) {
                        return;
                    }
                    ServerHitVehicleEvent top = events.get(0);
                    Entity entity = player.level().getEntity(top.entityId);
                    if (entity == null) {
                        LocalVehiclePlayer.ServerEntity remote = LocalVehiclePlayer.instance().serverEntities.get(top.entityId);
                        if (remote == null || remote.entity == null) {
                            return;
                        }
                        entity = remote.entity;
                    }
                    double damage = 0;
                    for (int i = 0; i < events.size(); i++) {
                        damage += events.get(i).damage;
                    }
                    Canvas canvas = ev.canvas;
                    float tw = canvas.width(top.message.getVisualOrderText());
                    canvas.text(top.message.getVisualOrderText(), x - tw / 2, y - 55, CaDraw.WHITE, true);
                    text.clear().append('-').append(damage, 2);
                    canvas.text(text, x - (int) canvas.width(text) / 2, y - 45, CaDraw.RED, false);
                    canvas.suspend();
                    model(ev.graphics, canvas, x, y, entity, top, events);
                    canvas.resume();
                }

                private void model(GuiGraphics graphics, Canvas canvas, float x, float y, Entity entity,
                                   ServerHitVehicleEvent top, List<ServerHitVehicleEvent> events) {
                    Vec3 view = top.hitPosition.subtract(entity.position());
                    float scale = entity instanceof AbstractVehicle vehicle
                            ? (float) Math.min(10, 8 / vehicle.getStructureLength() * 10)
                            : (float) Math.min(10, 48 / entity.getBoundingBox().getSize());
                    float pitch = (float) Math.toDegrees(Math.atan2(-view.y, Math.sqrt(view.x * view.x + view.z * view.z)));
                    float yaw = (float) Math.toDegrees(Math.atan2(view.x, view.z));
                    PoseStack pose = graphics.pose();
                    pose.pushPose();
                    canvas.applyTo(pose);
                    pose.translate(x, y, 0);
                    pose.rotateAround(Axis.XP.rotationDegrees(pitch + 180), 0, 0, 0);
                    pose.rotateAround(Axis.YP.rotationDegrees(yaw), 0, 0, 0);
                    pose.last().pose().mul(new Matrix4f().scaling(scale, scale, -scale));
                    Lighting.setupForEntityInInventory();
                    EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
                    dispatcher.setRenderShadow(false);
                    Entity target = entity;
                    RenderSystem.runAsFancy(() -> {
                        dispatcher.render(target, 0, 0, 0, 0, 1.0F, pose, graphics.bufferSource(), 15728880);
                        for (ServerHitVehicleEvent event : events) {
                            Vec3 start = event.hitPosition.subtract(target.position());
                            redLine(graphics, start, start.subtract(event.hitVector.normalize().scale(3)));
                        }
                    });
                    graphics.flush();
                    dispatcher.setRenderShadow(true);
                    pose.popPose();
                    Lighting.setupFor3DItems();
                }

                private void redLine(GuiGraphics graphics, Vec3 start, Vec3 end) {
                    Matrix4f matrix = graphics.pose().last().pose();
                    MultiBufferSource.BufferSource buffers = graphics.bufferSource();
                    VertexConsumer lines = buffers.getBuffer(RenderType.lines());
                    float offset = 0.01f;
                    for (int i = -1; i <= 2; i++) {
                        for (int j = -1; j <= 2; j++) {
                            lines.addVertex(matrix, (float) (start.x + i * offset), (float) start.y, (float) (start.z + j * offset))
                                    .setColor(1f, 0f, 0f, 1f).setNormal(0, 1, -100);
                            lines.addVertex(matrix, (float) (end.x + i * offset), (float) end.y, (float) (end.z + j * offset))
                                    .setColor(1f, 0f, 0f, 1f).setNormal(0, 1, -100);
                        }
                    }
                }
            };
        }
    }
}
