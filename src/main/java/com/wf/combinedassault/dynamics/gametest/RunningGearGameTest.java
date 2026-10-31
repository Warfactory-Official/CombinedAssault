package com.wf.combinedassault.dynamics.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3f;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.component.ComponentKind;
import com.wf.combinedassault.ballistics.component.VehicleSystems;
import com.wf.combinedassault.vehicle.entity.VehiclePart;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.gametest.TestLanes;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.dynamics.part.SuspensionUnit;
import com.wf.combinedassault.dynamics.part.TrackUnit;

import java.util.List;
import java.util.Locale;

/** User: destroyed wheels stayed on; tracks had no suspension. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class RunningGearGameTest {

    private static final String TEMPLATE = "empty";

    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aDestroyedWheelComesOffAndARepairRefitsIt(GameTestHelper helper) {
        Vec3[] before = new Vec3[1];
        TestLanes.run(helper, -5400, 8, 8, CombinedAssault.modLocation("humvee"), (seq, vehicle) -> seq
                .thenExecute(() -> {
                    before[0] = vehicle.get().position();
                    wheel(vehicle.get()).setHealth(0);
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    AbstractVehicle v = vehicle.get();
                    PartUnit<?> wheel = wheel(v);
                    List<VehiclePart> debris = debris(v);
                    String state = String.format(Locale.ROOT, "moved %.2f, pitch %.1f roll %.1f, debris %d",
                            v.position().distanceTo(before[0]), Mth.wrapDegrees(v.getXRot()), Mth.wrapDegrees(v.getZRot()),
                            debris.size());
                    helper.assertTrue(wheel.isDetached(), "destroyed wheel still on: " + state);
                    helper.assertTrue(debris.size() == 1 && debris.get(0).getPartUnit() != null
                            && debris.get(0).getPartUnit().getId().equals(wheel.getId()), "no wheel debris: " + state);
                    helper.assertTrue(v.position().distanceTo(before[0]) < 1.5, "pop threw the truck: " + state);
                    float up = debris.get(0).rotYXZ().transform(new Vector3f(0, 1, 0)).y;
                    helper.assertTrue(up < 0.5, "wheel debris still upright, up.y " + up + ": " + state);
                    helper.assertTrue(debris.get(0).interact(helper.makeMockPlayer(GameType.SURVIVAL), InteractionHand.MAIN_HAND)
                            == InteractionResult.PASS, "wheel debris answers a click");
                    VehicleSystems.Cell cell = VehicleSystems.cells(v).stream()
                            .filter(c -> c.kind() == ComponentKind.WHEEL && c.ratio() == 0).findFirst().orElseThrow();
                    cell.step();
                    helper.assertTrue(!wheel.isDetached() && wheel.getHealth() > 0, "repair left the wheel off: " + state);
                }), at -> debris(helper, at).forEach(VehiclePart::discard));
    }

    /** One probe per road wheel, at its x/z; a 0.25 step under the front wheel: that wheel takes it, the rest stay down. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void eachRoadWheelRidesItsOwnSpring(GameTestHelper helper) {
        BlockPos[] step = new BlockPos[1];
        TestLanes.run(helper, -5600, 8, 10, CombinedAssault.modLocation("m1a2"), (seq, vehicle) -> seq
                .thenExecute(() -> {
                    TrackUnit left = left(vehicle.get());
                    helper.assertTrue(left.contactCount() == left.getData().getRoadWheels().size(),
                            "left track rows " + left.contactCount() + " != road wheels "
                                    + left.getData().getRoadWheels().size());
                    int front = 0;
                    for (int row = 1; row < left.contactCount(); row++) {
                        if (left.contactZ(row) > left.contactZ(front)) {
                            front = row;
                        }
                    }
                    step[0] = BlockPos.containing(left.contactX(front), left.contactY(front) + 0.5, left.contactZ(front));
                    helper.getLevel().setBlockAndUpdate(step[0],
                            Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, 3));
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    TrackUnit left = left(vehicle.get());
                    double on = Double.NaN, max = Double.NEGATIVE_INFINITY;
                    StringBuilder rows = new StringBuilder();
                    for (int row = 0; row < left.contactCount(); row++) {
                        double c = left.contactCompression(row);
                        rows.append(String.format(Locale.ROOT, " z%.2f c%.3f[%.3f..%.3f]", left.contactZ(row), c,
                                left.contactMin(row), left.contactMax(row)));
                        if (BlockPos.containing(left.contactX(row), left.contactY(row) - 0.01, left.contactZ(row)).equals(step[0])) {
                            on = c;
                        } else {
                            max = Math.max(max, c);
                        }
                    }
                    helper.assertTrue(left.contactCount() == left.getData().getRoadWheels().size(),
                            "step lifted wheels off the ground:" + rows);
                    helper.assertTrue(on > max + 0.15, "stepped wheel not compressed past the rest:" + rows);
                }), at -> {
            if (step[0] != null) {
                helper.getLevel().setBlockAndUpdate(step[0], Blocks.AIR.defaultBlockState());
            }
        });
    }

    private static PartUnit<?> wheel(AbstractVehicle vehicle) {
        return vehicle.getPartUnit("wheel_fl").orElseThrow();
    }

    private static TrackUnit left(AbstractVehicle vehicle) {
        for (SuspensionUnit<?> unit : vehicle.getSuspensionUnits()) {
            if (unit instanceof TrackUnit track && track.getId().equals("suspension_left")) {
                return track;
            }
        }
        throw new IllegalStateException("m1a2 has no suspension_left");
    }

    private static List<VehiclePart> debris(AbstractVehicle vehicle) {
        return vehicle.level().getEntitiesOfClass(VehiclePart.class, vehicle.getBoundingBox().inflate(12));
    }

    private static List<VehiclePart> debris(GameTestHelper helper, BlockPos at) {
        return helper.getLevel().getEntitiesOfClass(VehiclePart.class, new AABB(at).inflate(24));
    }
}
