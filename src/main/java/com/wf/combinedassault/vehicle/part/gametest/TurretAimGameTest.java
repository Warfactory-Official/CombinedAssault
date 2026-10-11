package com.wf.combinedassault.vehicle.part.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.WeaponUnit;

import java.util.Optional;

/** Merkava gun: trunnion 1.1 blocks ahead of the ring. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class TurretAimGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation MERKAVA = CombinedAssault.modLocation("merkava");

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void theAimDoesNotDependOnTheTurretHeading(GameTestHelper helper) {
        WeaponUnit gun = gun(helper);
        if (gun == null) {
            return;
        }
        Vec3 target = gun.getVehicle().position().add(-6, 1, 6);
        Vec2 fromAhead = aimFrom(gun, 0, target);
        Vec2 fromSide = aimFrom(gun, 90, target);
        gun.getVehicle().discard();
        if (Math.abs(Mth.wrapDegrees(fromAhead.y - fromSide.y)) > 0.01f || Math.abs(fromAhead.x - fromSide.x) > 0.01f) {
            helper.fail("aim at 8.5 blocks: turret ahead " + fromAhead.x + "/" + fromAhead.y
                    + ", turret abeam " + fromSide.x + "/" + fromSide.y);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void theSolvedGunPointsAtTheTarget(GameTestHelper helper) {
        WeaponUnit gun = gun(helper);
        if (gun == null) {
            return;
        }
        Vec3 target = gun.getVehicle().position().add(-6, 1, 6);
        Vec2 aim = aimFrom(gun, 0, target);
        gun.setYRot(aim.y - gun.yBarrelSelfRot);
        gun.setXRot(aim.x - gun.xBarrelSelfRot);
        gun.updateRot();
        Vec3 trunnion = gun.getVehicle().relativeRotPos(
                gun.getVehicle().position().add(gun.getXTurnGroup().globalTransform().offset()), false);
        Vec2 line = gun.worldVecToLocalRot(target.subtract(trunnion));
        gun.getVehicle().discard();
        if (Math.abs(Mth.wrapDegrees(line.y - aim.y)) > 0.01f || Math.abs(line.x - aim.x) > 0.01f) {
            helper.fail("gun laid " + aim.x + "/" + aim.y + ", trunnion sees target at " + line.x + "/" + line.y);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aPointOverheadHoldsTheHeading(GameTestHelper helper) {
        WeaponUnit gun = gun(helper);
        if (gun == null) {
            return;
        }
        Vec3 overhead = gun.getVehicle().position().add(0.3, 60, -0.2);
        Vec2 aim = aimFrom(gun, 45, overhead);
        gun.getVehicle().discard();
        if (Math.abs(Mth.wrapDegrees(aim.y - gun.yBarrelSelfRot - 45)) > 0.01f) {
            helper.fail("aim overhead swings the turret from 45 to " + (aim.y - gun.yBarrelSelfRot));
            return;
        }
        helper.succeed();
    }

    private static Vec2 aimFrom(WeaponUnit gun, float turretYaw, Vec3 target) {
        gun.setYRot(turretYaw);
        gun.setXRot(0);
        gun.updateRot();
        return gun.aimRot(target);
    }

    @Nullable
    private static WeaponUnit gun(GameTestHelper helper) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(MERKAVA);
        if (data.isEmpty()) {
            helper.fail(MERKAVA + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(MERKAVA);
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail("the Merkava could not be added to the world");
            return null;
        }
        if (!(vehicle.getPartUnit("turret").orElse(null) instanceof WeaponUnit unit)) {
            helper.fail("the Merkava has no turret weapon part");
            return null;
        }
        return unit;
    }
}
