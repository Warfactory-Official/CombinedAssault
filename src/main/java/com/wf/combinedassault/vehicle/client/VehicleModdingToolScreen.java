package com.wf.combinedassault.vehicle.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.wf.combinedassault.aircraft.AircraftConfig;
import com.wf.combinedassault.pack.client.ClientAssetsManager;
import com.wf.combinedassault.pack.client.vehicle.BaseDisplay;
import com.wf.combinedassault.render.gem.GemRenderPreview;
import com.wf.combinedassault.screen.Badge;
import com.wf.combinedassault.screen.Field;
import com.wf.combinedassault.screen.Label;
import com.wf.combinedassault.screen.ModelPort;
import com.wf.combinedassault.screen.Navbar;
import com.wf.combinedassault.screen.Ore;
import com.wf.combinedassault.screen.OreButton;
import com.wf.combinedassault.screen.OreScreen;
import com.wf.combinedassault.screen.Panel;
import com.wf.combinedassault.screen.Paragraph;
import com.wf.combinedassault.screen.Pick;
import com.wf.combinedassault.screen.Port;
import com.wf.combinedassault.screen.Range;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.message.ClientVehicleChangeDisplay;
import com.wf.combinedassault.weapon.client.VehicleWeaponSelectScreen;
import com.wf.combinedassault.weapon.message.ClientVehicleSelectLoadout;
import com.wf.combinedassault.weapon.part.WeaponUnit;
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
import com.wf.wflib.ui.api.widget.ListView;
import com.wf.wflib.ui.api.widget.Point;
import com.wf.wflib.ui.api.widget.Sized;
import com.wf.wflib.ui.api.widget.Tooltip;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static com.wf.combinedassault.screen.Ore.pct;
import static com.wf.wflib.ui.api.Ui.at;
import static com.wf.wflib.ui.api.Ui.column;
import static com.wf.wflib.ui.api.Ui.row;
import static com.wf.wflib.ui.api.Ui.stack;
import static com.wf.wflib.ui.api.Ui.when;

public class VehicleModdingToolScreen extends OreScreen {
    private static VehicleModdingToolScreen current;
    private static final Subject<VehicleModdingToolScreen> SCREEN =
            Subject.register("ca_modding_tool", VehicleModdingToolScreen.class, () -> current);
    private static final Subject<Display> DISPLAY = Subject.register("ca_modding_tool_display", Display.class);
    private static final TextBox SEARCH = TextBox.text(48);
    private static final TextBox[] SMOKE = {TextBox.integer(3, 0, 255), TextBox.integer(3, 0, 255),
            TextBox.integer(3, 0, 255)};
    private static UiLayout layout;

    static {
        Signals.items(SCREEN, "displays", s -> s.visible, DISPLAY);
        Signals.bool(SCREEN, "none", s -> s.visible.isEmpty());
        Signals.bool(SCREEN, "loadouts", s -> !s.vehicle.loadouts().isEmpty());
        SEARCH.onChange(text -> {
            if (current != null) {
                current.filter(text);
            }
        });
        for (int i = 0; i < 3; i++) {
            int channel = i;
            SMOKE[i].onChange(text -> {
                long value = SMOKE[channel].integer(-1);
                if (value >= 0) {
                    smoke(channel).set((int) Mth.clamp(value, 0, 255));
                }
            });
        }
    }

    private final AbstractVehicle vehicle;
    private final List<Display> displays = new ArrayList<>();
    private final List<Display> visible = new ArrayList<>();
    private final boolean weaponSelections;
    private final Port preview;
    private Display selected;
    private String name;
    private String namespace;
    private String description;
    private String loadoutText;
    /** Shown until the server's sync lands. */
    private int loadoutShown;
    private float viewShiftX;
    private float viewShiftY;
    private float viewScale = 1;
    private float viewRotX;
    private float viewRotY;

