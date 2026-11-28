package com.wf.combinedassault.weapon.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.wf.combinedassault.pack.client.ClientAssetsManager;
import com.wf.combinedassault.pack.client.vehicle.BaseDisplay;
import com.wf.combinedassault.render.util.RenderHelper;
import com.wf.combinedassault.screen.Badge;
import com.wf.combinedassault.screen.Label;
import com.wf.combinedassault.screen.ModelPort;
import com.wf.combinedassault.screen.Navbar;
import com.wf.combinedassault.screen.Ore;
import com.wf.combinedassault.screen.OreScreen;
import com.wf.combinedassault.screen.Panel;
import com.wf.combinedassault.screen.Paragraph;
import com.wf.combinedassault.screen.Pick;
import com.wf.combinedassault.screen.Port;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.weapon.message.ClientVehicleSelectPartWeapon;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.wflib.ui.api.Signals;
import com.wf.wflib.ui.api.Subject;
import com.wf.wflib.ui.api.UiLayout;
import com.wf.wflib.ui.api.WidgetDef;
import com.wf.wflib.ui.api.widget.ListView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

import static com.wf.combinedassault.screen.Ore.pct;
import static com.wf.wflib.ui.api.Ui.at;
import static com.wf.wflib.ui.api.Ui.stack;
import static com.wf.wflib.ui.api.Ui.when;

public class VehicleWeaponSelectScreen extends OreScreen {
    private static VehicleWeaponSelectScreen current;
    private static final Subject<VehicleWeaponSelectScreen> SCREEN =
            Subject.register("ca_weapon_select", VehicleWeaponSelectScreen.class, () -> current);
    private static final Subject<Selection> SELECTION = Subject.register("ca_weapon_select_part", Selection.class);
    private static final Subject<WeaponRow> WEAPON = Subject.register("ca_weapon_select_weapon", WeaponRow.class);
    private static UiLayout layout;

    static {
        Signals.items(SCREEN, "parts", s -> s.selections, SELECTION);
        Signals.items(SCREEN, "weapons", s -> s.weapons, WEAPON);
        Signals.bool(SCREEN, "no_parts", s -> s.selections.isEmpty());
        Signals.bool(SCREEN, "no_weapons", s -> s.weapons.isEmpty());
    }

    private final AbstractVehicle vehicle;
    private final Screen parent;
    private final List<Selection> selections = new ArrayList<>();
    private final List<WeaponRow> weapons = new ArrayList<>();
    private final Port preview;
    private Selection selected;
    private int selectedWeapon = -1;
    private String partName;
    private String description;
    private float viewShiftX;
    private float viewShiftY;
    private float viewScale = 1;
    private float viewRotX;
    private float viewRotY;

