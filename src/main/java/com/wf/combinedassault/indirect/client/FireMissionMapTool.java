package com.wf.combinedassault.indirect.client;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.indirect.FireMission;
import com.wf.combinedassault.indirect.IndirectFireSpec;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.wflib.client.map.MapButton;
import com.wf.wflib.client.map.MapDraw;
import com.wf.wflib.client.map.MapMenu;
import com.wf.wflib.client.map.MapPointer;
import com.wf.wflib.client.map.MapTool;
import com.wf.wflib.client.map.MapView;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import com.wf.gemrender.ui.Canvas;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * Strikes ordered from the fullscreen map by the operator of an {@code indirect_fire.map} station: click
 * designates, right-click menu orders rounds per loaded kind. Missions execute through {@link FireMissions}.
 */
public final class FireMissionMapTool implements MapTool {

    private static final ResourceLocation ICON = CombinedAssault.modLocation("textures/map/fire_mission.png");
    private static final int COLOUR_TARGET = 0xFFFF4A3D;
    private static final int COLOUR_LINE = 0xAAFFFFFF;
    private static final int COLOUR_LAUNCHER = 0xFF4FC3F7;
    private static final int COLOUR_TEXT = 0xFFFFE08A;
    /** Clear of a host's toolbar column on the right. */
    private static final int RIGHT_MARGIN = 40;
    private static final int TOP_MARGIN = 44;
    private static final long SAY_MILLIS = 5000L;
    private static final int[] SALVOS = {1, 2, 4};

    private boolean armed;
    @Nullable
    private Vec3 designated;
    @Nullable
    private Component said;
    private long saidAt;

    @Nullable
    private static WeaponUnit station() {
        LocalVehiclePlayer local = LocalVehiclePlayer.instance;
        if (local == null || !local.onVehicle()) {
            return null;
        }
        WeaponUnit unit = local.getWeaponUnit();
        IndirectFireSpec spec = unit == null ? null : unit.getIndirectFireSpec();
        return spec != null && spec.map() ? unit : null;
    }

    @Override
    public MapButton button() {
        return new MapButton("Fire mission on", "Fire mission off", ICON, List.of("Fire mission",
                "Left click to designate the target.", "Right click to order rounds on it."));
    }

    @Override
    public boolean available() {
        return station() != null;
    }

    @Override
    public boolean armed() {
        return armed;
    }

    @Override
    public void setArmed(boolean armed) {
        this.armed = armed;
    }

    @Override
    public boolean click(MapPointer pointer) {
        if (!armed || pointer.button() != 0) {
            return false;
        }
        designated = target(pointer.blockX(), pointer.surfaceY(), pointer.blockZ());
        return true;
    }

    @Override
    public void menu(MapMenu menu, MapView view) {
        WeaponUnit unit = station();
        if (unit == null) {
            return;
        }
        menu.item("Fire mission: designate here", pos -> designated = target(pos.getX() + 0.5, pos.getY(),
                pos.getZ() + 0.5));
        for (Item round : FireMissions.orderable(unit)) {
            int loaded = FireMissions.loaded(unit, round);
            String name = round == Items.AIR ? "loaded round" : round.getDescription().getString();
            MapMenu sub = menu.sub(String.format(Locale.ROOT, "Fire mission: %s (%d)", name, loaded));
            for (int salvo : SALVOS) {
                if (salvo <= loaded) {
                    sub.item(salvo == 1 ? "1 round" : salvo + " rounds", pos -> order(unit, pos, round, salvo));
                }
            }
            if (loaded > 1 && java.util.Arrays.stream(SALVOS).noneMatch(salvo -> salvo == loaded)) {
                sub.item("all " + loaded, pos -> order(unit, pos, round, loaded));
            }
        }
        if (unit.fireMission() != null) {
            menu.item("Fire mission: cancel", pos -> {
                FireMissions.cancel(unit);
                say(Component.translatable("tips.fire_mission.cancelled"));
            });
        }
    }

    /** Ordered on the designated point when there is one, else where the menu opened. */
    private void order(WeaponUnit unit, BlockPos pos, Item round, int rounds) {
        Vec3 target = designated != null ? designated : target(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        designated = target;
        String refused = FireMissions.order(unit, target, round, rounds);
        say(refused != null ? Component.translatable(refused) : Component.translatable("tips.fire_mission.ordered",
                rounds, Math.round(distance(unit, target))));
    }

    private void say(Component message) {
        said = message;
        saidAt = Util.getMillis();
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(message, true);
        }
    }

    /** Readout height only: rack rounds re-take the surface server-side ({@code MissileEntity.setGroundTarget}). */
    private static Vec3 target(double x, int surfaceY, double z) {
        WeaponUnit unit = station();
        double y = surfaceY != MapPointer.UNKNOWN_Y ? surfaceY + 0.5 : unit == null ? 64 : unit.getVehicle().getY();
        return new Vec3(x, y, z);
    }

    private static double distance(WeaponUnit unit, Vec3 target) {
        Vec3 from = unit.getVehicle().position();
        return Math.hypot(target.x - from.x, target.z - from.z);
    }

    @Override
    public void render(Canvas graphics, MapView view) {
        WeaponUnit unit = station();
        if (unit == null || !view.dimension().equals(unit.getVehicle().level().dimension())) {
            return;
        }
        Vec3 from = unit.getVehicle().position();
        double fx = view.toScreenX(from.x);
        double fy = view.toScreenY(from.z);
        MapDraw.handle(graphics, fx, fy, 3, COLOUR_LAUNCHER, 0xFF000000);
        FireMission mission = unit.fireMission();
        Vec3 target = mission != null ? mission.target() : designated;
        if (target != null) {
            double tx = view.toScreenX(target.x);
            double ty = view.toScreenY(target.z);
            MapDraw.dashed(graphics, fx, fy, tx, ty, 1.0f, COLOUR_LINE, 4.0);
            MapDraw.line(graphics, tx - 6, ty, tx + 6, ty, 1.5f, COLOUR_TARGET);
            MapDraw.line(graphics, tx, ty - 6, tx, ty + 6, 1.5f, COLOUR_TARGET);
        }
        var font = Minecraft.getInstance().font;
        int right = (int) graphics.width() - RIGHT_MARGIN;
        int y = TOP_MARGIN;
        if (target != null) {
            String line = String.format(Locale.ROOT, "TGT %d %d %d  RNG %dm", (int) Math.floor(target.x),
                    (int) Math.floor(target.y), (int) Math.floor(target.z), Math.round(distance(unit, target)));
            graphics.text(line, right - font.width(line), y, COLOUR_TEXT, true);
            y += 10;
        }
        if (mission != null) {
            String line = String.format(Locale.ROOT, "FIRE MISSION  %s x%d", mission.round() == Items.AIR
                    ? "" : mission.round().getDescription().getString(), mission.remaining());
            graphics.text(line, right - font.width(line), y, COLOUR_TEXT, true);
            y += 10;
        }
        if (said != null && Util.getMillis() - saidAt < SAY_MILLIS) {
            graphics.text(said.getVisualOrderText(), right - font.width(said), y, COLOUR_TEXT, true);
        }
    }
}
