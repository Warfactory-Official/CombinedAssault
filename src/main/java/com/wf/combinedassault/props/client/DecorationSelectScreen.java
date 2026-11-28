package com.wf.combinedassault.props.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import com.wf.combinedassault.pack.client.ClientAssetsManager;
import com.wf.combinedassault.pack.client.vehicle.BaseDisplay;
import com.wf.combinedassault.props.message.ClientDecorationAction;
import com.wf.combinedassault.render.gem.GemModels;
import com.wf.combinedassault.screen.Badge;
import com.wf.combinedassault.screen.Field;
import com.wf.combinedassault.screen.Label;
import com.wf.combinedassault.screen.ModelPort;
import com.wf.combinedassault.screen.Navbar;
import com.wf.combinedassault.screen.Ore;
import com.wf.combinedassault.screen.OreScreen;
import com.wf.combinedassault.screen.Panel;
import com.wf.combinedassault.screen.Paragraph;
import com.wf.combinedassault.screen.Pick;
import com.wf.combinedassault.screen.Port;
import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.ui.api.Binder;
import com.wf.wflib.ui.api.NumProp;
import com.wf.wflib.ui.api.Signals;
import com.wf.wflib.ui.api.Subject;
import com.wf.wflib.ui.api.UiEval;
import com.wf.wflib.ui.api.UiLayout;
import com.wf.wflib.ui.api.Widget;
import com.wf.wflib.ui.api.WidgetDef;
import com.wf.wflib.ui.api.input.Interactive;
import com.wf.wflib.ui.api.input.TextBox;
import com.wf.wflib.ui.api.input.UiInput;
import com.wf.wflib.ui.api.widget.Place;
import com.wf.wflib.ui.api.widget.Point;
import com.wf.wflib.ui.api.widget.Rect;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static com.wf.combinedassault.screen.Ore.pct;
import static com.wf.wflib.ui.api.Ui.at;
import static com.wf.wflib.ui.api.Ui.stack;
import static com.wf.wflib.ui.api.Ui.when;

public class DecorationSelectScreen extends OreScreen {
    private static DecorationSelectScreen current;
    private static final Subject<DecorationSelectScreen> SCREEN =
            Subject.register("ca_decoration_select", DecorationSelectScreen.class, () -> current);
    private static final TextBox SEARCH = TextBox.text(48);
    private static UiLayout layout;

    static {
        Signals.bool(SCREEN, "none", s -> s.visible.isEmpty());
        SEARCH.onChange(text -> {
            if (current != null) {
                current.filter(text);
            }
        });
    }

    private final List<Decor> all = new ArrayList<>();
    private final List<Decor> visible = new ArrayList<>();
    private final Port preview;
    private Decor selected;
    private String count;
    private String name;
    private String namespace;
    private String description;
    private double zoom = 1.0;
    private double panX;
    private double panY;

