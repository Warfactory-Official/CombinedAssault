package com.wf.combinedassault.indirect.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import com.wf.wflib.testkit.api.ForcedChunks;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.weapon.ordnance.Munitions;
import com.wf.wflib.ammo.api.AmmoItems;
import com.wf.wflib.round.api.KineticPreset;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import com.wf.wflib.round.api.Rounds;
import net.minecraft.server.level.ServerLevel;
import com.wf.combinedassault.indirect.BallisticSolver;
import com.wf.combinedassault.indirect.FireArc;
import com.wf.combinedassault.indirect.FireSolution;
import com.wf.combinedassault.indirect.RoundSpec;
import com.wf.combinedassault.indirect.Trajectory;
import com.wf.combinedassault.dynamics.PhysicsEngine;

/**
 * Everything else in this package assumes the closed form matches the round's actual flight. Checked
 * twice: against the recurrence written out longhand, and against a WFLib round stepped by {@code Rounds}.
 */
@PrefixGameTestTemplate(false)
public class TrajectoryGameTest {

    private static final String TEMPLATE = "empty";

    /** Blocks. */
    private static final double CLOSED_FORM_TOLERANCE = 1.0E-6;

    /**
     * Blocks. Gametest arena sits at coordinates in the millions, where entity double-rounding error
     * (accumulated tick by tick) is already a hundredth of a micron per ULP.
     */
    private static final double ENTITY_TOLERANCE = 1.0E-6;

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void closedFormMatchesRecurrence(GameTestHelper helper) {
        double[][] cases = {
                // muzzle speed, drag, elevation
                {15.0, 0.01, 45.0},
                {15.0, 0.01, 80.0},
                {25.0, 0.005, 60.0},
                {8.0, 0.0, 45.0},
                {8.0, 0.0, 89.0},
                {40.0, 0.02, 15.0},
        };
        for (double[] test : cases) {
            RoundSpec spec = new RoundSpec(test[0], test[1], PhysicsEngine.G, 20 * 60);
            Vec3 origin = new Vec3(0, 0, 0);
            double radians = Math.toRadians(test[2]);
            Vec3 velocity = new Vec3(0, Math.sin(radians), Math.cos(radians)).scale(test[0]);

            Vec3 position = origin;
            Vec3 stepped = velocity;
            for (int tick = 1; tick <= 2000; tick++) {
                // recurrence: move, decay, gravity. decay = entity's float, not the configured drag.
                position = position.add(stepped);
                stepped = stepped.scale(spec.decay()).add(0, -PhysicsEngine.G, 0);

                Vec3 predicted = Trajectory.positionAt(origin, velocity, spec, tick);
                double error = predicted.distanceTo(position);
                if (error > CLOSED_FORM_TOLERANCE) {
                    helper.fail("closed form drifted " + error + " blocks at tick " + tick
                            + " for speed " + test[0] + " drag " + test[1] + " elevation " + test[2]);
                    return;
                }
                Vec3 predictedVelocity = Trajectory.velocityAt(velocity, spec, tick);
                double velocityError = predictedVelocity.distanceTo(stepped);
                if (velocityError > CLOSED_FORM_TOLERANCE) {
                    helper.fail("closed form velocity drifted " + velocityError + " at tick " + tick);
                    return;
                }
            }
        }
        helper.succeed();
    }

