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
import java.util.Map;
import java.util.Optional;

/** MEKO A200, Visby, Osa, Narcosub: def loads as a ship, pool counts per kind, one-sided flooding lists, engine loss scales speed (all lost stops). */
@PrefixGameTestTemplate(false)
public class WartyShipsGameTest {

    private static final String POOL = "water_pool";

    private record Expect(String id, double sinkAt, double listMax, Map<ComponentKind, Integer> pools) {
    }

    private static final Expect MEKO = new Expect("meko_a200", 0.6, 18.0, Map.of(
            ComponentKind.COMPARTMENT, 8, ComponentKind.ENGINE, 3, ComponentKind.FUEL, 2, ComponentKind.AMMO, 2,
            ComponentKind.PROPELLER, 2, ComponentKind.RUDDER, 2, ComponentKind.OPTICS, 2));
    private static final Expect VISBY = new Expect("visby", 0.5, 22.0, Map.of(
            ComponentKind.COMPARTMENT, 8, ComponentKind.ENGINE, 2, ComponentKind.FUEL, 2, ComponentKind.AMMO, 1,
            ComponentKind.PROPELLER, 2, ComponentKind.RUDDER, 2, ComponentKind.OPTICS, 2));
    private static final Expect OSA = new Expect("osa", 0.5, 25.0, Map.of(
            ComponentKind.COMPARTMENT, 8, ComponentKind.ENGINE, 3, ComponentKind.FUEL, 2, ComponentKind.AMMO, 4,
            ComponentKind.PROPELLER, 3, ComponentKind.RUDDER, 3, ComponentKind.OPTICS, 2));
    private static final Expect NARCOSUB = new Expect("narcosub", 0.45, 25.0, Map.of(
            ComponentKind.COMPARTMENT, 6, ComponentKind.ENGINE, 1, ComponentKind.FUEL, 2, ComponentKind.BALLAST, 2,
            ComponentKind.PROPELLER, 1, ComponentKind.RUDDER, 1, ComponentKind.OPTICS, 1));

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, batch = "naval", template = POOL, timeoutTicks = 700)
    public static void wartyShipMekoSystems(GameTestHelper helper) {
        systems(helper, MEKO);
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, batch = "naval", template = POOL, timeoutTicks = 700)
    public static void wartyShipVisbySystems(GameTestHelper helper) {
        systems(helper, VISBY);
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, batch = "naval", template = POOL, timeoutTicks = 700)
    public static void wartyShipOsaSystems(GameTestHelper helper) {
        systems(helper, OSA);
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, batch = "naval", template = POOL, timeoutTicks = 700)
    public static void wartyShipNarcosubSystems(GameTestHelper helper) {
        systems(helper, NARCOSUB);
    }

    private static void systems(GameTestHelper helper, Expect expect) {
        ShipVehicle ship = spawn(helper, expect.id());
        if (ship == null) {
            return;
        }
        HullComponents pools = ship.hullComponents();
        for (Map.Entry<ComponentKind, Integer> entry : expect.pools().entrySet()) {
            int n = ids(pools, entry.getKey(), "").size();
            if (n != entry.getValue()) {
                helper.fail(expect.id() + " declares " + n + " " + entry.getKey().code + " pools, expected " + entry.getValue());
                return;
            }
        }
        List<String> leftSide = ids(pools, ComponentKind.COMPARTMENT, "left");
        List<String> rightSide = ids(pools, ComponentKind.COMPARTMENT, "right");
        int all = ids(pools, ComponentKind.COMPARTMENT, "").size();
        if (leftSide.isEmpty() || leftSide.size() != rightSide.size() || 2 * leftSide.size() >= all) {
            helper.fail(expect.id() + " compartment sides: " + leftSide.size() + " left, " + rightSide.size() + " right of " + all);
            return;
        }
        if (ship.params().sinkAt != expect.sinkAt() || ship.params().listMax != expect.listMax()) {
            helper.fail(expect.id() + " sink_at " + ship.params().sinkAt + " list_max " + ship.params().listMax);
            return;
        }
        if (VehicleMobility.of(ship).speedScale() != 1.0f) {
            helper.fail(expect.id() + " intact hull: speed " + VehicleMobility.of(ship).speedScale());
            return;
        }
        List<String> engines = ids(pools, ComponentKind.ENGINE, "");
        set(ship, engines.get(0), 0);
        VehicleMobility mobility = VehicleMobility.of(ship);
        float one = mobility.speedScale();
        boolean several = engines.size() > 1;
        if ((several ? (one <= 0.0f || one >= 1.0f) : one != 0.0f) || mobility.turnScale() != 1.0f) {
            helper.fail(expect.id() + " one of " + engines.size() + " engine pools lost: speed " + one + " turn " + mobility.turnScale());
            return;
        }
        engines.forEach(id -> set(ship, id, 0));
        if (VehicleMobility.of(ship).speedScale() != 0.0f) {
            helper.fail(expect.id() + " every engine pool lost: speed " + VehicleMobility.of(ship).speedScale());
            return;
        }
        engines.forEach(id -> restore(ship, id));
        helper.startSequence()
                .thenIdle(40)
                .thenExecute(() -> leftSide.forEach(id -> set(ship, id, 0)))
                .thenIdle(160)
                .thenExecute(() -> {
                    if (ship.getZRot() > -1.0f) {
                        helper.fail(expect.id() + " port compartments flooded: roll " + ship.getZRot() + ", expected negative list");
                    }
                    if (ship.isRemoved() || ship.getHealth() <= 0) {
                        helper.fail(expect.id() + " one flooded side sank the hull");
                    }
                    leftSide.forEach(id -> restore(ship, id));
                    rightSide.forEach(id -> set(ship, id, 0));
                })
                .thenIdle(200)
                .thenExecute(() -> {
                    if (ship.getZRot() < 1.0f) {
                        helper.fail(expect.id() + " starboard compartments flooded: roll " + ship.getZRot() + ", expected positive list");
                    }
                })
                .thenExecute(() -> discard(ship))
                .thenSucceed();
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
    private static ShipVehicle spawn(GameTestHelper helper, String path) {
        ResourceLocation id = CombinedAssault.modLocation(path);
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            helper.fail(id + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(24, 13, 24));
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(id);
        if (!(vehicle instanceof ShipVehicle ship)) {
            helper.fail(id + " is not a ship");
            return null;
        }
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail(id + " could not be added to the world");
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
