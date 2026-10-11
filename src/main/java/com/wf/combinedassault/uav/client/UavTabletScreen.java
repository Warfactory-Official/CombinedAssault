package com.wf.combinedassault.uav.client;

import com.sighs.apricityui.init.Document;
import com.sighs.apricityui.init.Element;
import com.sighs.apricityui.render.Drawer;
import com.sighs.apricityui.screen.ApricityScreen;
import com.wf.combinedassault.ballistics.component.ComponentState;
import com.wf.combinedassault.ballistics.component.client.SystemsHud;
import com.wf.combinedassault.uav.net.UavTabletAction;
import com.wf.combinedassault.uav.net.UavTabletState;
import com.wf.wflib.client.cam.CameraFeedCache;
import com.wf.wflib.client.cam.CameraLink;
import com.wf.wflib.client.cam.FeedBlit;
import com.wf.wflib.drone.cam.CameraFeed;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Linked UAVs (left), selected one's live feed + systems, link, fuel, ammo, connect (right). */
public class UavTabletScreen extends ApricityScreen {

    private static final String TEMPLATE = "screens/uav_tablet.html";
    private static final String KEY = "tablet.combinedassault.";
    private static final int REFRESH_TICKS = 10;
    /** Gimbal degrees per GUI px dragged. */
    private static final double DRAG_GAIN = 0.3;

    private UavTabletState state;
    private int selected;
    private int ticks;
    private int openFeed;
    private boolean dragging;
    private String listShown = "";

    private Document document;
    private Element list;
    private Element empty;
    private Element count;
    private Element feed;
    private Element feedCard;
    private Element feedBadge;
    private Element name;
    private Element status;
    private Element systems;
    private Element linkBar;
    private Element linkValue;
    private Element fuelLabel;
    private Element fuelBar;
    private Element fuelValue;
    private Element ammo;
    private Element connect;
    private Element remove;

    private UavTabletScreen(UavTabletState state) {
        super(TEMPLATE);
        this.state = state;
        this.selected = state.selected();
        setPauseGame(false);
        setShowDefaultBackground(false);
    }

    public static void accept(UavTabletState state) {
        Minecraft mc = Minecraft.getInstance();
        if (state.open()) {
            mc.setScreen(new UavTabletScreen(state));
        } else if (mc.screen instanceof UavTabletScreen screen) {
            screen.state = state;
            screen.selected = Mth.clamp(screen.selected, 0, Math.max(0, state.entries().size() - 1));
            screen.refresh();
        }
    }

    @Override
    protected void init() {
        super.init();
        document = getLinkedDocument();
        if (document == null) {
            return;
        }
        list = element("vehicle-list");
        empty = element("vehicle-empty");
        count = element("vehicle-count");
        feed = element("feed");
        feedCard = element("feed-card");
        feedBadge = element("feed-badge");
        name = element("selected-name");
        status = element("selected-state");
        systems = element("systems");
        linkBar = element("link-bar");
        linkValue = element("link-value");
        fuelLabel = element("fuel-label");
        fuelBar = element("fuel-bar");
        fuelValue = element("fuel-value");
        ammo = element("ammo");
        connect = element("connect-button");
        remove = element("remove-button");
        listen("close-button", this::onClose);
        listen("connect-button", () -> act(UavTabletAction.Kind.CONNECT, true));
        listen("remove-button", () -> act(UavTabletAction.Kind.REMOVE, false));
        listShown = "";
        refresh();
    }

    private Element element(String id) {
        return document.getElementById(id);
    }

    private void listen(String id, Runnable action) {
        Element element = element(id);
        if (element != null) {
            element.addEventListener("click", event -> {
                if (!element.isDisabled()) {
                    click();
                    action.run();
                }
            });
        }
    }

    private void act(UavTabletAction.Kind kind, boolean close) {
        PacketDistributor.sendToServer(new UavTabletAction(kind, selected));
        if (close) {
            onClose();
        }
    }

    private UavTabletState.Entry current() {
        List<UavTabletState.Entry> entries = state.entries();
        return selected < entries.size() ? entries.get(selected) : null;
    }

