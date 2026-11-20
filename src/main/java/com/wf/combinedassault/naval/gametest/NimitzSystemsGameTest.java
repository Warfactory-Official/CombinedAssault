package com.wf.combinedassault.naval.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.component.ComponentKind;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Nimitz hit-node pools (one batch per test: a 330-block hull spans neighbouring arenas, whose cleanup discards it): counts, sink threshold, mobility gates. Spawned in the stock basin; no buoyancy asserted. */
@PrefixGameTestTemplate(false)
public class NimitzSystemsGameTest {

    private static final String POOL = "water_pool";
    private static final ResourceLocation CARRIER = CombinedAssault.modLocation("nimitz");
    private static final int COMPARTMENTS = 16;

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, batch = "nimitz1", template = POOL, timeoutTicks = 100)
    public static void nimitzDeclaresItsSystemPools(GameTestHelper helper) {
        ShipVehicle ship = spawn(helper);
        if (ship == null) {
            return;
        }
        HullComponents pools = ship.hullComponents();
        expect(helper, pools, ComponentKind.COMPARTMENT, COMPARTMENTS);
        expect(helper, pools, ComponentKind.PROPELLER, 4);
        expect(helper, pools, ComponentKind.RUDDER, 2);
        expect(helper, pools, ComponentKind.ENGINE, 6);
        expect(helper, pools, ComponentKind.AMMO, 2);
        expect(helper, pools, ComponentKind.FUEL, 3);
        expect(helper, pools, ComponentKind.OPTICS, 2);
        int left = ids(pools, ComponentKind.COMPARTMENT, "left").size();
        int right = ids(pools, ComponentKind.COMPARTMENT, "right").size();
        if (left != COMPARTMENTS / 2 || right != COMPARTMENTS / 2) {
            helper.fail("compartment sides: " + left + " left, " + right + " right");
        }
        if (ship.params().sinkAt != 0.75 || ship.params().listMax != 12.0) {
            helper.fail("sink_at " + ship.params().sinkAt + " list_max " + ship.params().listMax);
        }
        VehicleMobility mobility = VehicleMobility.of(ship);
        if (mobility.speedScale() != 1.0f || mobility.turnScale() != 1.0f) {
            helper.fail("intact hull: speed " + mobility.speedScale() + " turn " + mobility.turnScale());
        }
        discard(ship);
        helper.succeed();
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, batch = "nimitz3", template = POOL, timeoutTicks = 1000)
    public static void nimitzIntactHullSurvivesTheBasin(GameTestHelper helper) {
        ShipVehicle ship = spawn(helper);
        if (ship == null) {
            return;
        }
        helper.startSequence()
                .thenIdle(720)
                .thenExecute(() -> {
                    if (ship.isRemoved()) {
                        helper.fail("undamaged hull removed after 720 ticks: health " + ship.getHealth() + " y " + ship.getY() + " roll " + ship.getZRot());
                    }
                })
                .thenExecute(() -> discard(ship))
                .thenSucceed();
    }

    /** 11 of 16 lost = 0.6875 < sink_at 0.75. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, batch = "nimitz4", template = POOL, timeoutTicks = 1000)
    public static void nimitzSurvivesBelowSinkAt(GameTestHelper helper) {
        ShipVehicle ship = spawn(helper);
        if (ship == null) {
            return;
        }
        List<String> all = ids(helper, ship.hullComponents(), ComponentKind.COMPARTMENT);
        helper.startSequence()
                .thenIdle(20)
                .thenExecute(() -> {
                    ship.toggleEngine(true);
                    all.subList(0, 11).forEach(id -> set(ship, id, 0));
                })
                .thenIdle(700)
                .thenExecute(() -> {
                    if (ship.isRemoved() || ship.getHealth() <= 0) {
                        helper.fail("11 of 16 compartments lost (flood 0.69 < sink_at 0.75) sank the hull: " + ship.getRemovalReason());
                    }
                })
                .thenExecute(() -> discard(ship))
                .thenSucceed();
    }

    /** 12 of 16 lost = 0.75 = sink_at: written off 600 ticks later. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, batch = "nimitz6", template = POOL, timeoutTicks = 1200)
    public static void nimitzSinksAtSinkAt(GameTestHelper helper) {
        ShipVehicle ship = spawn(helper);
        if (ship == null) {
            return;
        }
        List<String> all = ids(helper, ship.hullComponents(), ComponentKind.COMPARTMENT);
        helper.startSequence()
                .thenIdle(20)
                .thenExecute(() -> {
                    ship.toggleEngine(true);
                    all.subList(0, 12).forEach(id -> set(ship, id, 0));
                })
                .thenIdle(900)
                .thenExecute(() -> {
                    if (!ship.isRemoved() && !ship.isDestroyed()) {
                        helper.fail("12 of 16 compartments lost (flood 0.75 = sink_at), hull neither removed nor destroyed after 900 ticks: health " + ship.getHealth() + " y " + ship.getY() + " tickCount " + ship.tickCount + " flood " + flood(ship) + " destroyed " + ship.isDestroyed());
                    }
                })
                .thenExecute(() -> discard(ship))
                .thenSucceed();
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, batch = "nimitz5", template = POOL, timeoutTicks = 100)
    public static void nimitzEnginePropellerRudderPoolsGateMobility(GameTestHelper helper) {
        ShipVehicle ship = spawn(helper);
        if (ship == null) {
            return;
        }
        HullComponents pools = ship.hullComponents();
        List<String> rudders = ids(helper, pools, ComponentKind.RUDDER);
        List<String> engines = ids(helper, pools, ComponentKind.ENGINE);
        List<String> props = ids(helper, pools, ComponentKind.PROPELLER);
        check(helper, "one of 2 rudders lost", rudders.subList(0, 1), ship, 1.0f, 0.5f);
        check(helper, "both rudders lost", rudders, ship, 1.0f, 0.0f);
        check(helper, "one of 6 engine pools lost", engines.subList(0, 1), ship, 5.0f / 6.0f, 1.0f);
        check(helper, "all engine pools lost", engines, ship, 0.0f, 1.0f);
        check(helper, "one of 4 propellers lost", props.subList(0, 1), ship, 0.75f, 1.0f);
        check(helper, "all propellers lost", props, ship, 0.0f, 1.0f);
        if (VehicleMobility.of(ship).speedScale() != 1.0f || VehicleMobility.of(ship).turnScale() != 1.0f) {
            helper.fail("restored pools: " + VehicleMobility.of(ship));
        }
        discard(ship);
        helper.succeed();
    }

    private static void check(GameTestHelper helper, String what, List<String> lost, ShipVehicle ship, float speed, float turn) {
        lost.forEach(id -> set(ship, id, 0));
        VehicleMobility m = VehicleMobility.of(ship);
        if (Math.abs(m.speedScale() - speed) > 1.0e-3f || Math.abs(m.turnScale() - turn) > 1.0e-3f) {
            helper.fail(what + ": speed " + m.speedScale() + " turn " + m.turnScale() + ", expected " + speed + " / " + turn);
        }
        lost.forEach(id -> restore(ship, id));
    }

    private static void expect(GameTestHelper helper, HullComponents pools, ComponentKind kind, int count) {
        int n = ids(pools, kind, "").size();
        if (n != count) {
            helper.fail("nimitz declares " + n + " " + kind.code + " pools, expected " + count);
        }
    }

    private static List<String> ids(HullComponents pools, ComponentKind kind, String token) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < pools.size(); i++) {
            if (pools.kind(i) == kind && pools.id(i).contains(token)) {
                out.add(pools.id(i));
            }
        }
        return out;
    }

    private static List<String> ids(GameTestHelper helper, HullComponents pools, ComponentKind kind) {
        List<String> out = ids(pools, kind, "");
        if (out.isEmpty()) {
            helper.fail("nimitz declares no " + kind.code + " pool");
        }
        return out;
    }

    private static double flood(ShipVehicle ship) {
        HullComponents pools = ship.hullComponents();
        double lost = 0;
        int n = 0;
        for (int i = 0; i < pools.size(); i++) {
            if (pools.kind(i) == ComponentKind.COMPARTMENT) {
                lost += 1.0 - pools.effectiveness(i);
                n++;
            }
        }
        return lost / n;
    }

    private static void set(ShipVehicle ship, String id, float health) {
        HullComponents pools = ship.hullComponents();
        pools.setHealth(pools.indexOf(id), health);
    }

    private static void restore(ShipVehicle ship, String id) {
        HullComponents pools = ship.hullComponents();
        int at = pools.indexOf(id);
        pools.setHealth(at, pools.maxHealth(at));
    }

    @Nullable
    private static ShipVehicle spawn(GameTestHelper helper) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(CARRIER);
        if (data.isEmpty()) {
            helper.fail(CARRIER + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(24, 13, 24));
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(CARRIER);
        if (!(vehicle instanceof ShipVehicle ship)) {
            helper.fail(CARRIER + " is not a ship");
            return null;
        }
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail(CARRIER + " could not be added to the world");
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