    /** WFLib 81 mm (linear drag only) against the closed form. Stepped by hand: deterministic either way. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void closedFormMatchesTheRound(GameTestHelper helper) {
        ItemStack mortar = AmmoItems.stack(ResourceLocation.fromNamespaceAndPath("wflib", "81mm/he"), 1);
        KineticPreset preset = Munitions.kinetic(mortar);
        RoundSpec spec = Munitions.spec(mortar);
        ServerLevel level = helper.getLevel();

        BlockPos origin = helper.absolutePos(new BlockPos(1, 10, 1));
        Vec3 start = new Vec3(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
        // steep: stays inside the arena's column, meets nothing on the way up.
        double radians = Math.toRadians(85);
        Vec3 velocity = new Vec3(0, Math.sin(radians), Math.cos(radians)).scale(spec.muzzleSpeed());

        // Rounds.step waits at an undecoded column instead of flying: load the whole path synchronously.
        int first = Math.floorDiv(origin.getZ(), 16);
        int last = Math.floorDiv(Mth.floor(Trajectory.positionAt(start, velocity, spec, 250).z), 16) + 2;
        int column = Math.floorDiv(origin.getX(), 16);
        int from = Math.min(first, last) - 2, to = Math.max(first, last);
        for (int cz = from; cz <= to; cz++) {
            ForcedChunks.set(level, column, cz, true);
            level.getChunk(column, cz);
        }
        long round = 0;
        boolean live = false;
        try {
            round = Rounds.launch(level, preset, start, velocity, null, null);
            live = true;
            // 81 mm at 85 deg lands ~tick 340.
            for (int tick = 1; tick <= 250; tick++) {
                if (!Rounds.step(level, round)) {
                    live = false;
                    helper.fail("round ended at tick " + tick + "; it should have had clear air");
                    return;
                }
                Vec3 at = Rounds.position(level, round);
                Vec3 predicted = Trajectory.positionAt(start, velocity, spec, tick);
                double error = predicted.distanceTo(at);
                if (error > ENTITY_TOLERANCE) {
                    helper.fail("round is " + error + " blocks from the closed form at tick " + tick
                            + " (round " + at + ", predicted " + predicted + ")");
                    return;
                }
            }
        } finally {
            if (live) {
                Rounds.destroy(level, round);
            }
            for (int cz = from; cz <= to; cz++) {
                ForcedChunks.set(level, column, cz, false);
            }
        }
        helper.succeed();
    }

    /** WFLib 12.7x99 (quadratic drag) against the stepped form, then a solution laid on it lands on target. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void steppedFormMatchesACartridge(GameTestHelper helper) {
        ItemStack cartridge = AmmoItems.stack(ResourceLocation.fromNamespaceAndPath("wflib", "12.7x99/ball"), 1);
        KineticPreset preset = Munitions.kinetic(cartridge);
        RoundSpec spec = Munitions.spec(cartridge);
        if (preset == null || spec == null || spec.quadraticDrag() <= 0) {
            helper.fail("12.7x99/ball: no quadratic drag in " + spec);
            return;
        }
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 10, 1));
        Vec3 start = new Vec3(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
        double radians = Math.toRadians(85);
        Vec3 velocity = new Vec3(0, Math.sin(radians), Math.cos(radians)).scale(spec.muzzleSpeed());
        long round = Rounds.launch(level, preset, start, velocity, null, null);
        for (int tick = 1; tick <= 60; tick++) {
            if (!Rounds.step(level, round)) {
                helper.fail("round ended at tick " + tick + "; it should have had clear air");
                return;
            }
            Vec3 at = Rounds.position(level, round);
            double error = Trajectory.positionAt(start, velocity, spec, tick).distanceTo(at);
            if (error > ENTITY_TOLERANCE) {
                helper.fail("round is " + error + " blocks from the stepped form at tick " + tick);
                return;
            }
        }
        Rounds.destroy(level, round);

        double range = 0.6 * BallisticSolver.maxRange(spec);
        for (FireArc arc : FireArc.values()) {
            FireSolution solution = BallisticSolver.solve(spec, range, -20.0, arc);
            if (solution == null) {
                helper.fail(arc + " arc: no solution at " + range);
                return;
            }
            double e = Math.toRadians(solution.elevationDegrees());
            Vec3 laid = new Vec3(0, Math.sin(e), Math.cos(e)).scale(spec.muzzleSpeed());
            Vec3 previous = Vec3.ZERO;
            Vec3 landed = null;
            for (int tick = 1; tick <= spec.lifeTicks() && landed == null; tick++) {
                Vec3 now = Trajectory.positionAt(Vec3.ZERO, laid, spec, tick);
                if (now.z >= range) {
                    landed = previous.lerp(now, (range - previous.z) / (now.z - previous.z));
                }
                previous = now;
            }
            if (landed == null || Math.abs(landed.y + 20.0) > 0.05) {
                helper.fail(arc + " arc at " + solution.elevationDegrees() + " deg: at range "
                        + (landed == null ? "never" : landed.y) + ", want -20");
                return;
            }
        }
        helper.succeed();
    }

}
