package com.wf.combinedassault.props.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.wf.combinedassault.props.message.ClientDecorationAction;
import com.wf.combinedassault.props.part.DecorationUnit;
import com.wf.combinedassault.render.util.RenderHelper;
import com.wf.combinedassault.screen.ModelPort;
import com.wf.combinedassault.screen.Navbar;
import com.wf.combinedassault.screen.Ore;
import com.wf.combinedassault.screen.OreButton;
import com.wf.combinedassault.screen.OreScreen;
import com.wf.combinedassault.screen.Panel;
import com.wf.combinedassault.screen.Port;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.ui.api.UiLayout;
import com.wf.wflib.ui.api.WidgetDef;
import com.wf.wflib.ui.api.input.TextBox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.apache.commons.lang3.StringUtils;
import org.joml.Vector3f;


import static com.wf.combinedassault.screen.Ore.pct;
import static com.wf.wflib.ui.api.Ui.at;
import static com.wf.wflib.ui.api.Ui.stack;

public class DecorationSettingsScreen extends OreScreen {
    private static DecorationSettingsScreen current;
    private static final TextBox SCALE = SettingRows.number();
    private static final TextBox X = SettingRows.number();
    private static final TextBox Y = SettingRows.number();
    private static final TextBox Z = SettingRows.number();
    private static final TextBox[] ROT = {SettingRows.angle(), SettingRows.angle(), SettingRows.angle()};
    private static UiLayout layout;

    static {
        bind(SCALE, (s, v) -> s.unit.scale = v);
        bind(X, (s, v) -> s.unit.offsetFromBone = new Vec3(v, s.unit.offsetFromBone.y, s.unit.offsetFromBone.z));
        bind(Y, (s, v) -> s.unit.offsetFromBone = new Vec3(s.unit.offsetFromBone.x, v, s.unit.offsetFromBone.z));
        bind(Z, (s, v) -> s.unit.offsetFromBone = new Vec3(s.unit.offsetFromBone.x, s.unit.offsetFromBone.y, v));
        for (int i = 0; i < 3; i++) {
            int axis = i;
            bind(ROT[i], (s, v) -> s.rotation(axis, v));
        }
    }

    private interface Apply {
        void apply(DecorationSettingsScreen screen, float value);
    }

    private static void bind(TextBox box, Apply apply) {
        box.onChange(text -> {
            DecorationSettingsScreen s = current;
            double value = box.number();
            if (s != null && !s.syncing && !Double.isNaN(value)) {
                apply.apply(s, SettingRows.round(value));
                s.serverUpdatePending = true;
            }
        });
    }

    private final DecorationUnit unit;
    private final Port preview;
    private boolean syncing;
    private boolean serverUpdatePending;
    private int serverUpdateCooldown;
    private boolean sliderDragging;
    private boolean renderStateInitialized;
    private long lastInterpolationNanos;
    private float renderScale;
    private float renderXRotation;
    private float renderYRotation;
    private float renderZRotation;
    private Vec3 renderOffset;
    private float viewShiftX;
    private float viewShiftY;
    private float viewScale = 1;
    private float viewRotX;
    private float viewRotY;

    public DecorationSettingsScreen(DecorationUnit unit) {
        super(build(), Component.translatable("screen.decoration_settings.title"));
        this.unit = unit;
        Vector3f rotation = new Vector3f();
        unit.rotation.getEulerAnglesYXZ(rotation);
        this.viewRotX = (float) (180 - Math.toDegrees(rotation.x));
        this.viewRotY = (float) (unit.getVehicle().getYRot() - Math.toDegrees(rotation.y));
        preview = port(new Port.Handler() {
            @Override
            public void drag(int button, double dx, double dy) {
                if (button == 0) {
                    viewRotY += (float) dx;
                    viewRotX += (float) dy;
                } else {
                    viewShiftX += (float) dx;
                    viewShiftY += (float) dy;
                }
            }

            @Override
            public void scroll(double mouseX, double mouseY, double amount) {
                viewScale = Mth.clamp(viewScale * (float) Math.pow(1.1, amount), 0.1f, 10.0f);
            }
        });
        current = this;
        syncing = true;
        SCALE.set(SettingRows.format(unit.scale));
        X.set(SettingRows.format((float) unit.offsetFromBone.x));
        Y.set(SettingRows.format((float) unit.offsetFromBone.y));
        Z.set(SettingRows.format((float) unit.offsetFromBone.z));
        for (int i = 0; i < 3; i++) {
            ROT[i].set(SettingRows.format(rotation(i)));
        }
        syncing = false;
    }

    @Override
    protected void init() {
        current = this;
        super.init();
    }

    @Override
    public void removed() {
        super.removed();
        if (current == this) {
            current = null;
        }
    }