    public VehicleWeaponSelectScreen(AbstractVehicle vehicle, Screen parent) {
        super(build(), Component.translatable("screen.vehicle_weapon_select.title"));
        this.vehicle = vehicle;
        this.parent = parent;
        this.viewRotX = 180 - vehicle.getXRot();
        this.viewRotY = 180 + LocalVehiclePlayer.instance().getPlayer().getYRot();
        for (PartUnit<?> partUnit : vehicle.getPartUnits()) {
            if (partUnit instanceof WeaponUnit unit && unit.isInteractive() && unit.weapons.size() > 1) {
                selections.add(new Selection(partUnit.getIndex(), unit));
            }
        }
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
        select(selections.isEmpty() ? null : selections.get(0));
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

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    private void select(Selection selection) {
        selected = selection;
        weapons.clear();
        if (selection == null) {
            partName = Component.translatable("screen.vehicle_weapon_select.select_part").getString();
            description = Component.translatable("screen.no_description").getString();
            return;
        }
        for (int i = 0; i < selection.unit.weapons.size(); i++) {
            weapons.add(new WeaponRow(i, selection.unit.proxyWeapon(selection.unit.weapons.get(i))
                    .getDisplayName().getString()));
        }
        selectedWeapon = selection.unit.getCurrentWeaponIndex();
        partName = selection.name;
        describe();
    }

    private void describe() {
        String key = "screen.no_description";
        if (selectedWeapon >= 0 && selectedWeapon < selected.unit.weapons.size()) {
            key = ClientAssetsManager.INSTANCE.getWeaponDisplay(selected.unit.proxyWeapon(
                            selected.unit.weapons.get(selectedWeapon)).getData().getWeaponId())
                    .map(BaseDisplay::getDescription).filter(text -> text != null && !text.isBlank()).orElse(key);
        }
        description = Component.translatable(key).getString();
    }

    private void pickWeapon(int index) {
        selectedWeapon = index;
        describe();
        PacketDistributor.sendToServer(new ClientVehicleSelectPartWeapon(vehicle.getId(), selected.partUnitIndex, index));
        click();
    }

    private void paintPreview(GuiGraphics graphics, Port port) {
        double scale = Math.min(port.width(), port.height()) * 1.5 / Math.max(vehicle.getStructureLength(), 3)
                * viewScale;
        PoseStack poseStack = graphics.pose();
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        poseStack.pushPose();
        try {
            poseStack.translate(port.centerX() + viewShiftX, port.centerY() + viewShiftY, 512);
            poseStack.scale((float) scale, (float) scale, (float) -scale);
            poseStack.mulPose(Axis.XP.rotationDegrees(viewRotX));
            poseStack.mulPose(Axis.YP.rotationDegrees(viewRotY));
            Vec3 centerOffset = vehicle.getBoundingBox().getCenter().subtract(vehicle.position());
            poseStack.translate((float) -centerOffset.x, (float) -centerOffset.y, (float) -centerOffset.z);
            dispatcher.setRenderShadow(false);
            Lighting.setupForEntityInInventory();
            RenderSystem.runAsFancy(() ->
                    dispatcher.render(vehicle, 0, 0, 0, 0, 1.0F, poseStack, graphics.bufferSource(), 15728880));
            graphics.flush();
            if (selected != null) {
                RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, false);
                Vec3 arrow = selected.unit.aimContext().from.subtract(vehicle.position());
                poseStack.pushPose();
                poseStack.translate(arrow.x, arrow.y, arrow.z);
                RenderHelper.renderArrow3D(poseStack, graphics.bufferSource(), 0.15f, 0.3f, 0, 255, 0, 255);
                poseStack.popPose();
            }
        } finally {
            dispatcher.setRenderShadow(true);
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
        String leftW = pct(ws, 0.22f);
        String midW = pct(ws, 0.28f);
        String midX = "(14 + " + leftW + ")";
        String rightX = "(" + midX + " + " + midW + " + 7)";
        String rightW = "(" + ws + " - " + leftW + " - " + midW + " - 14)";
        String previewH = "((" + bh + " - 7) * 0.6)";
        String descH = "(" + bh + " - 7 - " + previewH + ")";
        float pad = 16;
        String leftBody = Panel.bodyW(leftW, pad);
        String midBody = Panel.bodyW(midW, pad);

        WidgetDef partRow = new Pick(i -> {
            current.select((Selection) i);
            click();
        }, i -> i == current.selected, leftBody, "38", Pick.Look.ENTRY, 9, 0, stack(
                at("0", "10", Label.plain(i -> ((Selection) i).name, Ore.WHITE, 19).clipped(leftBody + " - 70")),
                at(leftBody + " - 58", "12", Label.plain(i -> ((Selection) i).count(), Ore.INK_MUTED, 14))));
        WidgetDef weaponRow = new Pick(i -> current.pickWeapon(((WeaponRow) i).index),
                i -> ((WeaponRow) i).index == current.selectedWeapon, midBody, "38", Pick.Look.ENTRY, 9, 10,
                Label.plain(i -> ((WeaponRow) i).name, Ore.WHITE, 19).clipped(midBody + " - 18"));

        layout = Ore.layout("vehicle_weapon_select", stack(
                new Navbar(Ore.tr("screen.vehicle_weapon_select.title"), 22, i -> current.onClose()),
                at("7", "68", new Panel(leftW, bh, Ore.GREEN, Ore.tr("screen.vehicle_weapon_select.weapon_selections"),
                        null, pad, stack(
                        new ListView("ca_weapon_select.parts", 38, 7, leftBody, Panel.bodyH(bh, pad), "#FF8A8C90",
                                partRow),
                        when("ca_weapon_select.no_parts", Ore.note("screen.vehicle_weapon_select.no_selections", leftBody))))),
                at(midX, "68", new Panel(midW, bh, Ore.GOLD, i -> current.partName, null, pad, stack(
                        new ListView("ca_weapon_select.weapons", 38, 7, midBody, Panel.bodyH(bh, pad), "#FF8A8C90",
                                weaponRow),
                        when("ca_weapon_select.no_weapons", Ore.note("screen.vehicle_weapon_select.no_weapons", midBody))))),
                at(rightX, "68", new Panel(rightW, previewH, Ore.PURPLE, Ore.tr("screen.vehicle_weapon_select.preview"),
                        new Badge(Label.of("3D"), Ore.PURPLE, 14), 0,
                        at("5", "5", new ModelPort(() -> current.preview, (g, p) -> current.paintPreview(g, p),
                                Panel.bodyW(rightW, 0) + " - 10", Panel.bodyH(previewH, 0) + " - 10", false)))),
                at(rightX, "(75 + " + previewH + ")", new Panel(rightW, descH, Ore.PURPLE,
                        Ore.tr("screen.vehicle_weapon_select.description"), null, pad,
                        new Paragraph(i -> current.description, Panel.bodyW(rightW, pad), Panel.bodyH(descH, pad),
                                Ore.INK_MUTED, 17, 23, Ore.BOLD)))));
        return layout;
    }

    private static final class Selection {
        final int partUnitIndex;
        final WeaponUnit unit;
        final String name;
        private int countFor = -1;
        private String count;

        Selection(int partUnitIndex, WeaponUnit unit) {
            this.partUnitIndex = partUnitIndex;
            this.unit = unit;
            this.name = unit.getName().getString();
        }

        String count() {
            int index = unit.getCurrentWeaponIndex();
            if (index != countFor) {
                countFor = index;
                count = (index + 1) + "/" + unit.weapons.size();
            }
            return count;
        }
    }

    private record WeaponRow(int index, String name) {
    }
}
