package com.wf.combinedassault.uav.client;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.component.ComponentState;
import com.wf.combinedassault.ballistics.component.client.SystemsHud;
import com.wf.combinedassault.uav.UavTablet;
import com.wf.combinedassault.uav.net.UavTabletAction;
import com.wf.combinedassault.uav.net.UavTabletState;
import com.wf.gemrender.ui.Canvas;
import com.wf.wflib.client.cam.CameraFeedCache;
import com.wf.wflib.client.cam.CameraLink;
import com.wf.wflib.pda.Pda;
import com.wf.wflib.pda.client.PdaApp;
import com.wf.wflib.pda.client.PdaApps;
import com.wf.wflib.pda.client.PdaClient;
import com.wf.wflib.pda.client.PdaShell;
import com.wf.wflib.ui.Binder;
import com.wf.wflib.ui.Signals;
import com.wf.wflib.ui.Subject;
import com.wf.wflib.ui.UiEval;
import com.wf.wflib.ui.Widget;
import com.wf.wflib.ui.WidgetDef;
import com.wf.wflib.ui.input.UiDrag;
import com.wf.wflib.ui.widget.Button;
import com.wf.wflib.ui.widget.Feed;
import com.wf.wflib.ui.widget.Point;
import com.wf.wflib.ui.widget.Sized;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static com.wf.wflib.ui.Ui.at;
import static com.wf.wflib.ui.Ui.drag;
import static com.wf.wflib.ui.Ui.list;
import static com.wf.wflib.ui.Ui.place;
import static com.wf.wflib.ui.Ui.rect;
import static com.wf.wflib.ui.Ui.stack;
import static com.wf.wflib.ui.Ui.text;
import static com.wf.wflib.ui.Ui.when;

/**
 * PDA UAV app: linked UAVs (sneak + use the PDA on one) left; selected one's feed, systems, link, fuel, ammo,
 * CONNECT / REMOVE / FULL right. Server state = {@link UavTabletState}, polled while open.
 */
public final class UavPdaApp implements PdaApp {
    private static final ResourceLocation ID = CombinedAssault.modLocation("uav");
    private static final String KEY = "pda.combinedassault.uav.";
    private static final int REFRESH_TICKS = 10;
    /** Drag units -> degrees at zoom 1. */
    private static final double DRAG_GAIN = 0.35;

    private static final UavPdaApp INSTANCE = new UavPdaApp();
    static final Subject<UavPdaApp> APP = Subject.register("pda_uavs", UavPdaApp.class, () -> INSTANCE);
    static final Subject<Row> ROW = Subject.register("pda_uav", Row.class);
    static final Subject<Row> SELECTED = Subject.register("pda_uavsel", Row.class, UavPdaApp::current);

    private static final List<Row> ROWS = new ArrayList<>();
    private static int selected;
    private static boolean open;
    private static int ticks;

    /** One entry with its strings resolved once per server update. */
    record Row(UavTabletState.Entry entry, String name, String status, String detail, String fuel, String ammo,
               String card) {
        static Row of(UavTabletState.Entry e) {
            String status = Component.translatable(e.status().key()).getString();
            List<String> rounds = new ArrayList<>();
            for (UavTabletState.Ammo a : e.ammo()) {
                rounds.add(a.name().getString() + " " + a.loaded() + "/" + a.capacity()
                        + (a.stored() > 0 ? " +" + a.stored() : ""));
            }
            String card = e.status() == UavTabletState.Status.ASLEEP ? Component.translatable(KEY + "waking").getString()
                    : live(e) ? Component.translatable(KEY + "no_feed").getString() : status;
            return new Row(e, e.name().getString(), status,
                    e.distance() >= 0 ? status + "  " + distance(e.distance()) : status,
                    Component.translatable(KEY + (e.electric() ? "charge_label" : "fuel_label")).getString(),
                    String.join("   ", rounds), card);
        }
    }

    private UavPdaApp() {
    }

    public static void register() {
        Signals.num(APP, "count", a -> ROWS.size());
        Signals.num(APP, "cap", a -> UavTablet.CAP);
        Signals.items(APP, "rows", a -> ROWS, ROW);
        Signals.num(APP, "feed", a -> feed());
        Signals.bool(APP, "picture", a -> feed() != 0 && CameraFeedCache.drawable(feed()));
        Signals.text(ROW, "name", 32, Row::name);
        Signals.text(ROW, "detail", 32, Row::detail);
        Signals.bool(ROW, "selected", r -> r == current());
        Signals.bool(ROW, "live", r -> live(r.entry()));
        Signals.text(SELECTED, "name", 32, Row::name);
        Signals.text(SELECTED, "status", 24, Row::status);
        Signals.text(SELECTED, "fuel_label", 12, Row::fuel);
        Signals.text(SELECTED, "ammo", 96, Row::ammo);
        Signals.text(SELECTED, "card", 24, Row::card);
        Signals.num(SELECTED, "link", r -> Math.round(r.entry().link() * 100));
        Signals.num(SELECTED, "fuel", r -> Math.round(r.entry().fuel() * 100));
        Signals.bool(SELECTED, "live", r -> live(r.entry()));
        Signals.bool(SELECTED, "connectable", r -> live(r.entry())
                || r.entry().status() == UavTabletState.Status.ASLEEP);
        NeoForge.EVENT_BUS.addListener(UavPdaApp::onTick);
        NeoForge.EVENT_BUS.addListener(UavPdaApp::onTooltip);
        PdaApps.register(INSTANCE);
    }

