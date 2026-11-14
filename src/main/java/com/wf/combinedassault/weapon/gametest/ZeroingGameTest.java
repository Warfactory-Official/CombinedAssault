package com.wf.combinedassault.weapon.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.indirect.IndirectFire;
import com.wf.combinedassault.indirect.RoundSpec;
import com.wf.combinedassault.indirect.Trajectory;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class ZeroingGameTest {

    private static final String TEMPLATE = "empty";

    /**
     * Each M1A2 gun stepped up to its last zero: level shot at that elevation lands at the zero, bore within the
     * pitch stops, aim moved by the elevation (sight held), next step refused.
     */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void eachGunZeroesToItsRoundWithinItsStops(GameTestHelper helper) {
        BaseVehicleData data = CommonAssetsManager.vehicleDataManager()
                .getVehicleData(CombinedAssault.modLocation("m1a2")).orElse(null);
        if (data == null) {
            helper.fail("m1a2 missing");
            return;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle tank = data.construct(helper.getLevel(), Vec3.atBottomCenterOf(at), 0, 0);
        tank.setDisplayId(CombinedAssault.modLocation("m1a2"));
        helper.getLevel().addFreshEntity(tank);
        helper.startSequence().thenIdle(3).thenExecute(() -> {
            StringBuilder log = new StringBuilder();
            for (String id : List.of("turret", "anti_aircraft_machine_gun", "loader_machine_gun")) {
                WeaponUnit unit = (WeaponUnit) tank.getPartUnit(id).orElseThrow();
                RoundSpec spec = IndirectFire.specOf(unit);
                if (spec == null) {
                    log.append(' ').append(id).append(": no round;");
                    continue;
                }
                float aim = unit.getXAimRot();
                int steps = 0;
                while (steps < 2000 && unit.stepZero(1)) {
                    steps++;
                }
                unit.tickZero();
                int range = unit.zeroRange();
                double e = unit.zeroElevation();
                Trajectory shot = Trajectory.of(spec, e);
                double landed = shot.heightAt(shot.ticksToHorizontal(range));
                float stop = -(unit.xRotMin - unit.xSelfRot + unit.xBarrelSelfRot);
                if (steps == 0 || steps == 2000 || Math.abs(landed) > 0.05 || e <= 0 || e > stop + 1e-4
                        || Math.abs(aim - e - unit.getXAimRot()) > 1e-3 || unit.stepZero(1) || unit.zeroRange() != range) {
                    log.append(String.format(" %s: zero %d, e %.3f (stop %.2f), landed %.3f, aim %.3f -> %.3f;",
                            id, range, e, stop, landed, aim, unit.getXAimRot()));
                }
                while (unit.stepZero(-1)) {
                    steps--;
                }
                unit.tickZero();
                if (steps != 0 || unit.zeroElevation() != 0 || Math.abs(unit.getXAimRot() - aim) > 1e-3) {
                    log.append(String.format(" %s: back to boresight left e %.3f aim %.3f;", id, unit.zeroElevation(),
                            unit.getXAimRot()));
                }
            }
            tank.discard();
            if (!log.isEmpty()) {
                helper.fail(log.toString().trim());
                return;
            }
            helper.succeed();
        });
    }
}
