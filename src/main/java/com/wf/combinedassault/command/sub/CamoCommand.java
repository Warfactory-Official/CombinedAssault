package com.wf.combinedassault.command.sub;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.wf.combinedassault.all.AllConfigs;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.combinedassault.vehicle.camo.CamoKind;
import com.wf.combinedassault.vehicle.camo.CamoLayer;
import com.wf.combinedassault.vehicle.camo.CamoPiece;
import com.wf.combinedassault.vehicle.camo.CamoPlacement;
import com.wf.combinedassault.vehicle.pojo.CamoAction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** No explicit broadcast: mutate layer, vehicle dirty flag resends. Command touches whole layer anyway => resend cost unchanged. */
public class CamoCommand {

    private static final String NAME = "camo";
    private static final String COUNT = "count";
    private static final String MATERIAL = "material";
    private static final double SEARCH_RADIUS = 32.0;
    /** Clone offset jitter, blocks. */
    private static final float SPREAD = 0.55f;
    private static final float SEED_SCALE = 0.45f;
    /** Rays per piece; thin wings miss often. */
    private static final int ATTEMPTS_PER_PIECE = 8;
    private static final String SEED_MATERIAL = "minecraft:oak_leaves";

    public static LiteralArgumentBuilder<CommandSourceStack> get() {
        return Commands.literal(NAME)
                .then(Commands.literal("stats").executes(CamoCommand::stats))
                .then(Commands.literal("clear").executes(CamoCommand::clear))
                .then(Commands.literal("seed")
                        .then(Commands.argument(COUNT, IntegerArgumentType.integer(1, 4096))
                                .executes(context -> seed(context, IntegerArgumentType.getInteger(context, COUNT),
                                        SEED_MATERIAL))
                                .then(Commands.argument(MATERIAL, StringArgumentType.greedyString())
                                        .executes(context -> seed(context, IntegerArgumentType.getInteger(context, COUNT),
                                                StringArgumentType.getString(context, MATERIAL))))))
                .then(Commands.literal("fill")
                        .then(Commands.argument(COUNT, IntegerArgumentType.integer(1, 4096))
                                .executes(context -> fill(context, IntegerArgumentType.getInteger(context, COUNT), null))
                                .then(Commands.literal("cube").executes(context ->
                                        fill(context, IntegerArgumentType.getInteger(context, COUNT), CamoKind.CUBE)))
                                .then(Commands.literal("plate").executes(context ->
                                        fill(context, IntegerArgumentType.getInteger(context, COUNT), CamoKind.PLATE)))));
    }

