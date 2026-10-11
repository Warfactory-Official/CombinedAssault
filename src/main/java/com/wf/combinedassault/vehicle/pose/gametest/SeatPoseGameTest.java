package com.wf.combinedassault.vehicle.pose.gametest;

import com.mojang.authlib.GameProfile;
import com.norwood.ahf.rig.HumanoidRig;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.pose.SeatPoses;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Server rig (AHF {@code computeServer}) of a riverine rider: seat pose set per seat, hull pitch carried. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class SeatPoseGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation BOAT = CombinedAssault.modLocation("riverine");

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
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
        if (!SeatPoses.SETS.get("drive").equals(SeatPoses.of(rider))) {
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
        if (!SeatPoses.SETS.get("stand").equals(SeatPoses.of(rider))) {
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
}
