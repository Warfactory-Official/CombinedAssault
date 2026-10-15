package com.wf.combinedassault.vehicle.loadout.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.fpv.gametest.KamikazeKit;
import com.wf.combinedassault.fpv.payload.FpvTrigger;
import com.wf.combinedassault.fpv.payload.PayloadUnit;
import com.wf.combinedassault.gametest.TestLanes;
import com.wf.combinedassault.vehicle.loadout.Loadouts;
import com.wf.wflib.ammo.AmmoItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class LoadoutGameTest {

    private static final String TEMPLATE = "empty";

    private static AbstractVehicle mavic(GameTestHelper helper) {
        return TestLanes.spawn(helper, CombinedAssault.modLocation("mavic"), helper.absoluteVec(new Vec3(1.5, 2, 1.5)));
    }

    private static int index(AbstractVehicle vehicle, String id) {
        for (int i = 0; i < vehicle.loadouts().size(); i++) {
            if (vehicle.loadouts().get(i).id.equals(id)) {
                return i;
            }
        }
        throw new IllegalArgumentException(id);
    }

    private static ServerPlayer player(GameTestHelper helper, AbstractVehicle vehicle) {
        ServerPlayer player = FakePlayerFactory.getMinecraft(helper.getLevel());
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        Vec3 at = vehicle.position().add(1.5, 0, 0);
        player.moveTo(at.x, at.y, at.z);
        return player;
    }

    /** Probe strap: one cartridge taken, mass on the airframe; strip: back, mass off. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void strappingAnRpgCostsItAndWeighs(GameTestHelper helper) {
        AbstractVehicle drone = mavic(helper);
        helper.runAfterDelay(2, () -> {
            Loadouts.select(drone, index(drone, "kamikaze"), null);
            ServerPlayer player = player(helper, drone);
            player.getInventory().add(AmmoItems.stack(KamikazeKit.PG7V, 2));
            boolean strapped = Loadouts.install(player, drone);
            boolean second = Loadouts.install(player, drone);
            int left = player.getInventory().countItem(AmmoItems.stack(KamikazeKit.PG7V, 1).getItem());
            helper.runAfterDelay(2, () -> {
                float mass = drone.getEntityData().get(AbstractVehicle.PART_MASS);
                boolean removed = Loadouts.remove(player, drone);
                int back = player.getInventory().countItem(AmmoItems.stack(KamikazeKit.PG7V, 1).getItem());
                player.getInventory().clearContent();
                drone.discard();
                helper.assertTrue(strapped && !second && left == 1, "strap " + strapped + ", second " + second
                        + ", left " + left);
                helper.assertTrue(Math.abs(mass - 2.6f) < 0.01f, "part mass " + mass + ", PG-7V 2.6 kg");
                helper.assertTrue(removed && back == 2, "removed " + removed + ", cartridges back " + back);
                helper.succeed();
            });
        });
    }

    /** Use on a plain Mavic: TNT refused; a rocket switches to kamikaze and is strapped, one taken. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void usingARocketOnAMavicStrapsIt(GameTestHelper helper) {
        AbstractVehicle drone = mavic(helper);
        helper.runAfterDelay(2, () -> {
            ServerPlayer player = player(helper, drone);
            boolean tnt = Loadouts.strap(player, drone, new ItemStack(Items.TNT));
            ItemStack rockets = AmmoItems.stack(KamikazeKit.PG7V, 2);
            boolean strapped = Loadouts.strap(player, drone, rockets);
            String loadout = drone.loadout().id;
            boolean loaded = PayloadUnit.on(drone).isLoaded();
            drone.discard();
            helper.assertTrue(!tnt, "TNT strapped");
            helper.assertTrue(strapped && "kamikaze".equals(loadout) && loaded && rockets.getCount() == 1,
                    "strapped " + strapped + ", loadout " + loadout + ", loaded " + loaded + ", left " + rockets.getCount());
            helper.succeed();
        });
    }

    /** Tool switch is free: what was strapped comes back to the player. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void switchingLoadoutRefundsTheHardware(GameTestHelper helper) {
        AbstractVehicle drone = mavic(helper);
        helper.runAfterDelay(2, () -> {
            KamikazeKit.strap(drone);
            ServerPlayer player = player(helper, drone);
            Loadouts.select(drone, index(drone, "bomber"), player);
            int back = player.getInventory().countItem(AmmoItems.stack(KamikazeKit.PG7V, 1).getItem());
            PayloadUnit hardpoint = PayloadUnit.on(drone);
            String type = hardpoint.payloadType() == null ? null : hardpoint.payloadType().id().getPath();
            player.getInventory().clearContent();
            drone.discard();
            helper.assertTrue(back == 1, "refunded " + back);
            helper.assertTrue("grenade_rack".equals(type) && !hardpoint.isLoaded(), "hardpoint " + type);
            helper.succeed();
        });
    }

    /** Operator trigger on a live kamikaze: drone gone, wflib warhead fired. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aLiveKamikazeFiresItsWarhead(GameTestHelper helper) {
        AbstractVehicle drone = mavic(helper);
        helper.runAfterDelay(2, () -> {
            PayloadUnit hardpoint = KamikazeKit.strap(drone);
            hardpoint.setOn(true);
            helper.runAfterDelay(hardpoint.armingTicks() + 2, () -> {
                boolean fired = hardpoint.fire(FpvTrigger.OPERATOR, null, 0);
                helper.assertTrue(fired && drone.isRemoved(), "fired " + fired + ", drone removed " + drone.isRemoved());
                helper.succeed();
            });
        });
    }

    /** STRIKE offered only with something strapped. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void strikeNeedsAPayload(GameTestHelper helper) {
        AbstractVehicle drone = mavic(helper);
        helper.runAfterDelay(2, () -> {
            boolean empty = drone.drone.supports(com.wf.wflib.drone.DroneTask.Kind.STRIKE);
            KamikazeKit.strap(drone);
            boolean armed = drone.drone.supports(com.wf.wflib.drone.DroneTask.Kind.STRIKE);
            drone.discard();
            helper.assertTrue(!empty && armed, "strike empty " + empty + ", strapped " + armed);
            helper.succeed();
        });
    }
}
