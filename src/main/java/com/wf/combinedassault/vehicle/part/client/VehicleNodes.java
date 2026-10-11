package com.wf.combinedassault.vehicle.part.client;

import com.wf.combinedassault.airdefense.client.AirDefenseProbe;
import com.wf.combinedassault.all.AllItems;
import com.wf.combinedassault.ballistics.addon.AddonActions;
import com.wf.combinedassault.ballistics.addon.AddonArmorLayer;
import com.wf.combinedassault.ballistics.addon.AddonFit;
import com.wf.combinedassault.ballistics.addon.AddonTargeting;
import com.wf.combinedassault.ballistics.addon.client.AddonProbe;
import com.wf.combinedassault.ballistics.component.ComponentKind;
import com.wf.combinedassault.ballistics.component.ComponentState;
import com.wf.combinedassault.ballistics.component.SystemRepair;
import com.wf.combinedassault.ballistics.component.VehicleSystems;
import com.wf.combinedassault.ballistics.component.client.SystemsHud;
import com.wf.combinedassault.ballistics.component.client.SystemsProbe;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.combinedassault.vehicle.hit.VehicleHits;
import com.wf.combinedassault.vehicle.part.AccessActions;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.SwitchableUnit;
import com.wf.combinedassault.vehicle.part.WeaponActions;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.wflib.probe.ProbeAction;
import com.wf.wflib.probe.ProbeContext;
import com.wf.wflib.probe.ProbeElement;
import com.wf.wflib.probe.ProbeInfo;
import com.wf.wflib.probe.ProbeInventory;
import com.wf.wflib.probe.ProbeNode;
import com.wf.wflib.probe.ProbeRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Probe nodes on a vehicle, on foot: doors, free seats, hull (storage, systems grid, air defence, repairs without a place),
 * damaged systems at their nearest member, ERA/slat mounts (kit carried), weapon mounts (modding tool carried).
 */
public final class VehicleNodes {

    private static final String KEY = "access.combinedassault.";
    private static final String PROBE = "probe.combinedassault.";
    private static final int DOOR = 0xFFFFD44A;
    private static final int SEAT = 0xFF4AE8FF;
    private static final int HULL = 0xFFE6E6E6;
    private static final int MOUNT_EMPTY = 0xFF8A8A8A;
    private static final int MOUNT_FITTED = 0xFF9ACD60;
    private static final int WEAPON = 0xFFFF9040;
    /** Seat eye point -> node, m. */
    private static final double SEAT_DROP = 0.3;

    private VehicleNodes() {
    }

    public static void register() {
        ProbeRegistry.addEntityNodes(entity -> entity instanceof AbstractVehicle, VehicleNodes::nodes,
                ProbeRegistry.CAPABILITY_PRIORITY);
    }

