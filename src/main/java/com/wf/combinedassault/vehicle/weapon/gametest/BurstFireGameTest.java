package com.wf.combinedassault.vehicle.weapon.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.weapon.VehicleCannon;
import com.wf.wflib.round.Rounds;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Mk 15 M61A1, {@code shoot_interval} 13 ms: shots arrive once a tick, rounds do not. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class BurstFireGameTest {

    private static final ResourceLocation MK15 = CombinedAssault.modLocation("mk15");
    private static final int AMMO = 1000;

    /** 13 shots x 50 ms / 13 ms = 50 rounds; one round a shot = 13. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void theM61FiresItsRateNotTheTickRate(GameTestHelper helper) {
        VehicleCannon gun = ciws(helper);
        if (gun == null) {
            return;
        }
        WeaponUnit unit = gun.getWeaponUnit();
        for (int shot = 0; shot < 13; shot++) {
            gun.endCooldown();
            if (!gun.shoot(new ArrayList<>(List.of(unit.aimContext())), null)) {
                unit.getVehicle().discard();
                helper.fail("shot " + shot + " refused, " + gun.getRemainAmmo() + " left");
                return;
            }
        }
        int fired = AMMO - gun.getRemainAmmo();
        int launched = clear(helper.getLevel(), unit.aimContext().from);
        unit.getVehicle().discard();
        helper.assertTrue(fired == 50 && launched == 50, "13 shots: " + fired + " rounds off the belt, " + launched
                + " launched; want 50");
        helper.succeed();
    }

    /** One shot = 3 rounds, launched 0, 1/3, 2/3 into the tick: one third of a tick's flight apart. */
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aBurstStringsOutAlongTheLine(GameTestHelper helper) {
        VehicleCannon gun = ciws(helper);
        if (gun == null) {
            return;
        }
        WeaponUnit unit = gun.getWeaponUnit();
        Vec3 muzzle = unit.aimContext().from;
        gun.shoot(new ArrayList<>(List.of(unit.aimContext())), null);
        unit.getVehicle().discard();
        long[] rounds = Rounds.within(helper.getLevel(), new AABB(muzzle, muzzle).inflate(8));
        if (rounds.length != 3) {
            clear(helper.getLevel(), muzzle);
            helper.fail(rounds.length + " rounds after one shot; want 3");
            return;
        }
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            ServerLevel level = helper.getLevel();
            double[] along = new double[3];
            double speed = 0;
            for (int k = 0; k < 3; k++) {
                Vec3 at = Rounds.position(level, rounds[k]);
                helper.assertTrue(at != null, "round " + k + " ended within 2 ticks");
                along[k] = at.distanceTo(muzzle);
                speed = Math.max(speed, Rounds.velocity(level, rounds[k]).length());
            }
            clear(level, muzzle);
            Arrays.sort(along);
            double gap = speed / 3;
            for (int k = 1; k < 3; k++) {
                double d = along[k] - along[k - 1];
                helper.assertTrue(Math.abs(d - gap) < 0.15 * gap, "gaps " + Arrays.toString(along) + "; want "
                        + gap + " (speed " + speed + " b/t / 3)");
            }
        }).thenSucceed();
    }

    @Nullable
    private static VehicleCannon ciws(GameTestHelper helper) {
        BaseVehicleData data = CommonAssetsManager.vehicleDataManager().getVehicleData(MK15).orElse(null);
        if (data == null) {
            helper.fail(MK15 + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle vehicle = data.construct(helper.getLevel(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5),
                0, 0);
        vehicle.setDisplayId(MK15);
        if (!helper.getLevel().addFreshEntity(vehicle)
                || !(vehicle.getPartUnit("ciws").orElse(null) instanceof WeaponUnit unit)
                || !(unit.getCurrentWeapon().orElse(null) instanceof VehicleCannon gun)) {
            vehicle.discard();
            helper.fail(MK15 + ": no ciws cannon");
            return null;
        }
        // Up: clear of the arena and its neighbours.
        unit.setXRot(-60);
        unit.updateRot();
        gun.setRemainAmmo(AMMO);
        gun.setLoadedRound(gun.solutionRound());
        return gun;
    }

    /** Destroys the rounds within 256 blocks of {@code muzzle}; @return how many. */
    private static int clear(ServerLevel level, Vec3 muzzle) {
        long[] rounds = Rounds.within(level, new AABB(muzzle, muzzle).inflate(256));
        for (long key : rounds) {
            Rounds.destroy(level, key);
        }
        return rounds.length;
    }
}
