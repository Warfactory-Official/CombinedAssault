package com.wf.combinedassault.vehicle.pose.gametest;

import com.mojang.authlib.GameProfile;
import com.norwood.ahf.rig.HumanoidRig;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.pose.SeatPoses;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import org.joml.Vector3f;
import com.wf.combinedassault.vehicle.part.SwitchableUnit;
import com.norwood.ahf.geometry.Obb;

/** Server rig (AHF {@code computeServer}) of a riverine rider: seat pose set per seat, hull pitch carried. */
@PrefixGameTestTemplate(false)
public class SeatPoseGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation BOAT = CombinedAssault.modLocation("riverine");

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aRiderTakesItsSeatPoseAndTheHullPitch(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle boat = CommonAssetsManager.vehicleDataManager().getVehicleData(BOAT).orElseThrow()
                .construct(helper.getLevel(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        boat.setDisplayId(BOAT);
        helper.getLevel().addFreshEntity(boat);
        // FakePlayer#startRiding always refuses.
        Player rider = new Player(helper.getLevel(), at, 0, new GameProfile(UUID.randomUUID(), "seat_rider")) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return false;
            }
        };
        rider.moveTo(boat.position());
        try {
            if (!rider.startRiding(boat, true)) {
                helper.fail("the test player could not board");
                return;
            }
            String failure = check(boat, rider);
            if (failure != null) {
                helper.fail(failure);
                return;
            }
        } finally {
            rider.stopRiding();
            boat.discard();
        }
        helper.succeed();
    }

    private static String check(AbstractVehicle boat, Player rider) {
        if (!SeatPoses.set("drive").equals(SeatPoses.of(rider))) {
            return "helm rider pose set " + SeatPoses.of(rider) + ", expected drive";
        }
        HumanoidRig.LocalRig seated = HumanoidRig.computeServer(rider);
        // drive: thigh out front, shin below the knee.
        if (seated.rightLeg.center().z < 0.12 || seated.rightShin.center().y > seated.rightLeg.center().y - 0.05) {
            return "helm legs not seated: thigh " + seated.rightLeg.center() + ", shin " + seated.rightShin.center();
        }
        int stern = -1;
        for (int i = 0; i < boat.seats.size(); i++) {
            if (boat.seats.get(i).partUnit.getId().equals("gun_stern")) {
                stern = i;
            }
        }
        if (stern < 0 || !boat.changeSeat(rider, stern)) {
            return "no stern gun seat to move to (" + stern + ")";
        }
        if (!SeatPoses.set("stand").equals(SeatPoses.of(rider))) {
            return "stern gunner pose set " + SeatPoses.of(rider) + ", expected stand";
        }
        HumanoidRig.LocalRig standing = HumanoidRig.computeServer(rider);
        if (Math.abs(standing.rightLeg.center().z) > 0.06 || standing.rightShin.center().y > 0.3) {
            return "stern gunner legs not standing: thigh " + standing.rightLeg.center() + ", shin "
                    + standing.rightShin.center();
        }
        boat.setXRot(20.0f);
        HumanoidRig.LocalRig pitched = HumanoidRig.computeServer(rider);
        Vec3 level = standing.head.center();
        Vec3 tilted = pitched.head.center();
        double expected = level.y * Math.sin(Math.toRadians(20.0));
        if (Math.abs(Math.abs(tilted.z - level.z) - expected) > 0.05 || Math.abs(tilted.x - level.x) > 0.02) {
            return "hull pitch 20 moved the head " + level + " -> " + tilted + "; expected " + expected + " along z";
        }
        return null;
    }

    /**
     * M1A2 driver (pose {@code recline}) under the hull liner: rig OBB corners (vehicle frame) below the liner roof,
     * above the hull floor; head centre within 0.2 m of the seat eye.
     */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aReclinedTankDriverFitsUnderTheHullRoof(GameTestHelper helper) {
        ResourceLocation id = CombinedAssault.modLocation("m1a2");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle tank = CommonAssetsManager.vehicleDataManager().getVehicleData(id).orElseThrow()
                .construct(helper.getLevel(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        tank.setDisplayId(id);
        helper.getLevel().addFreshEntity(tank);
        Player rider = new Player(helper.getLevel(), at, 0, new GameProfile(UUID.randomUUID(), "driver")) {
            @Override
            public boolean isSpectator() {
                return false;
            }

            @Override
            public boolean isCreative() {
                return false;
            }
        };
        rider.moveTo(tank.position());
        helper.startSequence().thenIdle(3).thenExecute(() -> {
            try {
                int seat = tank.seats.stream().filter(s -> s.partUnit.getId().equals("seat")).findFirst().orElseThrow().seatIndex;
                SwitchableUnit<?> hatch = (SwitchableUnit<?>) tank.getPartUnit("driver_hatch").orElseThrow();
                hatch.setOn(true);
                for (int t = 0; t < 40; t++) {
                    tank.clipTracks().tick();
                }
                rider.moveTo(hatch.worldPosition(hatch.getPivotOffset()).subtract(0, 1.5, 0));
                if (!tank.board(rider, seat)) {
                    helper.fail("driver could not board");
                    return;
                }
                tank.positionRider(rider);
                rider.setYBodyRot(tank.getYRot());
                HumanoidRig.LocalRig rig = HumanoidRig.computeServer(rider);
                Vec3 eye = tank.seats.get(seat).partUnit.getSeatOffset();
                double top = -9, bottom = 9;
                StringBuilder parts = new StringBuilder();
                for (HumanoidRig.LocalRig.Slot slot : HumanoidRig.LocalRig.SLOTS) {
                    Obb o = slot.get(rig);
                    double lo = 9, hi = -9;
                    for (int k = 0; k < 8; k++) {
                        Vec3 c = o.center().add(o.axisX().scale((k & 1) == 0 ? -o.half().x : o.half().x))
                                .add(o.axisY().scale((k & 2) == 0 ? -o.half().y : o.half().y))
                                .add(o.axisZ().scale((k & 4) == 0 ? -o.half().z : o.half().z));
                        Vec3 v = local(tank, rider, c);
                        lo = Math.min(lo, v.y);
                        hi = Math.max(hi, v.y);
                    }
                    top = Math.max(top, hi);
                    bottom = Math.min(bottom, lo);
                    Vec3 cv = local(tank, rider, o.center());
                    parts.append(slot).append(String.format(" (%.2f %.2f %.2f) y %.2f..%.2f; ", cv.x, cv.y, cv.z, lo, hi));
                }
                Vec3 head = local(tank, rider, rig.head.center());
                String msg = String.format("top %.3f bottom %.3f head %s eye %s | %s", top, bottom, head, eye, parts);
                CombinedAssault.LOGGER.info("recline rig: {}", msg);
                helper.assertTrue(top < 1.30 && bottom > 0.33 && head.distanceTo(eye) < 0.2, msg);
            } finally {
                rider.stopRiding();
                tank.discard();
            }
        }).thenSucceed();
    }

    /** Rig-local point (x right, y up, z front, feet origin, body yaw) -> vehicle frame. */
    private static Vec3 local(AbstractVehicle vehicle, Player rider, Vec3 p) {
        double yaw = Math.toRadians(rider.yBodyRot);
        Vec3 w = rider.position().add(-p.x * Math.cos(yaw) - p.z * Math.sin(yaw), p.y, -p.x * Math.sin(yaw) + p.z * Math.cos(yaw));
        Vector3f v = vehicle.rotYXZ().invert().transform(w.subtract(vehicle.position()).toVector3f());
        return new Vec3(v.x, v.y, v.z);
    }
}