    public static void accept(UavTabletState state) {
        ROWS.clear();
        for (UavTabletState.Entry e : state.entries()) {
            ROWS.add(Row.of(e));
        }
        selected = ROWS.isEmpty() ? 0 : Math.clamp(selected, 0, ROWS.size() - 1);
        link();
    }

    private static Row current() {
        return selected < ROWS.size() ? ROWS.get(selected) : null;
    }

    private static boolean live(UavTabletState.Entry e) {
        return e.status() == UavTabletState.Status.ONLINE || e.status() == UavTabletState.Status.OCCUPIED;
    }

    /** Selected live UAV's feed while it holds {@link CameraLink}; else 0. */
    private static int feed() {
        Row row = current();
        return row != null && CameraLink.active() && CameraLink.feedId() == row.entry().feedId()
                ? row.entry().feedId() : 0;
    }

    /** Open app => {@link CameraLink} follows the selected live UAV. */
    private static void link() {
        if (!open) {
            return;
        }
        Row row = current();
        int wanted = row != null && live(row.entry()) ? row.entry().feedId() : 0;
        if (wanted != 0 && !(CameraLink.active() && CameraLink.feedId() == wanted)) {
            CameraLink.open(wanted);
        } else if (wanted == 0 && feed() == 0 && CameraLink.active() && owned()) {
            CameraLink.close();
        }
    }

    /** {@link CameraLink} on one of this app's UAVs (not a TV round's). */
    private static boolean owned() {
        for (Row row : ROWS) {
            if (row.entry().feedId() == CameraLink.feedId()) {
                return true;
            }
        }
        return false;
    }

    private static void select(Object item) {
        int index = ROWS.indexOf(item);
        if (index < 0 || index == selected) {
            return;
        }
        selected = index;
        PacketDistributor.sendToServer(new UavTabletAction(UavTabletAction.Kind.SELECT, index));
        link();
    }

    private static void act(UavTabletAction.Kind kind) {
        if (current() != null) {
            PacketDistributor.sendToServer(new UavTabletAction(kind, selected));
        }
    }

    private static String distance(double metres) {
        return metres >= 1000 ? String.format(Locale.ROOT, "%.1f km", metres / 1000)
                : String.format(Locale.ROOT, "%.0f m", metres);
    }

    private static void onTick(ClientTickEvent.Post event) {
        if (open && ++ticks % REFRESH_TICKS == 0) {
            PacketDistributor.sendToServer(new UavTabletAction(UavTabletAction.Kind.REFRESH, selected));
        }
    }

