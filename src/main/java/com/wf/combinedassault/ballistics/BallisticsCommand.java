package com.wf.combinedassault.ballistics;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.wf.wflib.ammo.api.Ammo;
import com.wf.wflib.ammo.api.AmmoVariant;
import com.wf.wflib.damage.api.ThreatKind;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.resources.ResourceLocation;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.ballistics.component.ComponentDamage;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.weapon.data.BaseVehicleWeaponData;
import com.wf.combinedassault.ballistics.component.VehicleMobility;
import com.wf.combinedassault.ballistics.addon.AddonCommand;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.util.VectorUtil;
import com.wf.wflib.geom.api.OBB;
import com.wf.combinedassault.vehicle.part.PartUnit;

import java.util.Locale;
import java.util.List;
import java.util.ArrayList;
import com.wf.combinedassault.dynamics.structure.VehicleCubeOBB;
import com.wf.wflib.physics.api.HullTag;
import com.wf.combinedassault.ballistics.hit.HitMesh;
import com.wf.combinedassault.ballistics.hit.HitModel;
import com.wf.combinedassault.ballistics.hit.VehicleHits;
import com.wf.combinedassault.ballistics.component.HullComponents;
import com.wf.combinedassault.ballistics.component.VehicleSystems;
import com.wf.wflib.armor.api.hull.HullProfile;
import com.wf.wflib.armor.api.hull.HullResult;

/**
 * Debug readout for the armour model on the looked-at vehicle.
 * Aim with {@code /tp} when headless (no mouse to aim a real shot).
 */
public final class BallisticsCommand {

    private static final double REACH = 64.0;
    private static final float PROBE_DAMAGE = 1000f;

