package com.wf.combinedassault.vehicle.crew.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.all.AllConfigs;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Crew split: crew.required + a {@code sensor} seat on the station => that seat aims, the station seat only fires.
 * Rule-level: mock players cannot board (mods' payloads rejected by the mock connection; FakePlayer refused).
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class CrewSplitGameTest {

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void theSightSeatOwnsTheSpg9AimOnlyUnderCrewRules(GameTestHelper helper) {
        boolean crew = AllConfigs.server.crewRequired.get();
        AbstractVehicle spg = spawn(helper, "spg9");
        AbstractVehicle flak = spawn(helper, "flak36");
        try {
            AllConfigs.server.crewRequired.set(true);
            boolean spgSplit = gun(spg).aimedFromSensorSeat();
            boolean flakSplit = gun(flak).aimedFromSensorSeat();
            AllConfigs.server.crewRequired.set(false);
            boolean spgSolo = gun(spg).aimedFromSensorSeat();
            helper.assertTrue(spgSplit && !flakSplit && !spgSolo,
                    "split: spg9 crew " + spgSplit + ", flak36 crew " + flakSplit + ", spg9 solo " + spgSolo);
        } finally {
            AllConfigs.server.crewRequired.set(crew);
            spg.discard();
            flak.discard();
        }
        helper.succeed();
    }

    private static PartUnit<?> gun(AbstractVehicle vehicle) {
        return vehicle.getPartUnit("gun").orElseThrow();
    }

    private static AbstractVehicle spawn(GameTestHelper helper, String id) {
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 2, 1)));
        AbstractVehicle vehicle = CommonAssetsManager.vehicleDataManager().getVehicleData(CombinedAssault.modLocation(id))
                .orElseThrow().construct(helper.getLevel(), at, 0, 0);
        vehicle.setDisplayId(CombinedAssault.modLocation(id));
        vehicle.setNoGravity(true);
        helper.getLevel().addFreshEntity(vehicle);
        return vehicle;
    }
}
