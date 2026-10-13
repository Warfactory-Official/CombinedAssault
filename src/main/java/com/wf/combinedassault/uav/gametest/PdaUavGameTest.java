package com.wf.combinedassault.uav.gametest;

import com.mojang.authlib.GameProfile;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.indirect.CallForFire;
import com.wf.combinedassault.uav.UavTablet;
import com.wf.wflib.drone.cam.CameraChannels;
import com.wf.wflib.drone.cam.CameraNet;
import com.wf.wflib.drone.cam.FeedTarget;
import com.wf.wflib.item.ModDataComponents;
import com.wf.wflib.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/** wflib PDA as UAV controller: links + feed rights; call for fire refused without uplink. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class PdaUavGameTest {
    private static final String TEMPLATE = "empty";
    private static final ResourceLocation DRONE = CombinedAssault.modLocation("fpv_drone");

    /** Vehicle data (incl. {@code uav}) applies after spawn => checked a few ticks later. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void pdaLinksAUavAndEntitlesItsFeed(GameTestHelper helper) {
        AbstractVehicle drone = spawnDrone(helper);
        if (drone == null) {
            return;
        }
        FakePlayer operator = operator(helper, "uav_linker");
        helper.runAfterDelay(5, () -> {
            ItemStack pda = operator.getMainHandItem();
            InteractionResult result = UavTablet.interact(pda, operator, drone);
            if (!result.consumesAction()) {
                helper.fail("PDA on an owned-by-nobody UAV must link it: " + result + " uav=" + drone.uav
                        + " mayCommand=" + drone.mayCommand(operator));
                return;
            }
            if (!UavTablet.channels(pda).has(FeedTarget.ofDrone(drone.getUUID()))) {
                helper.fail("the UAV is not in the PDA's UAV links: " + UavTablet.channels(pda).targets());
                return;
            }
            if (!pda.getOrDefault(ModDataComponents.CHANNELS.get(), CameraChannels.EMPTY).isEmpty()) {
                helper.fail("UAV links must not land in the camera channel list");
                return;
            }
            CameraNet.subscribe(operator, drone.getId());
            if (!CameraNet.subscribers(helper.getLevel(), drone.getId()).contains(operator)) {
                helper.fail("a linked UAV's feed must be watchable from the held PDA");
                return;
            }
            CameraNet.unsubscribe(operator);
            drone.discard();
            operator.discard();
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void unlinkedPdaGetsNoUavFeed(GameTestHelper helper) {
        AbstractVehicle drone = spawnDrone(helper);
        if (drone == null) {
            return;
        }
        FakePlayer stranger = operator(helper, "uav_stranger");
        CameraNet.subscribe(stranger, drone.getId());
        if (CameraNet.subscribers(helper.getLevel(), drone.getId()).contains(stranger)) {
            helper.fail("a PDA without the link was given the UAV's feed");
            return;
        }
        drone.discard();
        stranger.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void callForFireWithoutUplinkIsRefused(GameTestHelper helper) {
        FakePlayer observer = operator(helper, "cff_observer");
        BlockPos at = helper.absolutePos(new BlockPos(8, 1, 8));
        if (CallForFire.request(observer, Vec3.atCenterOf(at)) != -1) {
            helper.fail("no net node in range: the request must be refused");
            return;
        }
        observer.discard();
        helper.succeed();
    }

    private static FakePlayer operator(GameTestHelper helper, String name) {
        FakePlayer player = new FakePlayer(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        player.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0f, 0f);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.PDA.get()));
        return player;
    }

    @Nullable
    private static AbstractVehicle spawnDrone(GameTestHelper helper) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(DRONE);
        if (data.isEmpty()) {
            helper.fail("the fpv drone is not in the vehicle roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        AbstractVehicle drone = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        drone.setDisplayId(DRONE);
        if (!helper.getLevel().addFreshEntity(drone)) {
            helper.fail("the drone could not be added to the world");
            return null;
        }
        return drone;
    }
}