    private BallisticsCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        return Commands.literal("ballistics")
                .then(Commands.literal("probe")
                        .executes(context -> probe(context.getSource(), 700f))
                        .then(Commands.argument("penetration", FloatArgumentType.floatArg(1))
                                .executes(context -> probe(context.getSource(),
                                        FloatArgumentType.getFloat(context, "penetration"))))
                        .then(Commands.literal("weapon")
                                .then(Commands.argument("weapon_id", ResourceLocationArgument.id())
                                        .executes(context -> probeWeapon(context.getSource(),
                                                ResourceLocationArgument.getId(context, "weapon_id")))))
                        .then(Commands.literal("round")
                                .then(Commands.argument("variant", ResourceLocationArgument.id())
                                        .executes(context -> probeRound(context.getSource(),
                                                ResourceLocationArgument.getId(context, "variant"))))))
                .then(Commands.literal("components")
                        .executes(context -> components(context.getSource())))
                .then(Commands.literal("spawn")
                        .then(Commands.argument("vehicle", ResourceLocationArgument.id())
                                .executes(context -> spawn(context.getSource(),
                                        ResourceLocationArgument.getId(context, "vehicle")))))
                .then(Commands.literal("addon")
                        .then(Commands.literal("fill")
                                .then(Commands.argument("fit", StringArgumentType.word())
                                        .executes(context -> AddonCommand.fill(context.getSource(),
                                                StringArgumentType.getString(context, "fit")))))
                        .then(Commands.literal("clear")
                                .executes(context -> AddonCommand.clear(context.getSource())))
                        .then(Commands.literal("stats")
                                .executes(context -> AddonCommand.stats(context.getSource())))
                        .then(Commands.literal("shoot")
                                .then(Commands.argument("mount", StringArgumentType.word())
                                        .then(Commands.argument("round", ResourceLocationArgument.id())
                                                .executes(context -> AddonCommand.shoot(context.getSource(),
                                                        StringArgumentType.getString(context, "mount"),
                                                        ResourceLocationArgument.getId(context, "round"), 0))
                                                .then(Commands.argument("obliquity", FloatArgumentType.floatArg(0, 85))
                                                        .executes(context -> AddonCommand.shoot(context.getSource(),
                                                                StringArgumentType.getString(context, "mount"),
                                                                ResourceLocationArgument.getId(context, "round"),
                                                                FloatArgumentType.getFloat(context, "obliquity"))))))))
                .then(Commands.literal("hurt")
                        .then(Commands.argument("part", StringArgumentType.string())
                                .then(Commands.argument("amount", FloatArgumentType.floatArg(0))
                                        .executes(context -> hurt(context.getSource(),
                                                StringArgumentType.getString(context, "part"),
                                                FloatArgumentType.getFloat(context, "amount"))))))
                .then(Commands.literal("system")
                        .then(Commands.argument("cell", StringArgumentType.string())
                                .then(Commands.argument("ratio", FloatArgumentType.floatArg(0, 1))
                                        .executes(context -> system(context.getSource(),
                                                StringArgumentType.getString(context, "cell"),
                                                FloatArgumentType.getFloat(context, "ratio"))))));
    }

    /** Sets every member of the looked-at vehicle's cell (HUD label, e.g. {@code L-TR}) to {@code ratio} of max. */
    private static int system(CommandSourceStack source, String label, float ratio) {
        Vec3 start = source.getPosition().add(0, source.getEntity() == null ? 0 : source.getEntity().getEyeHeight(), 0);
        Vec3 look = Vec3.directionFromRotation(source.getRotation().x, source.getRotation().y);
        AbstractVehicle vehicle = lookedAt(source, start, start.add(look.scale(REACH)));
        if (vehicle == null) {
            source.sendFailure(Component.literal("no vehicle in the way"));
            return 0;
        }
        for (VehicleSystems.Cell cell : VehicleSystems.cells(vehicle)) {
            if (cell.label().equalsIgnoreCase(label)) {
                cell.members().forEach(member -> member.setHealth(member.maxHealth() * ratio));
                source.sendSuccess(() -> Component.literal(cell.label() + " -> " + cell.state().name()), false);
                return 1;
            }
        }
        source.sendFailure(Component.literal("no cell " + label));
        return 0;
    }

    private static int probe(CommandSourceStack source, float penetration) {
        Vec3 start = source.getPosition().add(0, source.getEntity() == null ? 0 : source.getEntity().getEyeHeight(), 0);
        Vec3 look = source.getRotation() == null ? Vec3.ZERO
                : Vec3.directionFromRotation(source.getRotation().x, source.getRotation().y);
        Vec3 end = start.add(look.scale(REACH));
        AbstractVehicle vehicle = lookedAt(source, start, end);
        if (vehicle == null) {
            source.sendFailure(Component.literal("no vehicle in the way"));
            return 0;
        }
        // Hit mesh => its point: an OBB point short of the mesh leaves the resolver's trace in front of it.
        Vec3 hitPos;
        if (vehicle.hits() != null) {
            VehicleHits.Hit hit = vehicle.hits().surface(start, end);
            hitPos = hit == null ? null : hit.point();
        } else {
            OBB.SegmentHit hit = OBB.first(vehicle.getOBBs(), start, end);
            hitPos = hit == null ? null : hit.point();
        }
        if (hitPos == null) {
            source.sendFailure(Component.literal("ray misses ").append(vehicle.getDisplayName()));
            return 0;
        }
        HullProfile profile = vehicle.armorProfile;
        if (profile == null || !profile.isDefined()) {
            source.sendSuccess(() -> Component.empty().append(vehicle.getDisplayName())
                    .append(" has no armour profile; every hit on it resolves with the stock damage model")
                    .withStyle(ChatFormatting.YELLOW), false);
            return 1;
        }
        PartUnit<?> part = VectorUtil.hitPartUnit(vehicle, start, end, false);
        HullResult result = new ArmorResolver().resolve(vehicle, start, look, hitPos,
                penetration, PROBE_DAMAGE, ThreatKind.KINETIC);
        if (result == null) {
            source.sendFailure(Component.literal("no answer: the face hit has no armour set"));
            return 0;
        }
        // incidence recovered from nominal/effective ratio; avoids re-tracing the hit.
        double cos = result.effectiveThickness() <= 0 ? 1
                : Math.min(1.0, result.nominalThickness() / result.effectiveThickness());
        double incidence = Math.toDegrees(Math.acos(cos));
        String partName = part == null ? "hull" : part.getId();
        source.sendSuccess(() -> Component.empty().append(vehicle.getDisplayName()).append(String.format(Locale.ROOT,
                " @ %s | %.0f mm at %.0f deg = %.0f mm effective | pen %.0f -> ratio %.2f | %s | %.0f of %.0f damage",
                partName,
                result.nominalThickness(), incidence, result.effectiveThickness(),
                result.penetration(), result.ratio(), result.outcome().name(),
                result.hullDamage(), PROBE_DAMAGE)), false);
        return 1;
    }

    /** Probes with the weapon's default round; only way to confirm the weapon JSON actually loaded. */
    private static int probeWeapon(CommandSourceStack source, ResourceLocation weaponId) {
        var index = CommonAssetsManager.vehicleWeaponManager().getIndex(weaponId).orElse(null);
        if (index == null || !(index.data() instanceof BaseVehicleWeaponData data)) {
            source.sendFailure(Component.literal("no weapon data for " + weaponId));
            return 0;
        }
        var round = data.getReload() == null ? null
                : com.wf.combinedassault.weapon.ordnance.Munitions.kinetic(data.getReload().defaultRound());
        if (round == null) {
            source.sendFailure(Component.literal(weaponId + ": default round is not a WFLib kinetic round"));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%s: %s, caliber %.0f, penetration %.0f",
                data.getName(), round.id(), round.caliberMm(), round.armorPenetrationMm())), false);
        return probe(source, round.armorPenetrationMm());
    }

    /** WFLib round (e.g. a MineAssault gun's): its {@code armorPenMm}. */
    private static int probeRound(CommandSourceStack source, ResourceLocation variantId) {
        AmmoVariant variant = Ammo.variant(variantId);
        if (variant == null) {
            source.sendFailure(Component.literal("no WFLib round " + variantId));
            return 0;
        }
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "%s: penetration %.1f mm",
                variant.name(), variant.armorPenMm())), false);
        return probe(source, variant.armorPenMm());
    }

    private static int components(CommandSourceStack source) {
        Vec3 start = source.getPosition().add(0, source.getEntity() == null ? 0 : source.getEntity().getEyeHeight(), 0);
        Vec3 look = Vec3.directionFromRotation(source.getRotation().x, source.getRotation().y);
        AbstractVehicle vehicle = lookedAt(source, start, start.add(look.scale(REACH)));
        if (vehicle == null) {
            source.sendFailure(Component.literal("no vehicle in the way"));
            return 0;
        }
        VehicleMobility mobility = VehicleMobility.of(vehicle);
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "%s | hull %.0f/%.0f | mobility speed %.2f turn %.2f",
                vehicle.getName().getString(), vehicle.getHealth(), vehicle.getMaxHealth(),
                mobility.speedScale(), mobility.turnScale())), false);
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            String line = String.format(Locale.ROOT, "  %s: %s %s %.0f/%.0f, effectiveness %.2f",
                    part.getId(), ComponentDamage.kind(part).name(), ComponentDamage.state(part).name(),
                    part.getHealth(), part.getMaxHealth(), ComponentDamage.effectiveness(part));
            source.sendSuccess(() -> Component.literal(line), false);
        }
        hullSystems(source, vehicle);
        hullPieces(source, vehicle);
        return 1;
    }

    private static void hullSystems(CommandSourceStack source, AbstractVehicle vehicle) {
        HullComponents systems = vehicle.hullComponents();
        for (int i = 0; i < systems.size(); i++) {
            int index = i;
            String line = String.format(Locale.ROOT, "  %s: %s %s %.0f/%.0f, effectiveness %.2f%s",
                    systems.id(index), systems.kind(index).name(), systems.state(index).name(),
                    systems.health(index), systems.maxHealth(index), systems.effectiveness(index),
                    systems.isImplicit(index) ? ", default" : "");
            source.sendSuccess(() -> Component.literal(line), false);
        }
        StringBuilder cells = new StringBuilder("  cells:");
        for (VehicleSystems.Cell cell : VehicleSystems.cells(vehicle)) {
            cells.append(String.format(Locale.ROOT, " %s=%s(%.0f%%)", cell.label(), cell.state().name(), cell.ratio() * 100));
        }
        String cellLine = cells.toString();
        source.sendSuccess(() -> Component.literal(cellLine), false);
    }

    /** Collision pieces + hit-model nodes as loaded; checkable without a client. */
    private static void hullPieces(CommandSourceStack source, AbstractVehicle vehicle) {
        List<VehicleCubeOBB> tagged = new ArrayList<>();
        for (VehicleCubeOBB cube : vehicle.getVehicleCubeOBBs()) {
            if (cube.hasHull()) {
                tagged.add(cube);
            }
        }
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            for (VehicleCubeOBB cube : part.getPartCubeOBBs()) {
                if (cube.hasHull()) {
                    tagged.add(cube);
                }
            }
        }
        if (tagged.isEmpty()) {
            source.sendSuccess(() -> Component.literal("  no hull pieces; this vehicle is boxes"), false);
            return;
        }
        for (VehicleCubeOBB cube : tagged) {
            HullTag tag = cube.tag();
            String label = tag.name();
            // collides=false: detail piece, tagged/shot/drawn but no collision response.
            boolean collides = vehicle.collisionBody().solvesAgainst(cube);
            String line = String.format(Locale.ROOT,
                    "  hull %s: %d faces%s, %s",
                    label.isEmpty() ? boneOf(cube) : label + " (" + boneOf(cube) + ")",
                    cube.hull().faceCount(),
                    tag.solid() ? "" : ", not solid",
                    collides ? "collides" : "inside the hull");
            source.sendSuccess(() -> Component.literal(line), false);
        }
        VehicleHits hits = vehicle.hits();
        if (hits == null) {
            source.sendSuccess(() -> Component.literal("  no hit model; structure boxes are the hit surface"), false);
            return;
        }
        source.sendSuccess(() -> Component.literal("  hit model " + hits.model().mesh().source()), false);
        for (int i = 0; i < hits.model().nodes().size(); i++) {
            HitModel.Node node = hits.model().nodes().get(i);
            HitMesh.Node mesh = node.mesh();
            if (mesh.mesh() == null && mesh.disc() == null) {
                continue;
            }
            String line = String.format(Locale.ROOT, "  hit %s (%s): %s%s, %s",
                    mesh.name(), hits.groups()[node.group()].name,
                    node.part() != null && !mesh.module() ? "part " + node.part()
                            : node.kind() != null ? "system " + node.systemId() : "structure",
                    mesh.module() ? ", module" : "",
                    mesh.disc() != null ? String.format(Locale.ROOT, "disc r=%.2f", mesh.disc().radius())
                            : mesh.mesh().triangleCount() + " tris" + (mesh.mesh().hasArmor() ? ", armoured" : ""));
            source.sendSuccess(() -> Component.literal(line), false);
        }
    }

    private static String boneOf(VehicleCubeOBB cube) {
        var named = cube.group == null ? null : cube.group.namedAncestor();
        return named == null || named.name.isEmpty() ? "?" : named.name;
    }

    /**
     * Spawns without a player (stock summon needs a {@code ServerPlayer} for position; console
     * and {@code /execute} have none). Console-only path matters: only way to aim a probe exactly.
     */
    private static int spawn(CommandSourceStack source, ResourceLocation vehicleId) {
        if (source.getLevel() == null) {
            return 0;
        }
        BaseVehicleData data = CommonAssetsManager.vehicleDataManager().getVehicleData(vehicleId).orElse(null);
        if (data == null) {
            source.sendFailure(Component.literal("no vehicle data for " + vehicleId));
            return 0;
        }
        AbstractVehicle vehicle = data.construct(source.getLevel(), source.getPosition(), 0,
                source.getRotation() == null ? 0 : source.getRotation().y);
        source.getLevel().addFreshEntity(vehicle);
        source.sendSuccess(() -> Component.literal("spawned " + vehicleId + " at "
                + String.format(Locale.ROOT, "%.1f %.1f %.1f", vehicle.getX(), vehicle.getY(), vehicle.getZ())), false);
        return 1;
    }

    /** Debug-damages a part without a gunner; walks its health bands directly. */
    private static int hurt(CommandSourceStack source, String partId, float amount) {
        Vec3 start = source.getPosition().add(0, source.getEntity() == null ? 0 : source.getEntity().getEyeHeight(), 0);
        Vec3 look = Vec3.directionFromRotation(source.getRotation().x, source.getRotation().y);
        AbstractVehicle vehicle = lookedAt(source, start, start.add(look.scale(REACH)));
        if (vehicle == null) {
            source.sendFailure(Component.literal("no vehicle in the way"));
            return 0;
        }
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part.getId().equals(partId)) {
                part.setHealth(Math.max(0, part.getHealth() - amount));
                source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "%s -> %.0f/%.0f, %s, effectiveness %.2f", part.getId(), part.getHealth(),
                        part.getMaxHealth(), ComponentDamage.state(part).name(),
                        ComponentDamage.effectiveness(part))), false);
                return 1;
            }
        }
        source.sendFailure(Component.literal("no part called " + partId));
        return 0;
    }

    @Nullable
    private static AbstractVehicle lookedAt(CommandSourceStack source, Vec3 start, Vec3 end) {
        if (source.getLevel() == null) {
            return null;
        }
        AABB search = new AABB(start, end).inflate(2.0);
        AbstractVehicle closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Entity entity : source.getLevel().getEntities((Entity) null, search, e -> e instanceof AbstractVehicle)) {
            Vec3 hit = VectorUtil.closestHitObbPosition(entity, start, end);
            if (hit == null) {
                continue;
            }
            double distance = hit.distanceToSqr(start);
            if (distance < closestDistance) {
                closestDistance = distance;
                closest = (AbstractVehicle) entity;
            }
        }
        return closest;
    }

}
