package com.wf.combinedassault.vehicle.hit.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.hit.HitModel;
import com.wf.combinedassault.vehicle.hit.SurfaceMaterial;
import com.wf.combinedassault.vehicle.hit.VehicleHits;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** M142 tyres: `hit_surface` prisms (struck node, not the drawn tyre) with a `rubber` material; the hull stays steel. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class HitMaterialGameTest {

    private static final String TEMPLATE = "empty";

    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    public static void aShotOnTheTyreHitsRubber(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 at = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(1, 4, 1)));
        AbstractVehicle truck = CommonAssetsManager.vehicleDataManager()
                .getVehicleData(CombinedAssault.modLocation("m142")).orElseThrow().construct(level, at, 0, 0);
        truck.setDisplayId(CombinedAssault.modLocation("m142"));
        truck.setNoGravity(true);
        level.addFreshEntity(truck);
        helper.runAfterDelay(3, () -> {
            VehicleHits hits = truck.hits();
            int tyre = -1;
            for (int i = 0; i < hits.model().nodes().size(); i++) {
                HitModel.Node node = hits.model().nodes().get(i);
                if (node.mesh().name().equals("hit_wheel_0_l")) {
                    tyre = i;
                }
            }
            if (tyre < 0) {
                truck.discard();
                helper.fail("no hit_wheel_0_l node");
                return;
            }
            Vec3 wheel = hits.centre(tyre);
            Vec3 out = wheel.subtract(truck.getBoundingBox().getCenter()).multiply(1, 0, 0).normalize();
            VehicleHits.Hit side = hits.surface(wheel.add(out.scale(4)), wheel);
            Vec3 top = truck.getBoundingBox().getCenter();
            VehicleHits.Hit roof = hits.surface(top.add(0, 6, 0), top.add(0, -6, 0));
            truck.discard();
            String got = (side == null ? "miss" : hits.model().nodes().get(side.node()).mesh().name() + " " + side.material())
                    + " / " + (roof == null ? "miss" : roof.material());
            if (side == null || side.node() != tyre || side.material() != SurfaceMaterial.RUBBER || roof == null
                    || roof.material() != SurfaceMaterial.STEEL) {
                helper.fail("tyre / roof: " + got);
                return;
            }
            helper.succeed();
        });
    }
}