    private static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        int linked = Pda.is(stack) ? UavTablet.channels(stack).size() : 0;
        if (linked > 0) {
            event.getToolTip().add(Component.translatable("tips.uav_link.linked", linked, UavTablet.CAP)
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public Component title() {
        return Component.translatable(KEY + "title");
    }

    @Override
    public boolean visible(LocalPlayer player) {
        return PdaClient.held();
    }

    @Override
    public void opened() {
        open = true;
        ticks = 0;
        PacketDistributor.sendToServer(new UavTabletAction(UavTabletAction.Kind.REFRESH, selected));
        link();
    }

    @Override
    public void closed() {
        open = false;
        if (feed() != 0) {
            CameraLink.close();
        }
    }

    @Override
    public WidgetDef build() {
        float listW = 128;
        float h = PdaShell.APP_HEIGHT - 16;
        float rowW = listW - 8;
        WidgetDef row = new Button(UavPdaApp::select, "true", "pda_uav.selected ? " + PdaShell.PRESS + " : 0",
                PdaShell.HOVER, PdaShell.PRESS, "0", 0, 0, 0, 0, rowW, 24, Point.TOP_LEFT,
                new Sized(Float.toString(rowW), "24", Point.TOP_LEFT, false, stack(
                        at("5", "3", text("{pda_uav.name}", "pda_uav.live ? " + PdaShell.ACCENT + " : " + PdaShell.TEXT)),
                        at("5", "13", text("{pda_uav.detail}", PdaShell.DIM)))));
        float viewX = listW + 16;
        float viewW = PdaShell.APP_WIDTH - viewX - 8;
        float feedW = viewW - 8;
        float feedH = (float) Math.floor(feedW * 9 / 16);
        String fw = Float.toString(feedW);
        String fh = Float.toString(feedH);
        UiDrag pan = new UiDrag() {
            @Override
            public void drag(Object item, float dx, float dy) {
                if (feed() != 0) {
                    double gain = DRAG_GAIN / Math.max(1.0, CameraLink.zoom());
                    CameraLink.pan(dx * gain, dy * gain);
                }
            }

            @Override
            public boolean wheel(Object item, float amount) {
                if (feed() == 0) {
                    return false;
                }
                CameraLink.zoomBy(amount);
                return true;
            }
        };
        float infoY = 24 + feedH + 4;
        float buttonsY = h - 14;
        String vx = Float.toString(viewX + 4);
        return stack(
                at("8", "8", PdaShell.panel(listW, h)),
                at("14", "13", text("UAVS  {pda_uavs.count}/{pda_uavs.cap}", PdaShell.DIM)),
                at("12", "26", list("pda_uavs.rows", 24, 1, Float.toString(rowW), Float.toString(h - 22),
                        PdaShell.EDGE, row)),
                when("pda_uavs.count == 0", stack(at("14", "34", text("NO UAVS LINKED", PdaShell.DIM)),
                        at("14", "46", text("SNEAK+USE PDA ON A UAV", PdaShell.DIM)))),
                at(Float.toString(viewX), "8", PdaShell.panel(viewW, h)),
                when("pda_uavsel.present", stack(
                        at(Float.toString(viewX + 6), "13", text("{pda_uavsel.name}", PdaShell.TEXT)),
                        place(Float.toString(viewX + viewW - 6), "13", Point.TOP_RIGHT,
                                text("{pda_uavsel.status}", "pda_uavsel.live ? " + PdaShell.ACCENT + " : "
                                        + PdaShell.DIM)))),
                at(vx, "24", drag(fw, fh, pan, stack(
                        rect(fw, fh, "#ff101210"),
                        new Feed("pda_uavs.feed", fw, fh),
                        when("!pda_uavs.picture && pda_uavsel.present", place(Float.toString(feedW / 2),
                                Float.toString(feedH / 2 - 4), Point.TOP, text("{pda_uavsel.card}", PdaShell.DIM)))))),
                when("pda_uavsel.present", stack(
                        at(vx, Float.toString(infoY), text("LINK {pda_uavsel.link}%   {pda_uavsel.fuel_label} "
                                + "{pda_uavsel.fuel}%", PdaShell.TEXT)),
                        at(vx, Float.toString(infoY + 11), new Cells(feedW)),
                        at(vx, Float.toString(infoY + 24), text("{pda_uavsel.ammo}", PdaShell.DIM)))),
                at(vx, Float.toString(buttonsY), button("CONNECT", "pda_uavsel.present && pda_uavsel.connectable",
                        () -> {
                            act(UavTabletAction.Kind.CONNECT);
                            PdaClient.lower();
                        })),
                at(Float.toString(viewX + 66), Float.toString(buttonsY), button("REMOVE", "pda_uavsel.present",
                        () -> act(UavTabletAction.Kind.REMOVE))),
                at(Float.toString(viewX + viewW - 40), Float.toString(buttonsY), button("FULL",
                        "pda_uavs.feed != 0", () -> PdaClient.fullscreen(feed()))));
    }

    private static WidgetDef button(String label, String enabled, Runnable action) {
        return new Button(item -> action.run(), enabled, PdaShell.PANEL, PdaShell.HOVER, PdaShell.PRESS, PdaShell.EDGE,
                1, 0, 8, 2, 0, 0, Point.CENTER, text(label, PdaShell.TEXT));
    }

    /** Selected UAV's system cells, wrapped to {@code w}; colours as the vehicle HUD. */
    private record Cells(float w) implements WidgetDef {
        private static final float CELL_H = 10;
        private static final float GAP = 2;

        @Override
        public Widget create(Binder binder) {
            return new Widget() {
                @Override
                public void measure(UiEval ev) {
                    width = w;
                    height = CELL_H;
                }

                @Override
                public void draw(UiEval ev, float x, float y, float bw, float bh) {
                    Row row = current();
                    if (row == null) {
                        return;
                    }
                    Canvas canvas = ev.canvas;
                    List<UavTabletState.Cell> cells = row.entry().cells();
                    float cx = x;
                    for (int i = 0; i < cells.size(); i++) {
                        UavTabletState.Cell cell = cells.get(i);
                        float cw = canvas.width(cell.label()) + 6;
                        if (cx + cw > x + w) {
                            break;
                        }
                        ComponentState state = ComponentState.of(cell.ratio());
                        canvas.fill(cx, y, cx + cw, y + CELL_H, SystemsHud.colour(state));
                        canvas.text(cell.label(), cx + 3, y + 1, state == ComponentState.BROKEN ? 0xFFFFFFFF
                                : 0xFF202020, false);
                        cx += cw + GAP;
                    }
                }
            };
        }
    }
}
