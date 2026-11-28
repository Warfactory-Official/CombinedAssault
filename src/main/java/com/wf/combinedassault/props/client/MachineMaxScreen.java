package com.wf.combinedassault.props.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.pack.client.ClientAssetsManager;
import com.wf.combinedassault.pack.client.vehicle.BaseDisplay;
import com.wf.combinedassault.props.MachineMaxBlockEntity;
import com.wf.combinedassault.props.message.ClientMachineMaxAction;
import com.wf.combinedassault.render.gem.GemRenderPreview;
import com.wf.combinedassault.screen.Badge;
import com.wf.combinedassault.screen.Field;
import com.wf.combinedassault.screen.Label;
import com.wf.combinedassault.screen.Meter;
import com.wf.combinedassault.screen.ModelPort;
import com.wf.combinedassault.screen.Navbar;
import com.wf.combinedassault.screen.Ore;
import com.wf.combinedassault.screen.OreButton;
import com.wf.combinedassault.screen.OreScreen;
import com.wf.combinedassault.screen.Panel;
import com.wf.combinedassault.screen.Paragraph;
import com.wf.combinedassault.screen.Pick;
import com.wf.combinedassault.screen.Port;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.recipe.VehiclePrintingIngredient;
import com.wf.combinedassault.vehicle.recipe.VehiclePrintingRecipe;
import com.wf.wflib.ui.api.Binder;
import com.wf.wflib.ui.api.Signals;
import com.wf.wflib.ui.api.Subject;
import com.wf.wflib.ui.api.UiEval;
import com.wf.wflib.ui.api.UiLayout;
import com.wf.wflib.ui.api.Widget;
import com.wf.wflib.ui.api.WidgetDef;
import com.wf.wflib.ui.api.input.TextBox;
import com.wf.wflib.ui.api.widget.Align;
import com.wf.wflib.ui.api.widget.Box;
import com.wf.wflib.ui.api.widget.Item;
import com.wf.wflib.ui.api.widget.ListView;
import com.wf.wflib.ui.api.widget.Place;
import com.wf.wflib.ui.api.widget.Point;
import com.wf.wflib.ui.api.widget.Rect;
import com.wf.wflib.ui.api.widget.Repeat;
import com.wf.wflib.ui.api.widget.Sized;
import com.wf.wflib.ui.api.widget.Tooltip;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.wf.combinedassault.screen.Ore.pct;
import static com.wf.wflib.ui.api.Ui.at;
import static com.wf.wflib.ui.api.Ui.stack;
import static com.wf.wflib.ui.api.Ui.when;

public class MachineMaxScreen extends OreScreen {
    private static MachineMaxScreen current;
    private static final Subject<MachineMaxScreen> SCREEN =
            Subject.register("ca_machine_max", MachineMaxScreen.class, () -> current);
    private static final Subject<Entry> ENTRY = Subject.register("ca_machine_max_vehicle", Entry.class);
    private static final Subject<Material> MATERIAL = Subject.register("ca_machine_max_material", Material.class);
    private static final TextBox SEARCH = TextBox.text(48);
    private static final float ROW = 65;
    private static UiLayout layout;

    static {
        Signals.items(SCREEN, "vehicles", s -> s.visible, ENTRY);
        Signals.items(SCREEN, "materials", s -> s.materials, MATERIAL);
        Signals.bool(SCREEN, "none", s -> s.visible.isEmpty());
        Signals.bool(SCREEN, "no_recipe", s -> s.recipe == null);
        SEARCH.onChange(text -> {
            if (current != null) {
                current.filter(text);
            }
        });
    }

    private enum State { READY, PRINTING, PRODUCT_READY, NO_RECIPE, NO_SELECTION }

