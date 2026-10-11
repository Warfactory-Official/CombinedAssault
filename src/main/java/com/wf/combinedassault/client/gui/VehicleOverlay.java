package com.wf.combinedassault.client.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.minecraft.world.scores.Team;
import com.wf.combinedassault.client.render.util.Color;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.ballistics.component.client.SystemsHud;
import com.wf.combinedassault.entity.vehicle.NoneVehicle;
import com.wf.combinedassault.util.RenderHelper;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class VehicleOverlay implements LayeredDraw.Layer {

    public static ConcurrentHashMap<Long, Component> tips = new ConcurrentHashMap<>();

    @Override
    public void render(GuiGraphics guiGraphics, DeltaTracker deltaTracker) {
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
        int screenWidth = guiGraphics.guiWidth();
        int screenHeight = guiGraphics.guiHeight();
        LocalVehiclePlayer localVehiclePlayer = LocalVehiclePlayer.instance;
        if (!localVehiclePlayer.onVehicle()) {
            return;
        }
        AbstractVehicle vehicle = localVehiclePlayer.vehicle;
        int centerX = screenWidth / 2;
        int centerY = screenHeight / 2;
        renderCrew(guiGraphics, centerX, centerY, vehicle);
        if (localVehiclePlayer.viewType != LocalVehiclePlayer.ViewType.THIRD_PERSON) {
            renderCompassBar(guiGraphics, screenWidth);
        }
        if (!(vehicle instanceof NoneVehicle)) {
            renderBaseInfo(guiGraphics, screenWidth, screenHeight, vehicle);
        }
        renderTips(guiGraphics, screenWidth, screenHeight);
    }

    public static void renderCrew(GuiGraphics guiGraphics, int centerX, int centerY, AbstractVehicle vehicle) {
        int x = centerX - 160;
        int y = centerY + guiGraphics.guiHeight() / 5;
        for (int index = 0; index < vehicle.seats.size(); index++) {
            Integer playerId = vehicle.seats.get(index).passengerId;
            Entity entity = null;
            if (playerId != null) {
                entity = LocalVehiclePlayer.instance.getPlayer().level().getEntity(playerId);
            }
            String info = "[]";
            if (entity != null) {
                info = "[" + entity.getDisplayName().getString() + "]";
            }
            guiGraphics.drawString(Minecraft.getInstance().font, info, x, y, Color.GREEN);
            y += 10;
        }
    }

    public static void renderCompassBar(GuiGraphics guiGraphics, int screenWidth) {
        float yaw = Minecraft.getInstance().gameRenderer.getMainCamera().getYRot();
        Font font = Minecraft.getInstance().font;
        PoseStack poseStack = guiGraphics.pose();
        poseStack.pushPose();
        {
            RenderSystem.enableBlend();
            float centerX = screenWidth / 2f;
            poseStack.translate(centerX, 12, 0);
            poseStack.pushPose();
            {
                guiGraphics.enableScissor((int) (centerX - 120), 0, (int) (centerX + 120), 40);
                poseStack.translate(-yaw * 4 - 0.5f, 0, 0);
                for (int x = -225; x <= 225; x += 5) {
                    switch (x) {
                        case -135, 225 -> renderDirection(guiGraphics, poseStack, font, x, "NE");
                        case -90 -> renderDirection(guiGraphics, poseStack, font, x, "E");
                        case -45 -> renderDirection(guiGraphics, poseStack, font, x, "SE");
                        case 0 -> renderDirection(guiGraphics, poseStack, font, x, "S");
                        case 45 -> renderDirection(guiGraphics, poseStack, font, x, "SW");
                        case 90 -> renderDirection(guiGraphics, poseStack, font, x, "W");
                        case 135, -225 -> renderDirection(guiGraphics, poseStack, font, x, "NW");
                        case 180, -180 -> renderDirection(guiGraphics, poseStack, font, x, "N");
                        default -> {
                            if (x % 15 == 0) {
                                int s = x > 180 ? x - 360 : (x < -180 ? x + 360 : x);
                                renderDirection(guiGraphics, poseStack, font, x, s + "");
                            } else {
                                guiGraphics.vLine(x * 4, 0, 6, Color.GREEN);
                            }
                        }
                    }
                }
                guiGraphics.disableScissor();
            }
            poseStack.popPose();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
            BufferBuilder buf = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
            buf.addVertex(poseStack.last().pose(), 0, 23, 0).setColor(0, 255, 0, 255);
            buf.addVertex(poseStack.last().pose(), -3, 29, 0).setColor(0, 255, 0, 255);
            buf.addVertex(poseStack.last().pose(), 3, 29, 0).setColor(0, 255, 0, 255);
            BufferUploader.drawWithShader(buf.buildOrThrow());
            RenderSystem.disableBlend();
        }
        poseStack.popPose();
    }

    private void renderBaseInfo(GuiGraphics guiGraphics, int screenWidth, int screenHeight, AbstractVehicle vehicle) {
        int nameColor = Color.WHITE;
        Team team = vehicle.getTeam();
        if (team != null && team.getColor().getColor() != null) {
            nameColor = team.getColor().getColor();
        }
        float energyCapacity = vehicle.energyInfo.capacityUnits();
        float fuelPercent = energyCapacity > 0 ? vehicle.getEnergy() / energyCapacity * 100 : 0;
        float powerPercent = vehicle.getPower();
        SystemsHud.render(guiGraphics, vehicle, vehicle.getDisplayName(), nameColor,
                List.of(Component.literal(String.format("%s %.1f%%", fuelLabel(vehicle), fuelPercent)),
                        Component.literal(String.format("POWER %.1f%%", powerPercent))),
                List.of(fuelPercent < 5 ? Color.RED : Color.WHITE, powerPercent < 30 ? Color.RED : Color.WHITE));
    }

    /** What the tank is holding, so a gauge reading 40% says 40% of what. */
    private static String fuelLabel(AbstractVehicle vehicle) {
        if (vehicle.isElectric()) {
            return "CHARGE";
        }
        Fluid fluid = vehicle.fuelFluid();
        if (fluid == Fluids.EMPTY) {
            return "FUEL";
        }
        return new FluidStack(fluid, 1).getHoverName().getString().toUpperCase();
    }

    public void renderTips(GuiGraphics guiGraphics, int screenWidth, int screenHeight) {
        int centerX = screenWidth / 2;
        int centerY = screenHeight / 2;
        LocalVehiclePlayer localVehiclePlayer = LocalVehiclePlayer.instance;
        guiGraphics.pose().pushPose();
        {
            guiGraphics.pose().translate(centerX, centerY, 0);
            if (localVehiclePlayer.lostControl) {
                guiGraphics.drawCenteredString(Minecraft.getInstance().font,
                        Component.translatable("ui.lost_control"),
                        0, -45, Color.RED);
            } else if (localVehiclePlayer.endureTick > 5
                    && (LocalVehiclePlayer.instance.currentG >= 2 || LocalVehiclePlayer.instance.currentG <= -1)) {
                guiGraphics.drawCenteredString(Minecraft.getInstance().font,
                        Component.translatable("ui.overload", String.format("%.1f", localVehiclePlayer.currentG)).append("G"),
                        0, -45, Color.RED);
            } else if (!localVehiclePlayer.missiles.isEmpty()) {
                guiGraphics.drawCenteredString(Minecraft.getInstance().font,
                        Component.translatable("ui.homing"),
                        0, -45, Color.GREEN);
                tips.clear();
            }
            if (!tips.isEmpty()) {
                for (Map.Entry<Long, Component> tip : tips.entrySet()) {
                    if (tip.getKey() + 3000 < System.currentTimeMillis()) {
                        tips.remove(tip.getKey());
                        continue;
                    }
                    guiGraphics.drawCenteredString(Minecraft.getInstance().font, tip.getValue(), 0, -45, Color.RED);
                }
            }
        }
        guiGraphics.pose().popPose();
    }

    public static void renderDirection(GuiGraphics graphics, PoseStack poseStack, Font font, int x, String s) {
        graphics.vLine(x * 4, 0, 8, Color.GREEN);
        poseStack.translate(1f, 0, 0);
        RenderHelper.drawCenteredString(graphics, font, s, x * 4, 12, Color.GREEN);
        poseStack.translate(-1f, 0, 0);
    }

}
