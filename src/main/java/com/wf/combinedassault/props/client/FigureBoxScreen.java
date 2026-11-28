package com.wf.combinedassault.props.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.wf.combinedassault.props.FigureBoxBlock;
import com.wf.combinedassault.props.FigureBoxBlockEntity;
import com.wf.combinedassault.props.message.ClientFigureBoxUpdate;
import com.wf.combinedassault.screen.Check;
import com.wf.combinedassault.screen.ModelPort;
import com.wf.combinedassault.screen.Navbar;
import com.wf.combinedassault.screen.Ore;
import com.wf.combinedassault.screen.OreButton;
import com.wf.combinedassault.screen.OreScreen;
import com.wf.combinedassault.screen.Panel;
import com.wf.combinedassault.screen.Port;
import com.wf.wflib.ui.api.UiLayout;
import com.wf.wflib.ui.api.WidgetDef;
import com.wf.wflib.ui.api.input.TextBox;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.network.PacketDistributor;

import static com.wf.combinedassault.screen.Ore.pct;
import static com.wf.wflib.ui.api.Ui.at;
import static com.wf.wflib.ui.api.Ui.stack;

public class FigureBoxScreen extends OreScreen {
    private static FigureBoxScreen current;
    /** scale, x / y / z shift, x / y / z rotation. */
    private static final TextBox[] BOXES = {SettingRows.number(), SettingRows.number(), SettingRows.number(),
            SettingRows.number(), SettingRows.angle(), SettingRows.angle(), SettingRows.angle()};
    private static UiLayout layout;

    static {
        for (int i = 0; i < BOXES.length; i++) {
            int index = i;
            BOXES[i].onChange(text -> {
                FigureBoxScreen s = current;
                double value = BOXES[index].number();
                if (s != null && !s.syncing && !Double.isNaN(value)) {
                    s.values[index] = SettingRows.round(value);
                    s.serverUpdatePending = true;
                }
            });
        }
    }

    private final FigureBoxBlockEntity box;
    private final float[] values = new float[7];
    private final Port preview;
    private boolean syncing;
    private boolean serverUpdatePending;
    private int serverUpdateCooldown;
    private boolean sliderDragging;

    public FigureBoxScreen(FigureBoxBlockEntity box) {
        super(build(), Component.translatable("screen.figure_box.title"));
        this.box = box;
        values[0] = box.scale;
        values[1] = box.xShift;
        values[2] = box.yShift;
        values[3] = box.zShift;
        values[4] = box.xRot;
        values[5] = box.yRot;
        values[6] = box.zRot;
        preview = port(new Port.Handler() {
            @Override
            public boolean accepts(int button) {
                return false;
            }
        });
        current = this;
        syncing = true;
        for (int i = 0; i < BOXES.length; i++) {
            BOXES[i].set(SettingRows.format(values[i]));
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

    private void toggleOpen() {
        box.open = !box.open;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            minecraft.level.setBlock(box.getBlockPos(), box.getBlockState().setValue(FigureBoxBlock.OPEN, box.open), 3);
        }
        send();
    }

    private void slide(int index, double fraction) {
        float value = SettingRows.round(fraction * 360 - 180);
        sliderDragging = true;
        values[index] = value;
        syncing = true;
        BOXES[index].set(SettingRows.format(value));
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
                send();
            }
        }
    }

    @Override
    public void onClose() {
        if (serverUpdatePending) {
            serverUpdatePending = false;
            send();
        }
        super.onClose();
    }

    private void send() {
        ClientFigureBoxUpdate update = new ClientFigureBoxUpdate();
        update.blockPos = box.getBlockPos();
        update.open = box.open;
        update.scale = values[0];
        update.xShift = values[1];
        update.yShift = values[2];
        update.zShift = values[3];
        update.xRot = values[4];
        update.yRot = values[5];
        update.zRot = values[6];
        PacketDistributor.sendToServer(update);
    }

    /** Block entity eases toward the typed values; follows the range exactly while dragging. */
    private void ease(float partialTick) {
        float t = sliderDragging ? 1 : partialTick;
        box.scale = Mth.lerp(t, box.scale, values[0]);
        box.xShift = Mth.lerp(t, box.xShift, values[1]);
        box.yShift = Mth.lerp(t, box.yShift, values[2]);
        box.zShift = Mth.lerp(t, box.zShift, values[3]);
        box.xRot = Mth.rotLerp(t, box.xRot, values[4]);
        box.yRot = Mth.rotLerp(t, box.yRot, values[5]);
        box.zRot = Mth.rotLerp(t, box.zRot, values[6]);
    }

    private void paintPreview(GuiGraphics graphics, Port port) {
        ease(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false));
        double scale = Math.min(port.width(), port.height()) * 0.72 / Math.max(0.01f, box.scale);
        PoseStack poseStack = graphics.pose();
        poseStack.pushPose();
        try {
            Lighting.setupForEntityInInventory();
            poseStack.translate(port.centerX(), port.centerY(), 512);
            poseStack.scale((float) scale, (float) scale, (float) -scale);
            poseStack.mulPose(Axis.XP.rotationDegrees(210));
            BlockState state = box.getBlockState();
            float facing = state.isAir() ? 0 : switch (state.getValue(FigureBoxBlock.FACING)) {
                case NORTH -> 180;
                case WEST -> -90;
                case EAST -> 90;
                default -> 0;
            };
            poseStack.mulPose(Axis.YP.rotationDegrees(-15 - facing));
            poseStack.translate(-0.5, -0.5, -0.5);
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, poseStack, graphics.bufferSource(),
                    15728880, OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
            if (box.getEntity() != null) {
                FigureBoxBlockRenderer.renderEntity(poseStack, box, graphics.bufferSource(), 15728880);
            }
            graphics.flush();
        } finally {
            Lighting.setupFor3DItems();
            poseStack.popPose();
        }
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
        String[] angles = {"slider.x_rot", "slider.y_rot", "slider.z_rot"};
        WidgetDef[] rows = new WidgetDef[9];
        for (int i = 0; i < 4; i++) {
            rows[i] = at("0", Ore.f(i * step), SettingRows.number(keys[i], BOXES[i], bodyW));
        }
        for (int i = 0; i < 3; i++) {
            int index = 4 + i;
            rows[index] = at("0", Ore.f(index * step), SettingRows.angle(angles[i],
                    item -> current.values[index], (item, v) -> current.slide(index, v), () -> current.slid(),
                    BOXES[index], bodyW));
        }
        rows[7] = at("0", Ore.f(7 * step + 8), new Check(i -> current.box.open, i -> current.toggleOpen(),
                Ore.tr("check_box.figure_box.open"), 17));
        rows[8] = at("0", bodyH + " - 38", new OreButton(i -> current.onClose(), i -> true, Ore.Tone.GREEN,
                Ore.tr("button.done"), bodyW, 38, 14));
        layout = Ore.layout("figure_box", stack(
                new Navbar(Ore.tr("screen.figure_box.title"), 22, null),
                at("7", "68", new Panel(leftW, bh, Ore.GOLD, Ore.tr("screen.figure_box.settings"), null, pad,
                        stack(rows))),
                at(rightX, "68", new Panel(rightW, bh, Ore.PURPLE, Ore.tr("screen.figure_box.preview"), null, 0,
                        at("5", "5", new ModelPort(() -> current.preview, (g, p) -> current.paintPreview(g, p),
                                Panel.bodyW(rightW, 0) + " - 10", Panel.bodyH(bh, 0) + " - 10", false))))));
        return layout;
    }
}