    public VehicleModdingToolScreen(AbstractVehicle vehicle) {
        super(build(), Component.translatable("screen.vehicle_modding_tool.title"));
        this.vehicle = vehicle;
        this.viewRotX = 180 - vehicle.getXRot();
        this.viewRotY = 180 + LocalVehiclePlayer.instance().getPlayer().getYRot();
        ClientAssetsManager.INSTANCE.getVehicleDisplay(vehicle.getDisplayId()).ifPresent(display ->
                ClientAssetsManager.INSTANCE.getVariableDisplay(display.getModelPath()).stream()
                        .map(BaseDisplay::getDisplayId).distinct().sorted(Comparator.comparing(ResourceLocation::toString))
                        .forEach(id -> displays.add(new Display(id))));
        weaponSelections = vehicle.getPartUnits().stream().anyMatch(part -> part instanceof WeaponUnit unit
                && unit.isInteractive() && unit.weapons.size() > 1 && !unit.isHardpoint());
        loadoutShown = vehicle.loadoutIndex();
        showLoadout();
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
        SEARCH.set("");
        for (int i = 0; i < 3; i++) {
            SMOKE[i].set(Integer.toString(smoke(i).get()));
        }
        profile(null);
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

    private static ModConfigSpec.IntValue smoke(int channel) {
        return channel == 0 ? AircraftConfig.smokeR() : channel == 1 ? AircraftConfig.smokeG() : AircraftConfig.smokeB();
    }

    private void filter(String text) {
        String query = text.strip().toLowerCase(Locale.ROOT);
        visible.clear();
        for (Display display : displays) {
            if (query.isEmpty() || display.search.contains(query)) {
                visible.add(display);
            }
        }
        if (selected != null) {
            selected = null;
            profile(null);
        }
    }

    private void pick(Display display) {
        selected = display;
        PacketDistributor.sendToServer(new ClientVehicleChangeDisplay(vehicle.getId(), display.id));
        click();
        profile(display.id);
    }

    private void profile(ResourceLocation id) {
        if (id == null) {
            name = Component.translatable("screen.vehicle_modding_tool.select_display").getString();
            namespace = "--";
            description = describe(vehicle.getDisplayId());
        } else {
            name = id.getPath();
            namespace = id.getNamespace();
            description = describe(id);
        }
    }

    private static String describe(ResourceLocation id) {
        return Component.translatable(ClientAssetsManager.INSTANCE.getVehicleDisplay(id).map(BaseDisplay::getDescription)
                .filter(text -> text != null && !text.isBlank()).orElse("screen.no_description")).getString();
    }

    /** Free: hardware comes back to the player; refit at the probe node. */
    private void cycleLoadout() {
        loadoutShown = (Math.max(0, loadoutShown) + 1) % vehicle.loadouts().size();
        PacketDistributor.sendToServer(new ClientVehicleSelectLoadout(vehicle.getId(), loadoutShown));
        showLoadout();
    }

    private void showLoadout() {
        String id = loadoutShown < 0 || vehicle.loadouts().isEmpty() ? "--" : vehicle.loadouts().get(loadoutShown).id;
        loadoutText = Component.translatable("loadout.combinedassault.button",
                Component.translatable("loadout.combinedassault." + id)).getString();
    }

    private void slide(int channel, double fraction) {
        int value = (int) Math.round(fraction * 255);
        smoke(channel).set(value);
        SMOKE[channel].set(Integer.toString(value));
    }

    private int swatch() {
        return 0xFF000000 | smoke(0).get() << 16 | smoke(1).get() << 8 | smoke(2).get();
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
            GemRenderPreview.draw(vehicle, poseStack, 15728880);
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
        String leftW = pct(ws, 0.27f);
        String rightX = "(14 + " + leftW + ")";
        String rightW = "(" + ws + " - " + leftW + " - 7)";
        String previewH = "((" + bh + " - 7) * 0.6)";
        String profileH = "(" + bh + " - 7 - " + previewH + ")";
        float pad = 16;
        String listW = Panel.bodyW(leftW, pad);
        String profileW = Panel.bodyW(rightW, pad);
        String profileBodyH = Panel.bodyH(profileH, pad);
        String actionsW = "max(120, " + profileW + " * 0.33)";
        String copyW = "(" + profileW + " - " + actionsW + " - 8)";

        WidgetDef displayRow = new Tooltip(i -> List.of(Component.literal(((Display) i).full)),
                new Pick(i -> current.pick((Display) i), i -> i == current.selected, listW, "38", Pick.Look.ENTRY, 9,
                        10, Label.plain(i -> ((Display) i).path, Ore.WHITE, 19).clipped(listW + " - 18")));

        WidgetDef[] rows = new WidgetDef[3];
        String[] letters = {"R", "G", "B"};
        for (int i = 0; i < 3; i++) {
            int channel = i;
            rows[i] = new Sized(actionsW + " - 18", "34", Point.LEFT, false, row(6, Align.CENTER,
                    Label.plain(Label.of(letters[i]), Ore.INK, 17),
                    new Range(item -> smoke(channel).get() / 255f, (item, v) -> current.slide(channel, v), null,
                            actionsW + " - 104", 24),
                    new Field(SMOKE[i], "", "56", 28, 14, Ore.WHITE)));
        }
        WidgetDef smokeBox = new Box("#C2343537", "#FF1E1E1F", 2, 0, 9, 9, 0, 0, Point.TOP_LEFT, column(5,
                Label.plain(Ore.tr("ui.aerobatic_smoke"), Ore.GOLD, 14),
                rows[0], rows[1], rows[2],
                new Swatch()));

        layout = Ore.layout("vehicle_modding_tool", stack(
                new Navbar(Ore.tr("screen.vehicle_modding_tool.title"), 22, i -> current.onClose()),
                at("7", "68", new Panel(leftW, bh, Ore.GREEN, Ore.tr("screen.vehicle_modding_tool.catalog"), null, pad,
                        stack(new Field(SEARCH, "Search", listW, 42, 12, Ore.WHITE),
                                at("0", "48", stack(
                                        new ListView("ca_modding_tool.displays", 38, 7, listW,
                                                Panel.bodyH(bh, pad) + " - 48", "#FF8A8C90", displayRow),
                                        when("ca_modding_tool.none",
                                                Ore.note("screen.vehicle_modding_tool.no_results", listW))))))),
                at(rightX, "68", new Panel(rightW, previewH, Ore.PURPLE, Ore.tr("screen.vehicle_modding_tool.preview"),
                        new Badge(Label.of("3D"), Ore.PURPLE, 14), 0,
                        at("5", "5", new ModelPort(() -> current.preview, (g, p) -> current.paintPreview(g, p),
                                Panel.bodyW(rightW, 0) + " - 10", Panel.bodyH(previewH, 0) + " - 10", false)))),
                at(rightX, "(75 + " + previewH + ")", new Panel(rightW, profileH, Ore.GOLD,
                        Ore.tr("screen.vehicle_modding_tool.profile"), null, pad, stack(
                        Label.display(i -> current.name, Ore.WHITE, 22).clipped(copyW),
                        at("0", "30", new Badge(i -> current.namespace, Ore.PURPLE, 14)),
                        at("0", "62", new Paragraph(i -> current.description, copyW, profileBodyH + " - 62",
                                Ore.INK_MUTED, 17, 23, Ore.BOLD)),
                        at(copyW + " + 8", "0", column(6,
                                new OreButton(i -> Minecraft.getInstance().setScreen(
                                        new VehicleWeaponSelectScreen(current.vehicle, current)),
                                        i -> current.weaponSelections, Ore.Tone.PURPLE,
                                        Ore.tr("button.vehicle.weapon_selections"), actionsW, 38, 14),
                                when("ca_modding_tool.loadouts", new OreButton(i -> current.cycleLoadout(),
                                        i -> true, Ore.Tone.PURPLE, i -> current.loadoutText, actionsW, 38, 14)),
                                smokeBox)))))));
        return layout;
    }

    /** Smoke colour swatch, 42 px, 2 edge. */
    private record Swatch() implements WidgetDef {
        @Override
        public Widget create(Binder binder) {
            return new Widget() {
                @Override
                public void measure(UiEval ev) {
                    width = 42;
                    height = 42;
                }

                @Override
                public void draw(UiEval ev, float x, float y, float w, float h) {
                    ev.canvas.fill(x, y, x + 42, y + 42, current.swatch());
                    ev.canvas.frame(x, y, x + 42, y + 42, 2, 0xFF56585B);
                }
            };
        }
    }

    private static final class Display {
        final ResourceLocation id;
        final String path;
        final String full;
        final String search;

        Display(ResourceLocation id) {
            this.id = id;
            this.path = id.getPath();
            this.full = id.toString();
            this.search = full.toLowerCase(Locale.ROOT);
        }
    }
}
