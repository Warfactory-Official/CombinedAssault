package com.wf.combinedassault.vehicle.part.gametest;

import com.mojang.authlib.GameProfile;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.fpv.gametest.KamikazeKit;
import com.wf.combinedassault.fpv.payload.PayloadUnit;
import com.wf.combinedassault.gametest.TestLanes;
import com.wf.combinedassault.item.VehicleSpawnItem;
import com.wf.combinedassault.vehicle.loadout.Loadouts;
import com.wf.combinedassault.vehicle.part.AccessActions;
import com.wf.wflib.ammo.AmmoItems;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** `pickup` vehicles: probe pick up -> spawn item with save state -> placed back with it; others refused. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class PickupGameTest {

    private static final String TEMPLATE = "empty";

    private static ServerPlayer player(GameTestHelper helper, String name, Vec3 at) {
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.moveTo(at.x + 1.5, at.y, at.z);
        return player;
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aPickedUpMavicComesBackAsItWas(GameTestHelper helper) {
        AbstractVehicle drone = TestLanes.spawn(helper, CombinedAssault.modLocation("mavic"),
                helper.absoluteVec(new Vec3(1.5, 2, 1.5)));
        helper.runAfterDelay(2, () -> {
            ServerPlayer player = player(helper, "ca_pickup", drone.position());
            for (int i = 0; i < drone.loadouts().size(); i++) {
                if (drone.loadouts().get(i).id.equals("kamikaze")) {
                    Loadouts.select(drone, i, null);
                }
            }
            player.getInventory().add(AmmoItems.stack(KamikazeKit.PG7V, 1));
            Loadouts.install(player, drone);
            drone.setEnergy(drone.energyInfo.capacityUnits() * 0.4f);
            float energy = drone.getEnergy();
            ResourceLocation id = drone.getVehicleId();
            boolean taken = AccessActions.pickUp(player, drone);
            ItemStack item = player.getInventory().items.stream()
                    .filter(s -> s.getItem() instanceof VehicleSpawnItem).findFirst().orElse(ItemStack.EMPTY);
            boolean state = item.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag()
                    .contains(VehicleSpawnItem.TAG_STATE);
            Vec3 at = helper.absoluteVec(new Vec3(3.5, 2, 3.5));
            boolean placed = VehicleSpawnItem.place(helper.getLevel(),
                    CommonAssetsManager.vehicleDataManager().getVehicleData(id).orElseThrow(), at, 0, item);
            helper.runAfterDelay(2, () -> {
                List<AbstractVehicle> back = helper.getLevel().getEntitiesOfClass(AbstractVehicle.class,
                        new AABB(at, at).inflate(1));
                AbstractVehicle again = back.isEmpty() ? null : back.get(0);
                PayloadUnit hardpoint = again == null ? null : again.getPartUnits().stream()
                        .filter(PayloadUnit.class::isInstance).map(PayloadUnit.class::cast).findFirst().orElse(null);
                if (again != null) {
                    again.discard();
                }
                helper.assertTrue(taken && drone.isRemoved(), "taken " + taken + ", removed " + drone.isRemoved());
                helper.assertTrue(state && placed && item.isEmpty(), "state " + state + ", placed " + placed
                        + ", item left " + item);
                helper.assertTrue(again != null && Math.abs(again.getEnergy() - energy) < 1e-4,
                        "energy " + (again == null ? "no vehicle" : again.getEnergy()) + ", was " + energy);
                helper.assertTrue(hardpoint != null && hardpoint.contents().size() == 1,
                        "strapped " + (hardpoint == null ? "no hardpoint" : hardpoint.contents()));
                helper.succeed();
            });
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void onlyPickupVehiclesAndOnlyEmpty(GameTestHelper helper) {
        AbstractVehicle humvee = TestLanes.spawn(helper, CombinedAssault.modLocation("humvee"),
                helper.absoluteVec(new Vec3(2.5, 2, 2.5)));
        AbstractVehicle octo = TestLanes.spawn(helper, CombinedAssault.modLocation("amazog"),
                helper.absoluteVec(new Vec3(6.5, 2, 2.5)));
        helper.runAfterDelay(2, () -> {
            ServerPlayer player = player(helper, "ca_pickup_no", octo.position());
            ArmorStand rider = new ArmorStand(helper.getLevel(), octo.getX(), octo.getY(), octo.getZ());
            helper.getLevel().addFreshEntity(rider);
            boolean humveeTaken = AccessActions.pickUp(player, humvee);
            boolean boarded = rider.startRiding(octo, true);
            boolean riddenTaken = AccessActions.pickUp(player, octo);
            rider.stopRiding();
            boolean octoTaken = AccessActions.pickUp(player, octo);
            humvee.discard();
            octo.discard();
            rider.discard();
            player.getInventory().clearContent();
            helper.assertTrue(!humveeTaken, "humvee taken");
            helper.assertTrue(boarded && !riddenTaken, "boarded " + boarded + ", ridden amazog taken " + riddenTaken);
            helper.assertTrue(octoTaken, "empty amazog refused");
            helper.succeed();
        });
    }
}