    public DecorationSelectScreen() {
        super(build(), Component.translatable("screen.decoration_select.title"));
        ClientAssetsManager.INSTANCE.getDecorationDisplays().stream().map(BaseDisplay::getDisplayId).distinct()
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .forEach(id -> all.add(new Decor(id, ClientAssetsManager.INSTANCE.getDecorationDisplay(id).orElse(null))));
        preview = port(new Port.Handler() {
            @Override
            public boolean accepts(int button) {
                return button == 0 && hasModel();
            }

            @Override
            public void drag(int button, double dx, double dy) {
                panX += dx;
                panY += dy;
                clampPan();
            }

            @Override
            public void scroll(double mouseX, double mouseY, double amount) {
                if (!hasModel()) {
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

    private boolean hasModel() {
        return selected != null && selected.display != null && selected.display.getModelPath() != null;
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
        for (Decor decor : all) {
            if (query.isEmpty() || decor.search.contains(query)) {
                visible.add(decor);
            }
        }
        count = visible.size() + "/" + all.size();
        select(null);
    }

    private void select(Decor decor) {
        selected = decor == null || decor.display == null ? null : decor;
        zoom = 1.0;
        panX = 0;
        panY = 0;
        if (selected == null) {
            name = Component.translatable("screen.decoration_select.preview").getString();
            namespace = "--";
            description = Component.translatable("screen.decoration_select.select_decoration").getString();
            return;
        }
        name = selected.id.getPath();
        namespace = selected.id.getNamespace();
        String key = selected.display.getDescription();
        description = Component.translatable(key == null || key.isBlank() ? "screen.no_description" : key).getString();
        ClientDecorationAction action = new ClientDecorationAction();
        action.action = ClientDecorationAction.Action.UPDATE_ITEM;
        action.displayId = selected.id.toString();
        PacketDistributor.sendToServer(action);
        click();
    }

    private void paintPreview(GuiGraphics graphics, Port port) {
        if (!hasModel()) {
            return;
        }
        double scale = Math.min(port.width(), port.height()) * 0.38 * zoom;
        float rotation = (System.currentTimeMillis() % 10000L) / 10000.0F * 360.0F;
        Lighting.setupForEntityInInventory();
        try {
            drawModel(graphics.pose(), selected.display, port.centerX() + panX, modelCenterY() + panY, scale, 165,
                    rotation);
        } finally {
            Lighting.setupFor3DItems();
        }
    }

    /** {@code xRotation} 0 => upright thumbnail (Z flip). */
    private static void drawModel(PoseStack poseStack, BaseDisplay display, double centerX, double centerY,
                                  double scale, float xRotation, float yRotation) {
        poseStack.pushPose();
        try {
            poseStack.translate((float) centerX, (float) centerY, 512);
            if (xRotation != 0) {
                poseStack.mulPose(Axis.XP.rotationDegrees(xRotation));
            }
            poseStack.mulPose(Axis.YP.rotationDegrees(yRotation));
            if (xRotation == 0) {
                poseStack.mulPose(Axis.ZP.rotationDegrees(180));
            }
            poseStack.scale((float) scale, (float) scale, (float) -scale);
            GemModels.drawGui(display.getModelPath(), poseStack, 15728880);
        } finally {
            poseStack.popPose();
        }
    }

    private static UiLayout build() {
        if (layout != null) {
            return layout;
        }
        String ws = "(" + Ore.W + " - 14)";
        String bh = "1005";
        String leftW = pct(ws, 0.43f);
        String rightX = "(14 + " + leftW + ")";
        String rightW = "(" + ws + " - " + leftW + " - 7)";
        String previewH = "((" + bh + " - 7) * 0.6)";
        String profileH = "(" + bh + " - 7 - " + previewH + ")";
        float pad = 16;
        String gridW = Panel.bodyW(leftW, pad);
        String profW = Panel.bodyW(rightW, pad);
        String descH = Panel.bodyH(profileH, pad) + " - 40";

        layout = Ore.layout("decoration_select", stack(
                new Navbar(Ore.tr("screen.decoration_select.title"), 22, i -> current.onClose()),
                at("7", "68", new Panel(leftW, bh, Ore.GREEN, Ore.tr("screen.decoration_select.catalog"),
                        new Badge(i -> current.count, Ore.BLUE, 14), pad, stack(
                        new Field(SEARCH, "Search", gridW, 42, 12, Ore.WHITE),
                        at("0", "48", new Grid(gridW, Panel.bodyH(bh, pad) + " - 48")),
                        when("ca_decoration_select.none", at("0", "48",
                                Ore.note("screen.decoration_select.no_results", gridW)))))),
                at(rightX, "68", new Panel(rightW, previewH, Ore.PURPLE, Ore.tr("screen.decoration_select.preview"),
                        new Badge(Label.of("3D"), Ore.PURPLE, 14), 0,
                        at("5", "5", new ModelPort(() -> current.preview, (g, p) -> current.paintPreview(g, p),
                                Panel.bodyW(rightW, 0) + " - 10", Panel.bodyH(previewH, 0) + " - 10", true)))),
                at(rightX, "(75 + " + previewH + ")", new Panel(rightW, profileH, Ore.PURPLE,
                        Ore.tr("screen.decoration_select.profile"), null, pad, stack(
                        Label.display(i -> current.name, Ore.WHITE, 22).clipped(profW + " * 0.55"),
                        new Place(profW, "-2", Point.TOP_RIGHT,
                                new Badge(i -> current.namespace, Ore.PURPLE, 14, profW + " * 0.42 - 20")),
                        at("0", "40", stack(
                                new Rect(profW, descH, "#C2343537", 0, 0),
                                new Rect(profW, descH, "#FF1E1E1F", 0, 2),
                                at("9", "9", new Paragraph(i -> current.description, profW + " - 18",
                                        descH + " - 18", Ore.INK_MUTED, 17, 23, Ore.BOLD)))))))));
        return layout;
    }

    /**
     * Catalog grid: 2 edge, 4 padding, 6 columns of square tiles (min 28), wheel-scrolled; texture or model thumbnail
     * (models drawn after all chrome, one flush).
     */
    private record Grid(String w, String h) implements WidgetDef {
        private static final int COLUMNS = 6;
        private static final float GAP = 4;

        @Override
        public Widget create(Binder binder) {
            return new Node(binder.num(w), binder.num(h));
        }

        private static final class Node extends Widget implements Interactive {
            private final NumProp pw;
            private final NumProp ph;
            private final PoseStack scratch = new PoseStack();
            private final Vector4f corner = new Vector4f();
            private float scroll;
            private float content;

            Node(NumProp pw, NumProp ph) {
                this.pw = pw;
                this.ph = ph;
            }

            @Override
            public void measure(UiEval ev) {
                width = Math.max(0, pw.getFloat(ev));
                height = Math.max(0, ph.getFloat(ev));
            }

            @Override
            public void draw(UiEval ev, float x, float y, float bw, float bh) {
                Canvas c = ev.canvas;
                List<Decor> list = current.visible;
                float tile = Math.max(28, (float) Math.floor((width - 12 - GAP * (COLUMNS - 1)) / COLUMNS));
                float left = x + (width - tile * COLUMNS - GAP * (COLUMNS - 1)) / 2;
                int rows = (list.size() + COLUMNS - 1) / COLUMNS;
                content = rows == 0 ? 0 : rows * (tile + GAP) - GAP + 12;
                scroll = Math.max(0, Math.min(scroll, content - height));
                c.fill(x, y, x + width, y + height, 0xAD2D2E30);
                c.frame(x, y, x + width, y + height, 2, Ore.EDGE);
                UiInput input = ev.input;
                if (input != null) {
                    input.hit(x, y, x + width, y + height, this, null);
                    input.pushClip(x + 2, y + 2, x + width - 2, y + height - 2);
                }
                c.pushClip(x + 2, y + 2, x + width - 2, y + height - 2);
                int first = Math.max(0, (int) ((scroll - 6) / (tile + GAP))) * COLUMNS;
                int last = first;
                for (int i = first; i < list.size(); i++) {
                    float tx = left + (i % COLUMNS) * (tile + GAP);
                    float ty = y + 6 + (i / COLUMNS) * (tile + GAP) - scroll;
                    if (ty >= y + height) {
                        break;
                    }
                    last = i + 1;
                    Decor decor = list.get(i);
                    boolean hover = false;
                    if (input != null) {
                        input.hit(tx, ty, tx + tile, ty + tile, decor, decor);
                        hover = input.hovered(decor, decor);
                    }
                    Pick.Look look = Pick.Look.TILE;
                    boolean on = decor == current.selected;
                    c.fill(tx, ty, tx + tile, ty + tile, on ? look.on() : hover ? look.hover() : look.back());
                    c.fill(tx + 2, ty + 2, tx + tile - 2, ty + 4, on ? 0xFF302C20 : 0xFF292A2C);
                    c.fill(tx + 2, ty + 4, tx + 4, ty + tile - 2, on ? 0xFF302C20 : 0xFF292A2C);
                    c.fill(tx + 4, ty + tile - 4, tx + tile - 2, ty + tile - 2, on ? 0xFF6A6044 : 0xFF47494C);
                    c.fill(tx + tile - 4, ty + 4, tx + tile - 2, ty + tile - 4, on ? 0xFF6A6044 : 0xFF47494C);
                    c.frame(tx, ty, tx + tile, ty + tile, 2, on ? look.edgeOn() : hover ? look.edgeHover()
                            : look.edgeBack());
                    if (decor.display != null && decor.display.getSlotTexture() != null) {
                        c.sprite(decor.display.getSlotTexture(), tx + 4, ty + 4, tx + tile - 4, ty + tile - 4,
                                0xFFFFFFFF);
                    }
                }
                if (content > height) {
                    float size = Math.max(24, height * height / content);
                    float at = y + (height - size) * scroll / (content - height);
                    c.fill(x + width - 6, at, x + width - 2, at + size, 0xFF8A8C90);
                }
                GuiGraphics graphics = ev.graphics;
                if (graphics != null) {
                    models(c, graphics, list, first, last, left, y, tile);
                }
                c.popClip();
                if (input != null) {
                    input.popClip();
                }
            }

            private void models(Canvas c, GuiGraphics graphics, List<Decor> list, int first, int last, float left,
                                float y, float tile) {
                boolean any = false;
                for (int i = first; i < last && !any; i++) {
                    any = list.get(i).thumbModel;
                }
                if (!any) {
                    return;
                }
                c.suspend();
                PoseStack pose = graphics.pose();
                pose.pushPose();
                c.applyTo(pose);
                scratch.setIdentity();
                c.applyTo(scratch);
                corner.set(left - 6, y + 2, 0, 1).mul(scratch.last().pose());
                int sx0 = (int) Math.floor(corner.x);
                int sy0 = (int) Math.floor(corner.y);
                corner.set(left + COLUMNS * (tile + GAP) + 6, y + height - 2, 0, 1).mul(scratch.last().pose());
                graphics.enableScissor(sx0, sy0, (int) Math.ceil(corner.x), (int) Math.ceil(corner.y));
                Lighting.setupForEntityInInventory();
                try {
                    for (int i = first; i < last; i++) {
                        Decor decor = list.get(i);
                        if (!decor.thumbModel) {
                            continue;
                        }
                        float tx = left + (i % COLUMNS) * (tile + GAP);
                        float ty = y + 6 + (i / COLUMNS) * (tile + GAP) - scroll;
                        float inner = tile * 0.75f;
                        drawModel(pose, decor.display, tx + tile / 2, ty + tile / 2, inner, 0, 180);
                    }
                    graphics.flush();
                } finally {
                    Lighting.setupFor3DItems();
                    graphics.disableScissor();
                    pose.popPose();
                    c.resume();
                }
            }

            @Override
            public boolean feedback() {
                return false;
            }

            @Override
            public boolean wheel(Object item, float amount) {
                float before = scroll;
                scroll = Math.max(0, Math.min(scroll - amount * 40, content - height));
                return scroll != before;
            }
        }
    }

    /** Hit owner per tile: release => select. */
    private static final class Decor implements Interactive {
        final ResourceLocation id;
        final BaseDisplay display;
        final String search;
        final boolean thumbModel;

        Decor(ResourceLocation id, BaseDisplay display) {
            this.id = id;
            this.display = display;
            this.search = id.toString().toLowerCase(Locale.ROOT);
            this.thumbModel = display != null && display.getSlotTexture() == null && display.getModelPath() != null;
        }

        @Override
        public void release(Object item, float x, float y, boolean inside) {
            if (inside && current != null) {
                current.select(this);
            }
        }
    }
}