    private static void nodes(List<ProbeNode> out, ProbeContext ctx, Entity entity) {
        AbstractVehicle vehicle = (AbstractVehicle) entity;
        Player player = ctx.player();
        if (vehicle.isDestroyed() || player.isPassenger()) {
            return;
        }
        float partialTick = ctx.partialTick();
        // Hit / mount poses are tick poses; shift by the hull's interpolation so nodes ride the drawn model.
        Vec3 drift = vehicle.position(partialTick).subtract(vehicle.position());
        boolean aimed = ctx.hit() instanceof EntityHitResult hit && hit.getEntity() == vehicle;
        PartUnit<?> looked = aimed ? VectorUtil.pickPartUnit(player, vehicle) : null;
        VehicleHits hits = vehicle.hits();
        boolean tool = ctx.carrying(AllItems.MODDING_TOOL.get());

        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part.isDetached()) {
                continue;
            }
            if (AccessActions.isDoor(part)) {
                door(out, (SwitchableUnit<?>) part, anchor(hits, part).add(drift), part == looked);
            } else if (tool && part instanceof WeaponUnit unit && unit.isInteractive() && unit.weapons.size() > 1) {
                out.add(new ProbeNode("weapon:" + part.getIndex(), anchor(hits, part).add(drift),
                        info -> weapon(info, unit)).colour(WEAPON).claimed(part == looked));
            }
        }
        if (!vehicle.uav) {
            for (AbstractVehicle.Seat seat : vehicle.seats) {
                if (seat.passengerId != -1 || seat.partUnit.isDetached()) {
                    continue;
                }
                Vec3 at = seat.partUnit.worldPositionWithSelfRot(
                        seat.partUnit.getSeatOffset().subtract(0, SEAT_DROP, 0), partialTick);
                out.add(new ProbeNode("seat:" + seat.seatIndex, at, info -> {
                    info.title(PartNames.name(seat.partUnit));
                    info.action(ProbeAction.of(AccessActions.BOARD, seat.seatIndex, Component.translatable(KEY + "enter")));
                }).colour(SEAT));
            }
        }
        systems(out, ctx, vehicle, drift);
        if (!vehicle.addonArmor().isEmpty() && carriesAddonKit(player)) {
            AddonTargeting.Target target = aimed ? AddonTargeting.underCrosshair(player) : null;
            AddonArmorLayer layer = vehicle.addonArmor();
            for (int i = 0; i < layer.mounts().size(); i++) {
                int index = i;
                AddonArmorLayer.Pose pose = layer.pose(vehicle, i);
                Vector3f outward = pose.rotation().transform(new Vector3f(0, 0, 1));
                out.add(new ProbeNode("addon:" + i, pose.centre().add(drift),
                        info -> AddonProbe.fill(info, ctx, vehicle, index))
                        .normal(new Vec3(outward)).reach(AddonTargeting.REACH + 1)
                        .colour(layer.state(i).isEmpty() ? MOUNT_EMPTY : MOUNT_FITTED)
                        .claimed(target != null && target.vehicle() == vehicle && target.mount() == i));
            }
        }
    }

    private static void door(List<ProbeNode> out, SwitchableUnit<?> door, Vec3 at, boolean looked) {
        boolean jammed = door.isDestroyed();
        out.add(new ProbeNode("door:" + door.getIndex(), at, info -> {
            info.title(PartNames.name(door));
            ProbeAction toggle = ProbeAction.of(AccessActions.TOGGLE, door.getIndex(),
                    Component.translatable(KEY + (door.isOn() ? "close" : "open")));
            info.action(jammed ? toggle.unavailable(Component.translatable("door.combinedassault.jammed")) : toggle);
        }).colour(jammed ? SystemsHud.colour(ComponentState.BROKEN) : DOOR)
                .claimed(looked));
    }

    /** Hull node: grid + rows of damaged systems without geometry; each placed damaged system its own node. */
    private static void systems(List<ProbeNode> out, ProbeContext ctx, AbstractVehicle vehicle, Vec3 drift) {
        List<VehicleSystems.Cell> cells = VehicleSystems.cells(vehicle);
        Vec3 eye = ctx.player().getEyePosition(ctx.partialTick());
        List<VehicleSystems.Cell> unplaced = new ArrayList<>();
        for (VehicleSystems.Cell cell : cells) {
            if (cell.ratio() >= 1) {
                continue;
            }
            Vec3 at = nearest(vehicle, cell, eye);
            if (at == null) {
                unplaced.add(cell);
                continue;
            }
            out.add(new ProbeNode("system:" + cell.key(), at.add(drift), info -> {
                info.title(title(cell), SystemsHud.colour(cell.state()));
                info.entry(Component.translatable(PROBE + "condition"), Component.translatable(cell.state().translationKey()));
                SystemsProbe.repair(info, ctx, cell);
            }).colour(SystemsHud.colour(cell.state())));
        }
        out.add(new ProbeNode("hull", vehicle.getBoundingBox().getCenter().add(drift), info -> {
            info.title(vehicle.getDisplayName());
            if (vehicle.hasContainer()) {
                info.action(ProbeAction.of(AccessActions.STORAGE, 0, Component.translatable(KEY + "storage")));
            }
            SystemsProbe.grid(info, cells);
            AirDefenseProbe.append(info, ctx, vehicle);
            for (VehicleSystems.Cell cell : unplaced) {
                SystemsProbe.repair(info, ctx, cell);
            }
        }).colour(HULL).radius(0.5));
    }

    @Nullable
    private static Vec3 nearest(AbstractVehicle vehicle, VehicleSystems.Cell cell, Vec3 eye) {
        Vec3 best = null;
        for (VehicleSystems.Member member : cell.members()) {
            Vec3 at = member.anchor(vehicle);
            if (at != null && (best == null || at.distanceToSqr(eye) < best.distanceToSqr(eye))) {
                best = at;
            }
        }
        return best;
    }

    private static Component title(VehicleSystems.Cell cell) {
        Component kind = Component.translatable(cell.kind().translationKey());
        return switch (cell.side()) {
            case LEFT -> Component.translatable(PROBE + "side.left", kind);
            case RIGHT -> Component.translatable(PROBE + "side.right", kind);
            case NONE -> kind;
        };
    }

    private static void weapon(ProbeInfo info, WeaponUnit unit) {
        info.title(PartNames.name(unit));
        ProbeElement icon = new ProbeElement.Icon(new ItemStack(AllItems.MODDING_TOOL.get()), 10, false);
        int current = unit.getCurrentWeaponIndex();
        for (int i = 0; i < unit.weapons.size(); i++) {
            Component name = unit.proxyWeapon(unit.weapons.get(i)).getDisplayName();
            if (i == current) {
                info.entry(Component.translatable(PROBE + "weapon"), name);
            } else {
                info.action(ProbeAction.of(WeaponActions.SELECT, WeaponActions.arg(unit.getIndex(), i),
                        Component.translatable(PROBE + "select_weapon", name)).withIcon(icon));
            }
        }
    }

    private static Vec3 anchor(@Nullable VehicleHits hits, PartUnit<?> part) {
        Vec3 centre = hits == null ? null : hits.centre(part);
        return centre != null ? centre : part.worldPivotPosition();
    }

    /** Any fit, slat stock or a hull repair tool. */
    private static boolean carriesAddonKit(Player player) {
        return ProbeInventory.has(player, stack -> AddonFit.ofItem(stack.getItem()) != null
                || stack.is(AddonActions.SLAT_STOCK)) || !SystemRepair.tool(player, ComponentKind.HULL).isEmpty();
    }
}
