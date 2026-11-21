package com.wf.combinedassault.dynamics.dig.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.weapon.VehicleTool;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.wflib.testkit.api.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

/** The Tunnel Bore's {@code drill}: bores a stone wall at drive speed, refuses what its tier cannot mine. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class DrillGameTest {

    private static final String TEMPLATE = "empty";
    private static final int LANE_Z = 2500;
    private static final int LANE_PITCH = 100;
    private static final int SETTLE = 40;
    private static final int RUN = 600;
    private static final int WALL_AT = 8;
    private static final int WALL_DEPTH = 3;

    @GameTest(template = TEMPLATE, timeoutTicks = SETTLE + RUN + 100)
    public static void aDrillBoresThroughAStoneWall(GameTestHelper helper) {
        run(helper, 0, Blocks.STONE, true);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = SETTLE + RUN + 100)
    public static void aDrillCannotMineObsidianAtIronTier(GameTestHelper helper) {
        run(helper, 1, Blocks.OBSIDIAN, false);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = SETTLE + RUN + 100)
    public static void aDrillCannotMineBedrock(GameTestHelper helper) {
        run(helper, 2, Blocks.BEDROCK, false);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = SETTLE + 60)
    public static void aDrillWorksOnlyWithTheEngineOn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, "tunnelbore");
        AbstractVehicle bore = CommonAssetsManager.vehicleDataManager().getVehicleData(id).orElseThrow()
                .construct(level, new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        bore.setDisplayId(id);
        level.addFreshEntity(bore);
        ArmorStand driver = new ArmorStand(level, bore.getX(), bore.getY(), bore.getZ());
        level.addFreshEntity(driver);
        WeaponUnit unit = (WeaponUnit) bore.getPartUnit("drill").orElseThrow();
        VehicleTool tool = (VehicleTool) unit.getCurrentWeapon().orElseThrow();
        helper.startSequence()
                .thenExecute(() -> {
                    bore.setEnergy(bore.energyInfo.energyCapacity);
                    bore.toggleEngine(false);
                    unit.shoot(0, List.of(unit.aimContext()), driver);
                    if (tool.working()) {
                        helper.fail("drill works with the engine off");
                    }
                    bore.toggleEngine(true);
                    unit.shoot(0, List.of(unit.aimContext()), driver);
                    if (!tool.working()) {
                        helper.fail("drill idle with the engine on");
                    }
                    driver.discard();
                    bore.discard();
                })
                .thenSucceed();
    }

    private static void run(GameTestHelper helper, int lane, Block wall, boolean bores) {
        ServerLevel level = helper.getLevel();
        BlockPos start = helper.absolutePos(BlockPos.ZERO).offset(0, 0, LANE_Z + lane * LANE_PITCH);
        for (int cx = (start.getX() - 12) >> 4; cx <= (start.getX() + 12) >> 4; cx++) {
            for (int cz = (start.getZ() - 8) >> 4; cz <= (start.getZ() + 30) >> 4; cz++) {
                ForcedChunks.set(level, cx, cz, true);
            }
        }
        build(level, start, wall, true);
        AbstractVehicle[] bore = new AbstractVehicle[1];
        ArmorStand[] driver = new ArmorStand[1];
        helper.startSequence()
                .thenExecute(() -> {
                    ResourceLocation id = ResourceLocation.fromNamespaceAndPath(CombinedAssault.MOD_ID, "tunnelbore");
                    bore[0] = CommonAssetsManager.vehicleDataManager().getVehicleData(id).orElseThrow()
                            .construct(level, new Vec3(start.getX() + 0.5, start.getY() + 1.5, start.getZ() + 0.5), 0, 0);
                    bore[0].setDisplayId(id);
                    level.addFreshEntity(bore[0]);
                    driver[0] = new ArmorStand(level, bore[0].getX(), bore[0].getY(), bore[0].getZ());
                    driver[0].setNoGravity(true);
                    level.addFreshEntity(driver[0]);
                })
                .thenIdle(SETTLE)
                .thenExecute(() -> {
                    bore[0].setEnergy(bore[0].energyInfo.energyCapacity);
                    bore[0].controlUnit.setOperator(driver[0]);
                    bore[0].toggleEngine(true);
                    bore[0].controlUnit.forward = true;
                })
                .thenExecuteFor(RUN, () -> {
                    WeaponUnit unit = (WeaponUnit) bore[0].getPartUnit("drill").orElseThrow();
                    if (!(unit.getCurrentWeapon().orElseThrow() instanceof VehicleTool)) {
                        helper.fail("drill part carries no tool weapon");
                        return;
                    }
                    unit.shoot(0, List.of(unit.aimContext()), driver[0]);
                })
                .thenExecute(() -> {
                    double z = bore[0].getZ();
                    BlockPos centre = start.offset(0, 1, WALL_AT + 1);
                    BlockPos beyond = start.offset(0, 1, WALL_AT + WALL_DEPTH + 2);
                    BlockPos side = start.offset(10, 1, WALL_AT + 1);
                    String report = "bore z " + (z - start.getZ()) + ", centre " + level.getBlockState(centre).getBlock()
                            + ", side " + level.getBlockState(side).getBlock();
                    CombinedAssault.LOGGER.info("[drill] {} {}", wall, report);
                    driver[0].discard();
                    bore[0].discard();
                    boolean centreGone = level.getBlockState(centre).isAir();
                    boolean sideKept = level.getBlockState(side).is(wall);
                    boolean through = z > start.getZ() + WALL_AT + WALL_DEPTH;
                    build(level, start, wall, false);
                    if (bores && (!centreGone || !through || !sideKept)) {
                        helper.fail("wall not bored or neighbours eaten: " + report);
                    } else if (!bores && (centreGone || through)) {
                        helper.fail("drill got through " + wall + ": " + report);
                    }
                })
                .thenSucceed();
    }

    /** Floor, plus a wall 21 wide and 6 high spanning the lane. */
    private static void build(ServerLevel level, BlockPos start, Block wall, boolean place) {
        for (int x = -10; x <= 10; x++) {
            for (int z = -6; z <= 30; z++) {
                level.setBlockAndUpdate(start.offset(x, 0, z), (place ? Blocks.STONE : Blocks.AIR).defaultBlockState());
                for (int y = 1; y <= 6; y++) {
                    boolean inWall = z >= WALL_AT && z < WALL_AT + WALL_DEPTH;
                    level.setBlockAndUpdate(start.offset(x, y, z),
                            (place && inWall ? wall : Blocks.AIR).defaultBlockState());
                }
            }
        }
    }
}
