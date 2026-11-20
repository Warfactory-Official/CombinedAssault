package com.wf.combinedassault.naval.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.component.HullComponents;
import com.wf.combinedassault.ballistics.component.VehicleMobility;
import com.wf.combinedassault.naval.ShipVehicle;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/** Riverine hit-node pools: compartments flood / list / sink, engine pool gates speed, rudder pool gates turning. */
@PrefixGameTestTemplate(false)
public class ShipDamageGameTest {

    private static final String POOL = "water_pool";
    private static final ResourceLocation BOAT = CombinedAssault.modLocation("riverine");
    private static final String[] COMPARTMENTS = {"compartment bow", "compartment left", "compartment right", "compartment stern"};

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, batch = "naval", template = POOL, timeoutTicks = 1200)
    public static void riverineDamageFloodsListsAndSinks(GameTestHelper helper) {
        ShipVehicle boat = spawn(helper, 24, 13, 10);
        if (boat == null) {
            return;
        }
        helper.startSequence()
                .thenIdle(60)
                .thenExecute(() -> {
                    if (!declared(helper, boat, COMPARTMENTS) || !declared(helper, boat, "engine", "rudder", "propeller left", "propeller right")) {
                        return;
                    }
                    set(boat, "compartment left", 0);
                })
                .thenIdle(100)
                .thenExecute(() -> {
                    if (boat.getZRot() > -1.0f) {
                        helper.fail("port compartment flooded: roll " + boat.getZRot() + ", expected negative list");
                    }
                    if (boat.isRemoved() || boat.isDestroyed()) {
                        helper.fail("one of four compartments sank the hull");
                    }
                    HullComponents pools = boat.hullComponents();
                    pools.setHealth(pools.indexOf("compartment left"), pools.maxHealth(pools.indexOf("compartment left")));
                    set(boat, "compartment right", 0);
                })
                .thenIdle(120)
                .thenExecute(() -> {
                    if (boat.getZRot() < 1.0f) {
                        helper.fail("starboard compartment flooded: roll " + boat.getZRot() + ", expected positive list");
                    }
                    for (String id : COMPARTMENTS) {
                        set(boat, id, 0);
                    }
                })
                .thenIdle(700)
                .thenExecute(() -> {
                    if (!boat.isRemoved() && !boat.isDestroyed()) {
                        helper.fail("every compartment lost, not destroyed after 700 ticks: y " + boat.getY()
                                + " health " + boat.getHealth() + " afloat " + boat.afloat());
                    }
                })
                .thenExecute(() -> discard(boat))
                .thenSucceed();
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, batch = "naval", template = POOL, timeoutTicks = 400)
    public static void riverineDamageEnginePoolZeroesSpeed(GameTestHelper helper) {
        ShipVehicle boat = spawn(helper, 24, 13, 10);
        if (boat == null) {
            return;
        }
        helper.startSequence()
                .thenIdle(60)
                .thenExecute(() -> {
                    if (!declared(helper, boat, "engine")) {
                        return;
                    }
                    boat.toggleEngine(true);
                    boat.setPower(100);
                    boat.setHelm(1.0, 0.0);
                })
                .thenIdle(60)
                .thenExecute(() -> {
                    if (boat.surge() < 0.3) {
                        helper.fail("intact engine: surge only " + boat.surge());
                    }
                    set(boat, "engine", 0);
                    if (VehicleMobility.of(boat).speedScale() != 0.0f) {
                        helper.fail("engine pool 0: speedScale " + VehicleMobility.of(boat).speedScale());
                    }
                })
                .thenIdle(160)
                .thenExecute(() -> {
                    if (boat.surge() > 0.02) {
                        helper.fail("engine pool 0: surge still " + boat.surge());
                    }
                })
                .thenExecute(() -> discard(boat))
                .thenSucceed();
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, batch = "naval", template = POOL, timeoutTicks = 400)
    public static void riverineDamageRudderPoolZeroesTurn(GameTestHelper helper) {
        ShipVehicle boat = spawn(helper, 24, 13, 24);
        if (boat == null) {
            return;
        }
        helper.startSequence()
                .thenIdle(60)
                .thenExecute(() -> {
                    if (!declared(helper, boat, "rudder")) {
                        return;
                    }
                    set(boat, "rudder", 0);
                    VehicleMobility mobility = VehicleMobility.of(boat);
                    if (mobility.turnScale() != 0.0f || mobility.speedScale() != 1.0f) {
                        helper.fail("rudder pool 0: scales speed " + mobility.speedScale() + " turn " + mobility.turnScale());
                    }
                    boat.toggleEngine(true);
                    boat.setPower(100);
                    boat.setHelm(0.4, 1.0);
                })
                .thenIdle(60)
                .thenExecute(() -> {
                    float yaw = Mth.wrapDegrees(boat.getYRot());
                    if (Math.abs(yaw) > 1.0f) {
                        helper.fail("rudder pool 0: full rudder for 60 ticks still turned " + yaw + " degrees");
                    }
                    if (boat.surge() < 0.1) {
                        helper.fail("rudder pool 0 stopped the boat: surge " + boat.surge());
                    }
                })
                .thenExecute(() -> discard(boat))
                .thenSucceed();
    }

    private static boolean declared(GameTestHelper helper, ShipVehicle boat, String... ids) {
        for (String id : ids) {
            if (boat.hullComponents().indexOf(id) < 0) {
                helper.fail("riverine declares no pool '" + id + "'");
                return false;
            }
        }
        return true;
    }

    private static void set(ShipVehicle boat, String id, float health) {
        HullComponents pools = boat.hullComponents();
        pools.setHealth(pools.indexOf(id), health);
    }

    @Nullable
    private static ShipVehicle spawn(GameTestHelper helper, int x, int y, int z) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(BOAT);
        if (data.isEmpty()) {
            helper.fail(BOAT + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(x, y, z));
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(BOAT);
        if (!(vehicle instanceof ShipVehicle ship)) {
            helper.fail(BOAT + " is not a ship");
            return null;
        }
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail(BOAT + " could not be added to the world");
            return null;
        }
        vehicle.setEnergy(vehicle.energyInfo.capacityUnits());
        return ship;
    }

    private static void discard(AbstractVehicle vehicle) {
        if (!vehicle.isRemoved()) {
            vehicle.discard();
        }
    }
}