    private static boolean live(UavTabletState.Entry entry) {
        return entry != null && (entry.status() == UavTabletState.Status.ONLINE
                || entry.status() == UavTabletState.Status.OCCUPIED);
    }

    private void refresh() {
        if (document == null) {
            return;
        }
        buildList();
        UavTabletState.Entry entry = current();
        int wanted = live(entry) ? entry.feedId() : 0;
        if (wanted != openFeed) {
            if (wanted == 0) {
                CameraLink.close();
            } else {
                CameraLink.open(wanted);
            }
            openFeed = wanted;
        }
        if (connect != null) {
            connect.setDisabled(entry == null || !(live(entry) || entry.status() == UavTabletState.Status.ASLEEP));
            remove.setDisabled(entry == null);
        }
        setText(name, entry == null ? "--" : entry.name().getString());
        setText(status, entry == null ? "--" : Component.translatable(entry.status().key()).getString());
        setText(feedCard, entry == null ? ""
                : entry.status() == UavTabletState.Status.ASLEEP ? Component.translatable(KEY + "waking").getString()
                : live(entry) ? "" : Component.translatable(entry.status().key()).getString());
        systems(entry);
        meter(linkBar, linkValue, entry == null ? 0 : entry.link());
        setText(fuelLabel, Component.translatable(KEY + (entry != null && entry.electric() ? "charge_label" : "fuel_label")).getString());
        meter(fuelBar, fuelValue, entry == null ? 0 : entry.fuel());
        List<String> rounds = new ArrayList<>();
        if (entry != null) {
            for (UavTabletState.Ammo a : entry.ammo()) {
                rounds.add(a.name().getString() + " " + a.loaded() + "/" + a.capacity() + (a.stored() > 0 ? " +" + a.stored() : ""));
            }
        }
        setText(ammo, String.join("   ", rounds));
    }

    /** Rebuilt only when what it shows changes: a rebuild drops hover. */
    private void buildList() {
        StringBuilder signature = new StringBuilder().append(selected).append('|');
        for (UavTabletState.Entry entry : state.entries()) {
            signature.append(entry.name().getString()).append(entry.status()).append(distance(entry.distance())).append('|');
        }
        if (signature.toString().equals(listShown) || list == null) {
            return;
        }
        listShown = signature.toString();
        list.setTextContent("");
        List<UavTabletState.Entry> entries = state.entries();
        for (int i = 0; i < entries.size(); i++) {
            UavTabletState.Entry entry = entries.get(i);
            Element row = document.createElement("div");
            row.setClassName(i == selected ? "vehicle-entry active" : "vehicle-entry");
            row.setAttribute("role", "button");
            Element title = document.createElement("span");
            title.setClassName("vehicle-entry-name");
            title.setTextContent(entry.name().getString());
            Element sub = document.createElement("span");
            sub.setClassName("vehicle-entry-state " + switch (entry.status()) {
                case ONLINE -> "online";
                case OCCUPIED -> "occupied";
                case DESTROYED, UNKNOWN -> "lost";
                case ASLEEP, OTHER_DIMENSION -> "";
            });
            sub.setTextContent(Component.translatable(entry.status().key()).getString()
                    + (entry.distance() >= 0 ? "  " + distance(entry.distance()) : ""));
            row.appendChild(title);
            row.appendChild(sub);
            int index = i;
            row.addEventListener("click", event -> select(index));
            list.appendChild(row);
        }
        if (empty != null) {
            empty.setClassName(entries.isEmpty() ? "empty-state" : "empty-state hidden");
        }
        setText(count, String.valueOf(entries.size()));
    }

    private void select(int index) {
        if (index == selected) {
            return;
        }
        click();
        selected = index;
        PacketDistributor.sendToServer(new UavTabletAction(UavTabletAction.Kind.SELECT, index));
        refresh();
    }