    private float rotation(int axis) {
        return axis == 0 ? unit.selfXRot : axis == 1 ? unit.selfYRot : unit.selfZRot;
    }

    private void rotation(int axis, float value) {
        switch (axis) {
            case 0 -> unit.selfXRot = value;
            case 1 -> unit.selfYRot = value;
            default -> unit.selfZRot = value;
        }
    }

    private void slide(int axis, double fraction) {
        float value = SettingRows.round(fraction * 360 - 180);
        sliderDragging = true;
        rotation(axis, value);
        syncing = true;
        ROT[axis].set(SettingRows.format(value));
        syncing = false;
    }

    private void slid() {
        sliderDragging = false;
        serverUpdatePending = true;
    }

    @Override
    public void tick() {
        super.tick();
        if (serverUpdatePending && !sliderDragging) {
            if (serverUpdateCooldown > 0) {
                serverUpdateCooldown--;
            } else {
                serverUpdatePending = false;
                serverUpdateCooldown = 2;
                send(ClientDecorationAction.Action.SET);
            }
        }
    }

    @Override
    public void onClose() {
        if (serverUpdatePending) {
            serverUpdatePending = false;
            send(ClientDecorationAction.Action.SET);
        }
        super.onClose();
    }

    private void delete() {
        serverUpdatePending = false;
        send(ClientDecorationAction.Action.REMOVE);
        super.onClose();
    }

    private void send(ClientDecorationAction.Action kind) {
        ClientDecorationAction update = new ClientDecorationAction();
        update.action = kind;
        update.displayId = unit.getDisplayId().toString();
        if (StringUtils.isEmpty(update.displayId)) {
            return;
        }
        update.vehicleId = unit.getVehicle().getId();
        update.decorationUnitId = unit.getId();
        if (kind == ClientDecorationAction.Action.SET) {
            update.baseBoneName = unit.baseBoneName;
            update.scale = unit.scale;
            update.selfXRot = unit.selfXRot;
            update.selfYRot = unit.selfYRot;
            update.selfZRot = unit.selfZRot;
            update.offsetFromBone = unit.offsetFromBone;
        }
        PacketDistributor.sendToServer(update);
    }

    private void interpolate() {
        if (!renderStateInitialized) {
            renderScale = unit.scale;
            renderXRotation = unit.selfXRot;
            renderYRotation = unit.selfYRot;
            renderZRotation = unit.selfZRot;
            renderOffset = unit.offsetFromBone;
            renderStateInitialized = true;
            lastInterpolationNanos = System.nanoTime();
            return;
        }
        long now = System.nanoTime();
        float delta = Math.min(0.1f, Math.max(0, (now - lastInterpolationNanos) / 1_000_000_000.0f));
        lastInterpolationNanos = now;
        float alpha = sliderDragging ? 1.0f : 1.0f - (float) Math.exp(-48.0f * delta);
        renderScale = Mth.lerp(alpha, renderScale, unit.scale);
        renderXRotation = Mth.rotLerp(alpha, renderXRotation, unit.selfXRot);
        renderYRotation = Mth.rotLerp(alpha, renderYRotation, unit.selfYRot);
        renderZRotation = Mth.rotLerp(alpha, renderZRotation, unit.selfZRot);
        Vec3 target = unit.offsetFromBone;
        renderOffset = new Vec3(Mth.lerp(alpha, renderOffset.x, target.x), Mth.lerp(alpha, renderOffset.y, target.y),
                Mth.lerp(alpha, renderOffset.z, target.z));
    }

    private void paintPreview(GuiGraphics graphics, Port port) {
        AbstractVehicle vehicle = unit.getVehicle();
        interpolate();
        float scale = (float) (Math.min(port.width(), port.height()) * 1.5 / Math.max(vehicle.getStructureLength(), 3))
                * viewScale;
        PoseStack poseStack = graphics.pose();
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        poseStack.pushPose();
        float targetScale = unit.scale;
        float targetX = unit.selfXRot;
        float targetY = unit.selfYRot;
        float targetZ = unit.selfZRot;
        Vec3 targetOffset = unit.offsetFromBone;
        try {
            poseStack.translate(port.centerX() + viewShiftX, port.centerY() + viewShiftY, 512);
            poseStack.scale(scale, scale, -scale);
            poseStack.mulPose(Axis.XP.rotationDegrees(viewRotX));
            poseStack.mulPose(Axis.YP.rotationDegrees(viewRotY));
            Vec3 centerOffset = vehicle.getBoundingBox().getCenter().subtract(vehicle.position());
            poseStack.translate((float) -centerOffset.x, (float) -centerOffset.y, (float) -centerOffset.z);
            dispatcher.setRenderShadow(false);
            Lighting.setupForEntityInInventory();
            unit.scale = renderScale;
            unit.selfXRot = renderXRotation;
            unit.selfYRot = renderYRotation;
            unit.selfZRot = renderZRotation;
            unit.offsetFromBone = renderOffset;
            RenderSystem.runAsFancy(() -> {
                dispatcher.render(vehicle, 0, 0, 0, 0, 1.0F, poseStack, graphics.bufferSource(), 15728880);
                arrow(poseStack, graphics);
            });
            graphics.flush();
        } finally {
            unit.scale = targetScale;
            unit.selfXRot = targetX;
            unit.selfYRot = targetY;
            unit.selfZRot = targetZ;
            unit.offsetFromBone = targetOffset;
            dispatcher.setRenderShadow(true);
            Lighting.setupFor3DItems();
            poseStack.popPose();
        }
    }