    private static int stats(CommandContext<CommandSourceStack> context) {
        AbstractVehicle vehicle = target(context);
        if (vehicle == null) {
            return 0;
        }
        CamoLayer layer = vehicle.getCamoLayer();
        context.getSource().sendSuccess(() -> Component.literal(
                "camo: %d pieces, footprint %.2f m2, version %d"
                        .formatted(layer.size(), layer.footprint(), layer.version())), false);
        // Piece bone absent from hull => draws nothing, no error.
        int[] perBone = new int[layer.boneCount()];
        for (CamoPiece piece : layer.pieces()) {
            if (piece.bone() >= 0 && piece.bone() < perBone.length) {
                perBone[piece.bone()]++;
            }
        }
        StringBuilder bones = new StringBuilder();
        for (int i = 0; i < perBone.length; i++) {
            if (perBone[i] == 0) {
                continue;
            }
            String name = layer.bone(i);
            bones.append(bones.isEmpty() ? "" : ", ")
                    .append(name.isEmpty() ? "<root>" : name).append(": ").append(perBone[i]);
        }
        if (!bones.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("bones: " + bones), false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int clear(CommandContext<CommandSourceStack> context) {
        AbstractVehicle vehicle = target(context);
        if (vehicle == null) {
            return 0;
        }
        CamoAction action = new CamoAction();
        action.action = CamoAction.Action.CLEAR;
        action.vehicleId = vehicle.getId();
        int removed = vehicle.getCamoLayer().apply(context.getSource().registryAccess(), action, Integer.MAX_VALUE);
        context.getSource().sendSuccess(() -> Component.literal("cleared " + removed + " pieces"), false);
        return Command.SINGLE_SUCCESS;
    }

    /** Same ray trace as a player click. Server-only path: {@code fill} needs seeds, hand-painting needs a client. */
    private static int seed(CommandContext<CommandSourceStack> context, int count, String material) {
        AbstractVehicle vehicle = target(context);
        if (vehicle == null) {
            return 0;
        }
        HolderLookup.RegistryLookup<Block> blocks = context.getSource().registryAccess().lookupOrThrow(Registries.BLOCK);
        try {
            BlockStateParser.parseForBlock(blocks, material, false);
        } catch (Exception exception) {
            context.getSource().sendFailure(Component.literal("not a block state: " + material));
            return 0;
        }
        CamoAction action = new CamoAction();
        action.action = CamoAction.Action.PAINT;
        action.vehicleId = vehicle.getId();
        action.bones = new ArrayList<>();
        action.palette = new ArrayList<>(List.of(material));
        action.pieces = new ArrayList<>();
        Map<String, Integer> boneIds = new HashMap<>();
        RandomSource random = vehicle.getRandom();
        AABB hull = vehicle.getBoundingBox();
        Vec3 centre = hull.getCenter();
        // Max axis size: ray start clears hull from any direction.
        double radius = Math.max(hull.getXsize(), Math.max(hull.getYsize(), hull.getZsize()));
        int attempts = count * ATTEMPTS_PER_PIECE;
        while (action.pieces.size() < count && attempts-- > 0) {
            Vec3 direction = randomDirection(random);
            VectorUtil.HitBone hit = VectorUtil.hitHull(vehicle,
                    centre.add(direction.scale(radius)), centre, CamoPlacement.PLATE_NORMAL);
            if (hit == null) {
                continue;
            }
            int bone = boneIds.computeIfAbsent(hit.boneName(), name -> {
                action.bones.add(name);
                return action.bones.size() - 1;
            });
            action.pieces.add(CamoPlacement.from(hit, bone, 0, CamoKind.CUBE, SEED_SCALE, 1f, random));
        }
        int painted = vehicle.getCamoLayer()
                .apply(context.getSource().registryAccess(), action, AllConfigs.Cached.camoMaxPieces);
        int total = vehicle.getCamoLayer().size();
        context.getSource().sendSuccess(() -> Component.literal(
                "seeded " + painted + " pieces on the hull, now " + total), false);
        return Command.SINGLE_SUCCESS;
    }

    /** Uniform on sphere; not naive per-axis (would clump at poles). */
    private static Vec3 randomDirection(RandomSource random) {
        double y = random.nextDouble() * 2 - 1;
        double angle = random.nextDouble() * Math.PI * 2;
        double ring = Math.sqrt(1 - y * y);
        return new Vec3(ring * Math.cos(angle), y, ring * Math.sin(angle));
    }

    /** @param kind null keeps each seed's own kind; non-null forces every clone to it. */
    private static int fill(CommandContext<CommandSourceStack> context, int count, CamoKind kind) {
        AbstractVehicle vehicle = target(context);
        if (vehicle == null) {
            return 0;
        }
        CamoLayer layer = vehicle.getCamoLayer();
        List<CamoPiece> seeds = layer.pieces();
        if (seeds.isEmpty()) {
            context.getSource().sendFailure(Component.literal(
                    "nothing to clone — paint a few pieces with a camouflage kit first"));
            return 0;
        }
        int wanted = count - seeds.size();
        if (wanted <= 0) {
            context.getSource().sendSuccess(() -> Component.literal(
                    "already at " + seeds.size() + " pieces"), false);
            return Command.SINGLE_SUCCESS;
        }
        // action.bones/palette must mirror layer's indices: clone keeps same bone/material index.
        CamoAction action = new CamoAction();
        action.action = CamoAction.Action.PAINT;
        action.vehicleId = vehicle.getId();
        action.bones = new ArrayList<>();
        for (int i = 0; i < layer.boneCount(); i++) {
            action.bones.add(layer.bone(i));
        }
        action.palette = new ArrayList<>();
        for (int i = 0; i < layer.paletteCount(); i++) {
            action.palette.add(BlockStateParser.serialize(layer.material(i)));
        }
        RandomSource random = vehicle.getRandom();
        List<CamoPiece> clones = new ArrayList<>(wanted);
        for (int i = 0; i < wanted; i++) {
            CamoPiece seed = seeds.get(random.nextInt(seeds.size()));
            clones.add(new CamoPiece(seed.bone(), seed.material(), kind == null ? seed.kind() : kind,
                    seed.x() + Mth.randomBetween(random, -SPREAD, SPREAD),
                    seed.y() + Mth.randomBetween(random, -SPREAD, SPREAD),
                    seed.z() + Mth.randomBetween(random, -SPREAD, SPREAD),
                    seed.scale(),
                    seed.pitch() + Mth.randomBetween(random, -20f, 20f),
                    seed.yaw() + Mth.randomBetween(random, -20f, 20f),
                    Mth.randomBetween(random, -180f, 180f)));
        }
        action.pieces = clones;
        int painted = layer.apply(context.getSource().registryAccess(), action, AllConfigs.Cached.camoMaxPieces);
        int total = layer.size();
        context.getSource().sendSuccess(() -> Component.literal(
                "added " + painted + " pieces, now " + total), false);
        return Command.SINGLE_SUCCESS;
    }

    private static AbstractVehicle target(CommandContext<CommandSourceStack> context) {
        Entity executor = context.getSource().getEntity();
        if (executor == null) {
            context.getSource().sendFailure(Component.literal("needs an entity to search from"));
            return null;
        }
        if (executor.getVehicle() instanceof AbstractVehicle ridden) {
            return ridden;
        }
        AABB box = executor.getBoundingBox().inflate(SEARCH_RADIUS);
        AbstractVehicle nearest = null;
        double bestDistance = Double.MAX_VALUE;
        for (AbstractVehicle vehicle : executor.level().getEntitiesOfClass(AbstractVehicle.class, box)) {
            double distance = vehicle.distanceToSqr(executor);
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = vehicle;
            }
        }
        if (nearest == null) {
            context.getSource().sendFailure(Component.literal("no vehicle within " + (int) SEARCH_RADIUS + " blocks"));
        }
        return nearest;
    }
}