    private void systems(UavTabletState.Entry entry) {
        if (systems == null) {
            return;
        }
        systems.setTextContent("");
        if (entry == null) {
            return;
        }
        for (UavTabletState.Cell cell : entry.cells()) {
            ComponentState cellState = ComponentState.of(cell.ratio());
            Element box = document.createElement("span");
            box.setClassName(cellState == ComponentState.BROKEN ? "system-cell broken" : "system-cell");
            box.setInlineStyleProperty("background", String.format(Locale.ROOT, "#%06X", SystemsHud.colour(cellState) & 0xFFFFFF));
            box.setTextContent(cell.label());
            systems.appendChild(box);
        }
    }

    private void meter(Element bar, Element value, float ratio) {
        int percent = Math.round(Mth.clamp(ratio, 0, 1) * 100);
        if (bar != null) {
            bar.setInlineStyleProperty("width", percent + "%");
            document.markDirty(bar, Drawer.RELAYOUT | Drawer.REPAINT);
        }
        setText(value, percent + "%");
    }

    private void setText(Element element, String text) {
        if (element != null) {
            element.setTextContent(text);
        }
    }

    private static String distance(double metres) {
        if (metres < 0) {
            return "";
        }
        return metres >= 1000 ? String.format(Locale.ROOT, "%.1f km", metres / 1000)
                : String.format(Locale.ROOT, "%.0f m", metres);
    }

    @Override
    public void tick() {
        super.tick();
        if (++ticks % REFRESH_TICKS == 0) {
            PacketDistributor.sendToServer(new UavTabletAction(UavTabletAction.Kind.REFRESH, selected));
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        float[] box = feedBounds();
        if (box == null || openFeed == 0) {
            return;
        }
        CameraFeedCache.request(openFeed, Math.round((box[2] - box[0]) * (float) minecraft.getWindow().getGuiScale()), 0.0);
        ResourceLocation texture = CameraFeedCache.texture(openFeed);
        CameraFeed camera = CameraFeedCache.feed(openFeed);
        boolean drawn = texture != null && CameraFeedCache.drawable(openFeed);
        if (drawn) {
            // Feed is 16:9; fit inside the viewport, letterboxed.
            float w = box[2] - box[0], h = box[3] - box[1];
            float fw = Math.min(w, h * 16 / 9f), fh = fw * 9 / 16f;
            float x0 = box[0] + (w - fw) / 2, y0 = box[1] + (h - fh) / 2;
            graphics.flush();
            // Above the document's layers (weapon-select preview: same z).
            graphics.pose().pushPose();
            graphics.pose().translate(0, 0, 512);
            FeedBlit.draw(texture, graphics.pose().last().pose(), x0, y0, x0 + fw, y0 + fh);
            graphics.pose().popPose();
        }
        String badge = camera == null ? "--" : camera.modeValue().name();
        if (feedBadge != null && !badge.equals(feedBadge.getTextContent())) {
            feedBadge.setTextContent(badge);
        }
        String card = drawn ? "" : Component.translatable(KEY + "no_feed").getString();
        if (feedCard != null && !card.equals(feedCard.getTextContent())) {
            feedCard.setTextContent(card);
        }
    }

    /** #feed in GUI px: left, top, right, bottom. */
    private float[] feedBounds() {
        if (feed == null || document == null) {
            return null;
        }
        Element.DOMRect rect = feed.getBoundingClientRect();
        if (rect.width <= 0 || rect.height <= 0) {
            return null;
        }
        float scale = (float) document.getViewport().renderScale();
        return new float[]{(float) rect.x * scale, (float) rect.y * scale,
                (float) (rect.x + rect.width) * scale, (float) (rect.y + rect.height) * scale};
    }

    private boolean inFeed(double x, double y) {
        float[] box = feedBounds();
        return box != null && x >= box[0] && x <= box[2] && y >= box[1] && y <= box[3];
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && openFeed != 0 && inFeed(mouseX, mouseY)) {
            dragging = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging && button == 0) {
            double gain = DRAG_GAIN / Math.max(1.0, CameraLink.zoom());
            CameraLink.pan(dragX * gain, dragY * gain);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging && button == 0) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (openFeed != 0 && inFeed(mouseX, mouseY)) {
            CameraLink.zoomBy(scrollY);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void removed() {
        super.removed();
        CameraLink.close();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }
}