    private final MachineMaxBlockEntity machine;
    private final List<Entry> all = new ArrayList<>();
    private final List<Entry> visible = new ArrayList<>();
    private final List<Material> materials = new ArrayList<>();
    private final Port preview;
    private Entry selected;
    private AbstractVehicle previewVehicle;
    private VehiclePrintingRecipe recipe;
    private String count;
    private String name;
    private String code;
    private String description;
    private String progressLabel;
    private String progressValue;
    private String productNote;
    private int shownProgress = -1;
    private State shownState;
    private double zoom = 1.0;
    private double panX;
    private double panY;
    private float rotX = 165.0F;
    private float rotY;

    public MachineMaxScreen(MachineMaxBlockEntity machine) {
        super(build(), Component.translatable("block.combinedassault.machine_max_block"));
        this.machine = machine;
        CommonAssetsManager.vehicleDataManager().getVehicleData().values().forEach(vehicle -> {
            BaseVehicleData<?> data = (BaseVehicleData<?>) vehicle;
            if (!data.isExperimental()) {
                all.add(new Entry(data));
            }
        });
        all.sort((left, right) -> left.name.compareToIgnoreCase(right.name));
        preview = port(new Port.Handler() {
            @Override
            public boolean accepts(int button) {
                return previewVehicle != null && (button == 0 || button == 1);
            }

            @Override
            public void drag(int button, double dx, double dy) {
                if (button == 0) {
                    rotY -= (float) dx;
                    rotX -= (float) dy;
                } else {
                    panX += dx;
                    panY += dy;
                    clampPan();
                }
            }

            @Override
            public void scroll(double mouseX, double mouseY, double amount) {
                if (previewVehicle == null) {
                    return;
                }
                double old = zoom;
                zoom = Mth.clamp(zoom * Math.pow(1.15, amount), 0.35, 4.0);
                double ratio = zoom / old;
                double baseX = preview.centerX();
                double baseY = modelCenterY();
                panX = mouseX + (baseX + panX - mouseX) * ratio - baseX;
                panY = mouseY + (baseY + panY - mouseY) * ratio - baseY;
                clampPan();
            }
        });
        current = this;
        SEARCH.set("");
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

    private double modelCenterY() {
        return preview.centerY() + preview.height() * 0.08;
    }

    private void clampPan() {
        panX = Mth.clamp(panX, -preview.width() * 0.45, preview.width() * 0.45);
        panY = Mth.clamp(panY, -preview.height() * 0.45, preview.height() * 0.45);
    }

    private void filter(String text) {
        String query = text.strip().toLowerCase(Locale.ROOT);
        visible.clear();
        for (Entry entry : all) {
            if (query.isEmpty() || entry.search.contains(query)) {
                visible.add(entry);
            }
        }
        count = visible.size() + "/" + all.size();
        if (selected != null && visible.contains(selected)) {
            return;
        }
        select(visible.isEmpty() ? null : visible.get(0), false);
    }

    private void select(Entry entry, boolean sound) {
        if (entry == selected && entry != null) {
            return;
        }
        selected = entry;
        zoom = 1.0;
        panX = 0;
        panY = 0;
        rotX = 165.0F;
        rotY = 0;
        materials.clear();
        recipe = null;
        Minecraft minecraft = Minecraft.getInstance();
        if (entry == null || minecraft.level == null) {
            previewVehicle = null;
            name = Component.translatable("screen.machine_max.preview").getString();
            code = "--";
            description = Component.translatable("screen.machine_max.select_vehicle").getString();
            refresh(true);
            return;
        }
        previewVehicle = entry.data.construct(minecraft.level, Vec3.ZERO, 0, 0);
        previewVehicle.initData();
        previewVehicle.initDisplayData();
        name = entry.name;
        code = entry.data.getVehicleId().getPath();
        description = Component.translatable(ClientAssetsManager.INSTANCE.getVehicleDisplay(previewVehicle.getDisplayId())
                .map(BaseDisplay::getDescription).filter(value -> value != null && !value.isBlank())
                .orElse("screen.no_description")).getString();
        Optional<RecipeHolder<?>> holder = minecraft.level.getRecipeManager().byKey(entry.data.getVehicleId());
        if (holder.isPresent() && holder.get().value() instanceof VehiclePrintingRecipe printing) {
            recipe = printing;
            for (VehiclePrintingIngredient input : printing.getInputs()) {
                ItemStack[] choices = input.ingredient().getItems();
                if (choices.length > 0) {
                    materials.add(new Material(input, choices));
                }
            }
        }
        counts();
        if (sound) {
            click();
        }
        refresh(true);
    }

    private void counts() {
        if (materials.isEmpty() || Minecraft.getInstance().player == null) {
            return;
        }
        List<ItemStack> items = Minecraft.getInstance().player.getInventory().items;
        for (Material material : materials) {
            int owned = 0;
            for (ItemStack stack : items) {
                if (!stack.isEmpty() && material.input.ingredient().test(stack)) {
                    owned += stack.getCount();
                }
            }
            material.owned(owned);
        }
    }

    @Override
    public void tick() {
        super.tick();
        counts();
        refresh(false);
    }

    private State state() {
        if (machine.hasProduct()) {
            return State.PRODUCT_READY;
        }
        if (machine.isCrafting()) {
            return State.PRINTING;
        }
        if (selected == null) {
            return State.NO_SELECTION;
        }
        return recipe == null ? State.NO_RECIPE : State.READY;
    }

    private void refresh(boolean force) {
        int progress = Math.max(0, Math.min(100, Math.round(machine.progress * 100)));
        State state = state();
        if (!force && progress == shownProgress && state == shownState) {
            return;
        }
        shownProgress = progress;
        shownState = state;
        progressLabel = state == State.PRINTING
                ? Component.translatable("screen.machine_max.progress.crafting", craftingName()).getString()
                : Component.translatable("screen.machine_max.progress").getString();
        progressValue = progress + "%";
        productNote = machine.hasProduct() ? Component.translatable("tips.machine_max_product").getString() : "";
    }

    private String craftingName() {
        if (machine.craftingVehicleId == null) {
            return "--";
        }
        return CommonAssetsManager.vehicleDataManager().getVehicleData(machine.craftingVehicleId)
                .map(data -> data.getName().getString()).orElse(machine.craftingVehicleId.getPath());
    }

    private void craft() {
        if (state() != State.READY) {
            return;
        }
        ClientMachineMaxAction action = new ClientMachineMaxAction();
        action.craftingVehicleId = selected.data.getVehicleId();
        action.blockPos = machine.getBlockPos();
        action.action = ClientMachineMaxAction.Action.CRAFT;
        PacketDistributor.sendToServer(action);
    }

    private void paintPreview(GuiGraphics graphics, Port port) {
        if (previewVehicle == null) {
            return;
        }
        double centerX = port.centerX() + panX;
        double centerY = modelCenterY() + panY;
        float scale = (float) (Math.min(port.width(), port.height()) * 0.78
                / Math.max(previewVehicle.getStructureLength(), 3) * zoom);
        PoseStack poseStack = graphics.pose();
        EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
        poseStack.pushPose();
        try {
            poseStack.translate((float) centerX, (float) centerY, 512);
            poseStack.mulPose(Axis.XP.rotationDegrees(rotX));
            poseStack.mulPose(Axis.YP.rotationDegrees(rotY));
            poseStack.scale(scale, scale, -scale);
            dispatcher.setRenderShadow(false);
            Lighting.setupForEntityInInventory();
            RenderSystem.runAsFancy(() -> dispatcher.render(previewVehicle, 0, 0, 0, previewVehicle.getYRot(), 1.0F,
                    poseStack, graphics.bufferSource(), 15728880));
            graphics.flush();
            GemRenderPreview.draw(previewVehicle, poseStack, 15728880);
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
        String leftW = pct(ws, 0.25f);
        String rightW = pct(ws, 0.30f);
        String midX = "(14 + " + leftW + ")";
        String midW = "(" + ws + " - " + leftW + " - " + rightW + " - 14)";
        String rightX = "(" + midX + " + " + midW + " + 7)";
        String profileH = "((" + bh + " - 7) * 0.4)";
        String fabH = "(" + bh + " - 7 - " + profileH + ")";
        float pad = 16;
        String listW = Panel.bodyW(leftW, pad);
        String listH = Panel.bodyH(bh, pad) + " - 48";
        String rowW = listW + " - 6";
        String profW = Panel.bodyW(rightW, pad);
        String fabW = Panel.bodyW(rightW, pad);
        String fabBody = Panel.bodyH(fabH, pad);
        String matW = "((" + fabW + " - 4) / 2)";

        WidgetDef vehicleRow = new Pick(i -> current.select((Entry) i, true), i -> i == current.selected, rowW,
                Ore.f(ROW), Pick.Look.ITEM, 12, 12, stack(
                Label.plain(i -> ((Entry) i).name, Ore.WHITE, 19).styled(Ore.BOLD).clipped(rowW + " - 24"),
                at("0", "26", Label.plain(i -> ((Entry) i).code, Ore.INK_MUTED, 14).clipped(rowW + " - 24"))));

        WidgetDef materialRow = new Tooltip(i -> ((Material) i).tooltip(), stack(
                new Rect(matW, "50", "#CC3B3C3E", 0, 0),
                new Rect(matW, "50", "#FF1E1E1F", 0, 2),
                at("8", "8", new Slot()),
                at("48", "9", Label.plain(i -> ((Material) i).name, Ore.WHITE, 18).clipped(matW + " - 116")),
                at("48", "29", Label.plain(i -> ((Material) i).options, Ore.INK_MUTED, 11)),
                new Place(matW + " - 8", "17", Point.TOP_RIGHT,
                        Label.display(i -> ((Material) i).count, Ore.GOLD, 17))));

        layout = Ore.layout("machine_max", stack(
                new Navbar(Ore.tr("block.combinedassault.machine_max_block"), 19, i -> current.onClose()),
                at("7", "68", new Panel(leftW, bh, Ore.GREEN, Ore.tr("screen.machine_max.catalog"),
                        new Badge(i -> current.count, Ore.BLUE, 14), pad, stack(
                        new Field(SEARCH, "Search", listW, 42, 12, Ore.WHITE),
                        at("0", "48", new Box("#00000000", "#FF1E1E1F", 3, 0, 3, 3, 0, 0, Point.TOP_LEFT,
                                new ListView("ca_machine_max.vehicles", ROW, 0, rowW, listH + " - 6", "#FF8A8C90",
                                        vehicleRow))),
                        when("ca_machine_max.none", at("0", "48",
                                Ore.note("screen.machine_max.no_results", listW)))))),
                at(midX, "68", new Panel(midW, bh, Ore.PURPLE, Ore.tr("screen.machine_max.preview"),
                        new Badge(Label.of("3D"), Ore.PURPLE, 14), 0,
                        at("5", "5", new ModelPort(() -> current.preview, (g, p) -> current.paintPreview(g, p),
                                Panel.bodyW(midW, 0) + " - 10", Panel.bodyH(bh, 0) + " - 10", true)))),
                at(rightX, "68", new Panel(rightW, profileH, Ore.PURPLE, Ore.tr("screen.machine_max.profile"), null,
                        pad, stack(
                        Label.display(i -> current.name, Ore.WHITE, 22).clipped(profW + " * 0.5"),
                        new Place(profW, "-4", Point.TOP_RIGHT,
                                new Badge(i -> current.code, Ore.PURPLE, 14, profW + " * 0.46 - 20")),
                        at("0", "34", new Paragraph(i -> current.description, profW,
                                Panel.bodyH(profileH, pad) + " - 34", Ore.INK_MUTED, 17, 23, Ore.BOLD))))),
                at(rightX, "(75 + " + profileH + ")", new Panel(rightW, fabH, Ore.GOLD,
                        Ore.tr("screen.machine_max.fabrication"), null, pad, stack(
                        Label.display(Ore.tr("screen.machine_max.requirements"), Ore.GOLD, 14),
                        at("0", "24", new Repeat("ca_machine_max.materials", false, 4, Align.START, 0, 2, 0,
                                new Sized(matW, "50", Point.TOP_LEFT, false, materialRow))),
                        when("ca_machine_max.no_recipe", at("0", "24", Ore.note("tips.no_recipe", fabW))),
                        at("0", fabBody + " - 126", Label.plain(i -> current.progressLabel, Ore.INK, 14)
                                .styled(Ore.BOLD).clipped(fabW + " - 60")),
                        new Place(fabW, fabBody + " - 126", Point.TOP_RIGHT,
                                Label.plain(i -> current.progressValue, Ore.INK, 14).styled(Ore.BOLD)),
                        at("0", fabBody + " - 104", new Meter(i -> current.machine.progress, fabW, 16)),
                        new Place(fabW + " / 2", fabBody + " - 72", Point.TOP,
                                Label.plain(i -> current.productNote, Ore.GOLD, 14).styled(Ore.BOLD)),
                        at("16", fabBody + " - 50", new OreButton(i -> current.craft(),
                                i -> current.state() == State.READY, Ore.Tone.GREEN,
                                Ore.tr("button.machine_max.printing"), fabW + " - 32", 38, 14)))))));
        return layout;
    }

    /** Requirement slot: 34 px, vanilla slot bevel, cycling ingredient icon. */
    private record Slot() implements WidgetDef {
        @Override
        public Widget create(Binder binder) {
            Widget icon = binder.child(new Item(i -> ((Material) i).shown(), 32, false));
            return new Widget() {
                @Override
                public void measure(UiEval ev) {
                    width = 34;
                    height = 34;
                    icon.measure(ev);
                }

                @Override
                public void draw(UiEval ev, float x, float y, float w, float h) {
                    ev.canvas.fill(x, y, x + 34, y + 34, 0xFF8B8B8B);
                    ev.canvas.fill(x, y, x + 33, y + 1, 0xFF373737);
                    ev.canvas.fill(x, y, x + 1, y + 33, 0xFF373737);
                    ev.canvas.fill(x + 1, y + 33, x + 34, y + 34, 0xFFFFFFFF);
                    ev.canvas.fill(x + 33, y + 1, x + 34, y + 34, 0xFFFFFFFF);
                    icon.draw(ev, x + 1, y + 1, 32, 32);
                }
            };
        }
    }

    private static final class Entry {
        final BaseVehicleData<?> data;
        final String name;
        final String code;
        final String search;

        Entry(BaseVehicleData<?> data) {
            this.data = data;
            this.name = data.getName().getString();
            this.code = data.getVehicleId().toString();
            this.search = (name + " " + code).toLowerCase(Locale.ROOT);
        }
    }

    private static final class Material {
        final VehiclePrintingIngredient input;
        final ItemStack[] choices;
        final String name;
        final String options;
        private int owned = -1;
        String count;

        Material(VehiclePrintingIngredient input, ItemStack[] choices) {
            this.input = input;
            this.choices = choices;
            this.name = choices[0].getHoverName().getString();
            this.options = choices.length > 1
                    ? Component.translatable("screen.machine_max.options", choices.length).getString() : "";
        }

        void owned(int value) {
            if (value != owned) {
                owned = value;
                count = value + "/" + input.count();
            }
        }

        ItemStack shown() {
            return choices[(int) (Util.getMillis() / 900 % choices.length)];
        }

        List<Component> tooltip() {
            return Screen.getTooltipFromItem(Minecraft.getInstance(), shown());
        }
    }
}
