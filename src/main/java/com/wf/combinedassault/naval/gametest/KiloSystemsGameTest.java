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

/** Kilo hit-node pools (own batch per test: a 72 m hull spans neighbouring arenas): counts, sink threshold, mobility averaging. Stock basin; no buoyancy asserted. */
@PrefixGameTestTemplate(false)
public class KiloSystemsGameTest {

    private static final String POOL = "water_pool";
    private static final ResourceLocation KILO = CombinedAssault.modLocation("kilo");

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, batch = "kilo1", template = POOL, timeoutTicks = 100)
    public static void kiloDeclaresItsSystemPools(GameTestHelper helper) {
        ShipVehicle ship = spawn(helper);
        if (ship == null) {
            return;
        }
        HullComponents pools = ship.hullComponents();
        expect(helper, pools, ComponentKind.COMPARTMENT, 6);
        expect(helper, pools, ComponentKind.ENGINE, 2);
        expect(helper, pools, ComponentKind.PROPELLER, 1);
        expect(helper, pools, ComponentKind.RUDDER, 1);
        expect(helper, pools, ComponentKind.FUEL, 2);
        expect(helper, pools, ComponentKind.AMMO, 1);
        expect(helper, pools, ComponentKind.OPTICS, 1);
        expect(helper, pools, ComponentKind.BALLAST, 2);
        int left = ids(pools, ComponentKind.COMPARTMENT, "left").size();
        int right = ids(pools, ComponentKind.COMPARTMENT, "right").size();
        if (left != 3 || right != 3) {
            helper.fail("compartment sides: " + left + " left, " + right + " right");
        }
        if (ids(pools, ComponentKind.BALLAST, "fore").size() != 1 || ids(pools, ComponentKind.BALLAST, "aft").size() != 1) {
            helper.fail("ballast tanks are not fore + aft");
        }
        if (ship.params().sinkAt != 0.5 || ship.params().listMax != 20.0) {
            helper.fail("sink_at " + ship.params().sinkAt + " list_max " + ship.params().listMax);
        }
        discard(ship);
        helper.succeed();
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, batch = "kilo2", template = POOL, timeoutTicks = 100)
    public static void kiloMobilityAveragesPerKind(GameTestHelper helper) {
        ShipVehicle ship = spawn(helper);
        if (ship == null) {
            return;
        }
        HullComponents pools = ship.hullComponents();
        if (VehicleMobility.of(ship).speedScale() != 1.0f) {
            helper.fail("intact hull: speed " + VehicleMobility.of(ship).speedScale());
            return;
        }
        List<String> engines = ids(pools, ComponentKind.ENGINE, "");
        set(ship, engines.get(0), 0);
        VehicleMobility one = VehicleMobility.of(ship);
        if (Math.abs(one.speedScale() - 0.5f) > 1.0e-3f || one.turnScale() != 1.0f) {
            helper.fail("one of two engine pools lost: speed " + one.speedScale() + " turn " + one.turnScale());
            return;
        }
        set(ship, engines.get(1), 0);
        if (VehicleMobility.of(ship).speedScale() != 0.0f) {
            helper.fail("both engine pools lost: speed " + VehicleMobility.of(ship).speedScale());
            return;
        }
        engines.forEach(id -> restore(ship, id));
        set(ship, ids(pools, ComponentKind.PROPELLER, "").get(0), 0);
        if (VehicleMobility.of(ship).speedScale() != 0.0f) {
            helper.fail("only propeller pool lost: speed " + VehicleMobility.of(ship).speedScale());
            return;
        }
        restore(ship, ids(pools, ComponentKind.PROPELLER, "").get(0));
        set(ship, ids(pools, ComponentKind.RUDDER, "").get(0), 0);
        VehicleMobility rudder = VehicleMobility.of(ship);
        if (rudder.turnScale() != 0.0f || rudder.speedScale() != 1.0f) {
            helper.fail("only rudder pool lost: speed " + rudder.speedScale() + " turn " + rudder.turnScale());
            return;
        }
        discard(ship);
        helper.succeed();
    }

    private static void expect(GameTestHelper helper, HullComponents pools, ComponentKind kind, int count) {
        int n = ids(pools, kind, "").size();
        if (n != count) {
            helper.fail("kilo declares " + n + " " + kind.code + " pools, expected " + count);
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
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(KILO);
        if (data.isEmpty()) {
            helper.fail(KILO + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(24, 13, 24));
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(KILO);
        if (!(vehicle instanceof ShipVehicle ship)) {
            helper.fail(KILO + " is not a ship");
            return null;
        }
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail(KILO + " could not be added to the world");
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