    private void arrow(PoseStack poseStack, GuiGraphics graphics) {
        if (unit.offsetFromVehicle == null || unit.rotation == null) {
            return;
        }
        Vector3f rotation = new Vector3f();
        unit.rotation.getEulerAnglesYXZ(rotation);
        AbstractVehicle vehicle = unit.getVehicle();
        Vec3 root = vehicle.centerOffset;
        poseStack.pushPose();
        poseStack.rotateAround(Axis.YP.rotationDegrees(-vehicle.getViewYRot(1.0F)), (float) root.x, (float) root.y,
                (float) root.z);
        poseStack.rotateAround(Axis.XP.rotationDegrees(vehicle.getViewXRot(1.0F)), (float) root.x, (float) root.y,
                (float) root.z);
        poseStack.rotateAround(Axis.ZP.rotationDegrees(vehicle.getViewZRot(1.0F)), (float) root.x, (float) root.y,
                (float) root.z);
        poseStack.translate(unit.offsetFromVehicle.x, unit.offsetFromVehicle.y, unit.offsetFromVehicle.z);
        poseStack.scale(unit.scale, unit.scale, unit.scale);
        poseStack.rotateAround(Axis.YP.rotation(rotation.y), 0, 0, 0);
        poseStack.rotateAround(Axis.XP.rotation(rotation.x), 0, 0, 0);
        poseStack.rotateAround(Axis.ZP.rotation(rotation.z), 0, 0, 0);
        RenderHelper.renderArrow3D(poseStack, graphics.bufferSource(), 0.15f, 0.3f, 0, 255, 0, 255);
        poseStack.popPose();
    }

    private static UiLayout build() {
        if (layout != null) {
            return layout;
        }
        String ws = "(" + Ore.W + " - 14)";
        String bh = "1005";
        String leftW = pct(ws, 0.42f);
        String rightX = "(14 + " + leftW + ")";
        String rightW = "(" + ws + " - " + leftW + " - 7)";
        float pad = 16;
        String bodyW = Panel.bodyW(leftW, pad);
        String bodyH = Panel.bodyH(bh, pad);
        float step = SettingRows.HEIGHT + SettingRows.GAP;
        String[] keys = {"edit_box.scale", "edit_box.x_shift", "edit_box.y_shift", "edit_box.z_shift"};
        TextBox[] boxes = {SCALE, X, Y, Z};
        String[] angles = {"slider.x_rot", "slider.y_rot", "slider.z_rot"};
        WidgetDef[] rows = new WidgetDef[8];
        for (int i = 0; i < 4; i++) {
            rows[i] = at("0", Ore.f(i * step), SettingRows.number(keys[i], boxes[i], bodyW));
        }
        for (int i = 0; i < 3; i++) {
            int axis = i;
            rows[4 + i] = at("0", Ore.f((4 + i) * step), SettingRows.angle(angles[i],
                    item -> current.rotation(axis), (item, v) -> current.slide(axis, v), () -> current.slid(),
                    ROT[i], bodyW));
        }
        String half = "((" + bodyW + " - 8) / 2)";
        rows[7] = at("0", bodyH + " - 38", stack(
                new OreButton(i -> current.onClose(), i -> true, Ore.Tone.GREEN, Ore.tr("button.done"), half, 38, 14),
                at(half + " + 8", "0", new OreButton(i -> current.delete(), i -> true, Ore.Tone.RED,
                        Ore.tr("button.delete"), half, 38, 14))));
        layout = Ore.layout("decoration_settings", stack(
                new Navbar(Ore.tr("screen.decoration_settings.title"), 22, null),
                at("7", "68", new Panel(leftW, bh, Ore.GOLD, Ore.tr("screen.decoration_settings.controls"), null,
                        pad, stack(rows))),
                at(rightX, "68", new Panel(rightW, bh, Ore.PURPLE, Ore.tr("screen.decoration_settings.preview"), null,
                        0, at("5", "5", new ModelPort(() -> current.preview, (g, p) -> current.paintPreview(g, p),
                        Panel.bodyW(rightW, 0) + " - 10", Panel.bodyH(bh, 0) + " - 10", false))))));
        return layout;
    }
}
