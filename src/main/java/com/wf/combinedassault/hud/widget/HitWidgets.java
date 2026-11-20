package com.wf.combinedassault.hud.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.message.HitDetail;
import com.wf.combinedassault.ballistics.message.ServerHitVehicleEvent;
import com.wf.combinedassault.render.util.HullRenderer;
import com.wf.combinedassault.hud.AimPoint;
import com.wf.combinedassault.hud.CaDraw;
import com.wf.combinedassault.hud.HitFeedback;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.VehicleConfig;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
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
     * Damage panel for the target last hit: latest message and summed damage, the target's armour hit mesh (plate
     * colours, the F3+B view) turned to face the hit, and every recent round's path: approach, crossings, where it
     * stopped or left. F3 adds the latest round's plate / angle / thickness / damage split. Clears 3 s after the
     * last hit. Origin = panel centre.
     */
    public record HitPanel() implements WidgetDef {
        private static final int[] OUTCOME_RGB = {0xB4A8FF, 0x5FD09A, 0xE5B84A, 0xF0925A, 0xFF4A40};
        private static final String[] OUTCOMES = {"RICOCHET", "ABSORBED", "PARTIAL", "PENETRATION", "OVERMATCH"};
        private static final float PANEL_SPAN = 110f;
        private static final float MAX_SCALE = 16f;

        private static int rgb(HitDetail detail) {
            if (detail == null) {
                return 0xFF4A40;
            }
            for (int i = 0; i < OUTCOMES.length; i++) {
                if (OUTCOMES[i].equals(detail.outcome())) {
                    return OUTCOME_RGB[i];
                }
            }
            return 0xFFFFFF;
        }

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
                    ServerHitVehicleEvent top = events.get(events.size() - 1);
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
                    float length = entity instanceof AbstractVehicle vehicle ? (float) Math.max(vehicle.getStructureLength(), 2.0)
                            : (float) Math.max(entity.getBoundingBox().getSize(), 1.0);
                    float scale = Math.min(MAX_SCALE, PANEL_SPAN / length);
                    float pitch = (float) Math.toDegrees(Math.atan2(-view.y, Math.sqrt(view.x * view.x + view.z * view.z)));
                    float yaw = (float) Math.toDegrees(Math.atan2(view.x, view.z));
                    PoseStack pose = graphics.pose();
                    pose.pushPose();
                    canvas.applyTo(pose);
                    pose.translate(x, y, 0);
                    pose.rotateAround(Axis.XP.rotationDegrees(pitch + 180), 0, 0, 0);
                    pose.rotateAround(Axis.YP.rotationDegrees(yaw), 0, 0, 0);
                    pose.last().pose().mul(new Matrix4f().scaling(scale, scale, -scale));
                    Entity target = entity;
                    MultiBufferSource.BufferSource buffers = graphics.bufferSource();
                    RenderSystem.runAsFancy(() -> {
                        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
                        for (ServerHitVehicleEvent event : events) {
                            shotPath(pose.last().pose(), lines, target, event, event == top);
                        }
                        if (target instanceof AbstractVehicle vehicle && vehicle.hits() != null) {
                            // Entity renderer draws only the vanilla-pipeline parts here (tracks): the armour mesh is the model.
                            // Paths first: translucent surfaces then dim the part of each path inside the hull.
                            VertexConsumer surfaces = buffers.getBuffer(RenderType.debugQuads());
                            HullRenderer.renderHitsSolid(vehicle, pose, surfaces, 0.55F);
                        }
                    });
                    graphics.flush();
                    pose.popPose();
                }

                /** Approach line, crossing ticks, stop (cross) or exit (arrow tip) marker, entry marker; latest round brighter. */
                private void shotPath(Matrix4f matrix, VertexConsumer lines, Entity target, ServerHitVehicleEvent event, boolean latest) {
                    Vec3 dir = event.hitVector.lengthSqr() < 1e-9 ? new Vec3(0, 0, 1) : event.hitVector.normalize();
                    Vec3 entry = event.hitPosition.subtract(target.position());
                    HitDetail d = event.detail;
                    int colour = rgb(d);
                    float a = latest ? 1f : 0.45f;
                    float r = (colour >> 16 & 255) / 255f, g = (colour >> 8 & 255) / 255f, b = (colour & 255) / 255f;
                    seg(matrix, lines, entry.subtract(dir.scale(2.5)), entry, r, g, b, a);
                    cross(matrix, lines, entry, 0.12, 1f, 1f, 1f, a);
                    if (d == null) {
                        seg(matrix, lines, entry, entry.add(dir.scale(0.5)), r, g, b, a);
                        return;
                    }
                    Vec3 end = entry;
                    for (int k = 0; k < d.crossings().length; k++) {
                        Vec3 at = entry.add(dir.scale(d.crossings()[k]));
                        cross(matrix, lines, at, 0.05, d.entering()[k] ? 1f : 0.6f, 1f, d.entering()[k] ? 1f : 0.6f, a);
                        if (d.stoppedAt() < 0 || k <= d.stoppedAt()) {
                            end = at;
                        }
                    }
                    if (d.crossings().length == 0 || end == entry) {
                        end = entry.add(dir.scale(d.interiorPen() > 0 ? 0.4 : 0.05));
                    }
                    seg(matrix, lines, entry, end, r, g, b, a);
                    if (d.stoppedAt() >= 0) {
                        cross(matrix, lines, end, 0.18, 1f, 0.15f, 0.1f, a);
                    } else if (d.interiorPen() > 0) {
                        seg(matrix, lines, end, end.add(dir.scale(0.6)), 0.3f, 1f, 0.4f, a);
                    }
                }

                private void seg(Matrix4f matrix, VertexConsumer lines, Vec3 from, Vec3 to, float r, float g, float b, float a) {
                    Vec3 n = to.subtract(from);
                    float len = (float) n.length();
                    if (len < 1e-6f) {
                        return;
                    }
                    float nx = (float) n.x / len, ny = (float) n.y / len, nz = (float) n.z / len;
                    lines.addVertex(matrix, (float) from.x, (float) from.y, (float) from.z).setColor(r, g, b, a).setNormal(nx, ny, nz);
                    lines.addVertex(matrix, (float) to.x, (float) to.y, (float) to.z).setColor(r, g, b, a).setNormal(nx, ny, nz);
                }

                private void cross(Matrix4f matrix, VertexConsumer lines, Vec3 c, double size, float r, float g, float b, float a) {
                    seg(matrix, lines, c.add(size, 0, 0), c.subtract(size, 0, 0), r, g, b, a);
                    seg(matrix, lines, c.add(0, size, 0), c.subtract(0, size, 0), r, g, b, a);
                    seg(matrix, lines, c.add(0, 0, size), c.subtract(0, 0, size), r, g, b, a);
                }
            };
        }
    }
}
