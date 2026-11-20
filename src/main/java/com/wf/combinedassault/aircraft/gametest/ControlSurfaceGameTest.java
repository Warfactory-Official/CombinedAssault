package com.wf.combinedassault.aircraft.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.aircraft.FixedWingVehicle;
import com.wf.combinedassault.aircraft.part.ControlSurfaceUnit;
import com.wf.combinedassault.aircraft.part.data.ControlSurfaceUnitData;
import com.wf.combinedassault.aircraft.part.data.ControlSurfaceUnitPojo;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.pack.serialize.GsonUtil;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Optional;

/** Elevator pitch 20, aileron roll 15, rudder yaw 25 on a driverless MQ9: clip progress 0.5 +- xRot / 2 full. */
@PrefixGameTestTemplate(false)
public class ControlSurfaceGameTest {

    private static final ResourceLocation MQ9 = CombinedAssault.modLocation("mq9");

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = "empty", timeoutTicks = 60)
    public static void clipProgressFollowsDeflectionAndDriverlessReturnsToNeutral(GameTestHelper helper) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(MQ9);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        if (data.isEmpty() || !(data.get().construct(helper.getLevel(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0)
                instanceof FixedWingVehicle mq9)) {
            helper.fail(MQ9 + " is not a fixed-wing vehicle in the roster");
            return;
        }
        ControlSurfaceUnitData surface = new ControlSurfaceUnitData(GsonUtil.GSON.fromJson(
                "{\"id\":\"elevon\",\"pitch\":20,\"roll\":15,\"yaw\":-5}", ControlSurfaceUnitPojo.class));
        ControlSurfaceUnit unit = new ControlSurfaceUnit(0, mq9, surface);
        float full = surface.full();
        unit.setXRot(-full);
        float low = unit.clipProgress(1);
        unit.setXRot(0);
        float mid = unit.clipProgress(1);
        unit.setXRot(full);
        float high = unit.clipProgress(1);
        unit.setXRot(20);
        unit.tick();
        float after = unit.getXRot();
        mq9.discard();
        if (full != 40 || low != 0 || mid != 0.5f || high != 1) {
            helper.fail("full " + full + ", progress at -full/0/+full " + low + "/" + mid + "/" + high);
            return;
        }
        if (!(after < 20 && unit.getXAimRot() == 0)) {
            helper.fail("driverless: aim " + unit.getXAimRot() + ", xRot 20 -> " + after + " (neutral expected)");
            return;
        }
        helper.succeed();
    }
}
