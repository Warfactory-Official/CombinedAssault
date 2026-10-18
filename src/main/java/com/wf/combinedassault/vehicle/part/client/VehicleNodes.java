package com.wf.combinedassault.vehicle.part.client;

import com.wf.combinedassault.airdefense.client.AirDefenseProbe;
import com.wf.combinedassault.all.AllItems;
import com.wf.combinedassault.ballistics.ArmorRegions;
import com.wf.combinedassault.ballistics.addon.AddonActions;
import com.wf.combinedassault.ballistics.addon.AddonArmorLayer;
import com.wf.combinedassault.ballistics.addon.AddonFit;
import com.wf.combinedassault.ballistics.addon.AddonTargeting;
import com.wf.combinedassault.ballistics.addon.client.AddonProbe;
import com.wf.combinedassault.ballistics.component.ArmorRepair;
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
import com.wf.combinedassault.vehicle.part.BarrelCharge;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.SwitchableUnit;
import com.wf.combinedassault.vehicle.part.WeaponActions;
import com.wf.combinedassault.vehicle.part.StationActions;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;
import com.wf.combinedassault.vehicle.structure.VehicleCubeGroup;
import com.wf.combinedassault.client.model.VehicleModelInstance;
import com.wf.combinedassault.client.render.entity.vehicle.VehicleRender;
import com.wf.combinedassault.vehicle.loadout.Loadouts;
import com.wf.combinedassault.vehicle.part.MissileHolderUnit;
import com.wf.combinedassault.vehicle.loadout.client.LoadoutProbe;
import com.wf.combinedassault.vehicle.hardware.Hardpoints;
import com.wf.combinedassault.vehicle.hardware.client.HardpointProbe;
import com.wf.combinedassault.vehicle.pojo.LoadoutInfo;
import com.wf.combinedassault.vehicle.pojo.Bolt;
import com.wf.combinedassault.vehicle.structure.VehicleCubeOBB;
import com.wf.wflib.util.OBB;
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
 * damaged systems at their nearest member, worn armour faces, ERA/slat mounts (kit carried), weapon mounts (modding tool
 * carried), large-bore muzzles (thermite charge carried), exposed ordnance stations (fitting round carried or empty hand).
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
    private static final int THERMITE = 0xFFFF5A1E;
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
        int from = out.size();

        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part.isDetached()) {
                continue;
            }
            if (AccessActions.isDoor(part)) {
                door(out, (SwitchableUnit<?>) part, VehicleHits.anchor(hits, part).add(drift), part == looked);
            } else if (tool && part instanceof WeaponUnit unit && unit.isInteractive() && unit.weapons.size() > 1
                    && !unit.isHardpoint()) {
                out.add(new ProbeNode("weapon:" + part.getIndex(), VehicleHits.anchor(hits, part).add(drift),
                        info -> weapon(info, unit)).colour(WEAPON).claimed(part == looked));
            }
        }
        LoadoutInfo loadout = vehicle.loadout();
        PartUnit<?> hardpoint = Loadouts.part(vehicle, loadout);
        if (loadout != null && loadout.payload != null && hardpoint != null && !hardpoint.isDetached()) {
            out.add(new ProbeNode("loadout", VehicleHits.anchor(hits, hardpoint).add(drift),
                    info -> LoadoutProbe.fill(info, ctx, vehicle, loadout, hardpoint))
                    .colour(MOUNT_FITTED).reach(Loadouts.REACH).claimed(hardpoint == looked));
        }
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part instanceof WeaponUnit unit && unit.isHardpoint() && !part.isDetached()) {
                out.add(new ProbeNode("hardpoint:" + part.getIndex(), VehicleHits.anchor(hits, part).add(drift),
                        info -> HardpointProbe.fill(info, ctx, unit))
                        .colour(unit.kit() != null ? MOUNT_FITTED : MOUNT_EMPTY).reach(Hardpoints.REACH)
                        .claimed(part == looked));
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
        stations(out, ctx, vehicle);
        systems(out, ctx, vehicle, drift);
        armor(out, ctx, vehicle, drift);
        if (ProbeInventory.has(player, BarrelCharge::thermite)) {
            barrels(out, vehicle, drift);
        }
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
        if (hits != null) {
            Vec3 eye = player.getEyePosition(partialTick).subtract(drift);
            out.subList(from, out.size()).removeIf(node -> !node.key().equals("hull")
                    && hits.hidden(eye, node.at().subtract(drift)));
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

    /** Holder stations, weapon {@code round_bones} stations ({@link StationActions}). */
    private static void stations(List<ProbeNode> out, ProbeContext ctx, AbstractVehicle vehicle) {
        Player player = ctx.player();
        float partialTick = ctx.partialTick();
        boolean emptyHand = player.getMainHandItem().isEmpty();
        VehicleModelInstance model = null;
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part.isDetached()) {
                continue;
            }
            if (part instanceof MissileHolderUnit holder) {
                for (int i = 0; i < holder.getCapacity(); i++) {
                    int slot = i;
                    VehicleCubeGroup.GlobalTransform at = holder.stationInModel(i, partialTick);
                    ItemStack round = holder.round(i);
                    ItemStack carried = ProbeInventory.find(player, s -> holder.canLoad(slot, s));
                    if (at == null || round.isEmpty() && carried.isEmpty() || !round.isEmpty() && !emptyHand && carried.isEmpty()) {
                        continue;
                    }
                    int arg = StationActions.holder(part.getIndex(), i);
                    out.add(new ProbeNode("station:" + arg, toWorld(vehicle, at.offset().toVector3f(), partialTick),
                            info -> station(info, PartNames.name(holder), slot + 1, round, carried, arg))
                            .colour(round.isEmpty() ? MOUNT_EMPTY : MOUNT_FITTED));
                }
            } else if (part instanceof WeaponUnit unit) {
                for (int w = 0; w < unit.getIndexedWeapons().size(); w++) {
                    AbstractVehicleWeapon<?> weapon = StationActions.stationed(part, w);
                    if (weapon == null) {
                        continue;
                    }
                    if (model == null) {
                        model = VehicleRender.activeModelInstance(vehicle);
                        if (model == null || !model.posed()) {
                            return;
                        }
                    }
                    List<String> bones = weapon.getRoundBones();
                    int fired = bones.size() - weapon.getRemainAmmo();
                    ItemStack carried = ProbeInventory.find(player, s -> StationActions.fits(weapon, s));
                    int arg = StationActions.weapon(part.getIndex(), w);
                    for (int b = 0; b < bones.size(); b++) {
                        boolean loaded = b >= fired;
                        // Next to fill / next to empty: the only bones a load / unload changes.
                        boolean next = loaded ? b == fired && (emptyHand || !carried.isEmpty()) : b == fired - 1 && !carried.isEmpty();
                        int bone = model.boneIndex(bones.get(b));
                        if (!next || bone < 0) {
                            continue;
                        }
                        ItemStack round = loaded ? weapon.getLoadedRound() : ItemStack.EMPTY;
                        Vector3f at = model.globalTransform(bone).getTranslation(new Vector3f());
                        int number = b + 1;
                        out.add(new ProbeNode("station:" + arg + ":" + b, toWorld(vehicle, at, partialTick),
                                info -> station(info, weapon.getDisplayName(), number, round, carried, arg))
                                .colour(loaded ? MOUNT_FITTED : MOUNT_EMPTY));
                    }
                }
            }
        }
    }

    private static void station(ProbeInfo info, Component name, int number, ItemStack round, ItemStack carried, int arg) {
        info.title(Component.translatable(PROBE + "station", name, number));
        info.entry(Component.translatable(PROBE + "station_round"), round.isEmpty()
                ? Component.translatable(PROBE + "station_empty") : round.getHoverName());
        if (round.isEmpty()) {
            info.action(ProbeAction.of(StationActions.LOAD, arg, Component.translatable(PROBE + "station_load",
                    carried.getHoverName())).withIcon(new ProbeElement.Icon(carried, 10, false)));
        } else {
            info.action(ProbeAction.of(StationActions.UNLOAD, arg, Component.translatable(PROBE + "station_unload",
                    round.getHoverName())).withIcon(new ProbeElement.Icon(round, 10, false)));
            if (!carried.isEmpty()) {
                info.action(ProbeAction.of(StationActions.LOAD, arg, Component.translatable(PROBE + "station_load",
                        carried.getHoverName())).withIcon(new ProbeElement.Icon(carried, 10, false)));
            }
        }
    }

    /** Vehicle model frame -> world, as {@link VehicleRender#applyVehicleRotation} draws it. */
    private static Vec3 toWorld(AbstractVehicle vehicle, Vector3f model, float partialTick) {
        Vec3 c = vehicle.centerOffset;
        Vector3f arm = vehicle.rotYXZ(partialTick).transform(model.sub((float) c.x, (float) c.y, (float) c.z));
        return vehicle.position(partialTick).add(c).add(arm.x, arm.y, arm.z);
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
            ItemStack held = ctx.player().getMainHandItem();
            // UAV + item it takes: use falls through to the strap / missile load.
            if (vehicle.hasContainer() && !(vehicle.uav && (Loadouts.strapIndex(vehicle, held) >= 0
                    || MissileHolderUnit.couldLoad(vehicle, held)))) {
                info.action(ProbeAction.of(AccessActions.STORAGE, 0, Component.translatable(KEY + "storage")));
            }
            if (vehicle.pickup && !vehicle.isDestroyed()) {
                ProbeAction pickUp = ProbeAction.of(AccessActions.PICK_UP, 0, Component.translatable(KEY + "pick_up"));
                info.action(vehicle.isVehicle() || vehicle.isPassenger()
                        ? pickUp.unavailable(Component.translatable(KEY + "pick_up.occupied")) : pickUp);
            }
            SystemsProbe.grid(info, cells);
            AirDefenseProbe.append(info, ctx, vehicle);
            for (VehicleSystems.Cell cell : unplaced) {
                SystemsProbe.repair(info, ctx, cell);
            }
        }).colour(HULL).radius(0.5));
    }

    /** One node per worn armour face, at the face centre of the main box. */
    private static void armor(List<ProbeNode> out, ProbeContext ctx, AbstractVehicle vehicle, Vec3 drift) {
        VehicleCubeOBB main = vehicle.getMainCubeOBB();
        if (vehicle.armorProfile == null || !vehicle.armorProfile.isDefined() || main == null) {
            return;
        }
        ArmorRegions regions = vehicle.armorRegions();
        OBB box = main.obb();
        Vector3f[] axes = box.getAxes();
        Vector3f ext = box.extents();
        for (ArmorRegions.Face face : ArmorRegions.Face.VALUES) {
            if (!regions.worn(face) || vehicle.armorProfile.nominal(face.profile) <= 0) {
                continue;
            }
            int axis = switch (face) {
                case LEFT, RIGHT -> 0;
                case TOP, BOTTOM -> 1;
                case FRONT, REAR -> 2;
            };
            float sign = face == ArmorRegions.Face.REAR || face == ArmorRegions.Face.RIGHT
                    || face == ArmorRegions.Face.BOTTOM ? -1 : 1;
            Vec3 normal = new Vec3(new Vector3f(axes[axis]).mul(sign));
            Vec3 at = box.centerVec().add(normal.scale(ext.get(axis)));
            float integrity = regions.integrity(face);
            ComponentState state = ComponentState.of(integrity);
            out.add(new ProbeNode("armor:" + face.ordinal(), at.add(drift), info -> {
                info.title(Component.translatable(PROBE + "armor." + face.key()), SystemsHud.colour(state));
                info.entry(Component.translatable(PROBE + "integrity"),
                        Component.literal(Math.round(integrity * 100) + "%"));
                ProbeAction repair = ProbeAction.of(ArmorRepair.ACTION, face.ordinal(),
                        Component.translatable(PROBE + "armor_repair")).timed(ArmorRepair.TICKS);
                ItemStack tool = SystemRepair.tool(ctx.player(), ComponentKind.HULL);
                if (!tool.isEmpty()) {
                    repair = repair.withIcon(new ProbeElement.Icon(tool, 10, false));
                }
                Component refusal = ArmorRepair.refusal(ctx.player(), vehicle, face);
                info.action(refusal == null ? repair : repair.unavailable(refusal));
            }).colour(SystemsHud.colour(state)).normal(normal));
        }
    }

    /** Large-bore muzzles: drop a carried thermite charge down the barrel. */
    private static void barrels(List<ProbeNode> out, AbstractVehicle vehicle, Vec3 drift) {
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (!(part instanceof WeaponUnit unit) || !BarrelCharge.accepts(unit)) {
                continue;
            }
            List<Bolt> bolts = unit.getBolts();
            for (int b = 0; b < bolts.size(); b++) {
                Vec3[] muzzle = BarrelCharge.muzzle(unit, bolts.get(b));
                int arg = BarrelCharge.arg(unit.getIndex(), b);
                out.add(new ProbeNode("barrel:" + arg, muzzle[0].add(drift), info -> {
                    info.title(PartNames.name(unit));
                    info.action(ProbeAction.of(BarrelCharge.ACTION, arg,
                            Component.translatable(PROBE + "barrel_charge")).timed(BarrelCharge.INSERT_TICKS));
                }).colour(THERMITE).normal(muzzle[1]).reach(BarrelCharge.REACH));
            }
        }
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

    /** Any fit, slat stock or a hull repair tool. */
    private static boolean carriesAddonKit(Player player) {
        return ProbeInventory.has(player, stack -> AddonFit.ofItem(stack.getItem()) != null
                || stack.is(AddonActions.SLAT_STOCK)) || !SystemRepair.tool(player, ComponentKind.HULL).isEmpty();
    }
}
