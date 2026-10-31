package com.wf.combinedassault.weapon.gametest;

import com.wf.combinedassault.vehicle.VehicleConfig;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.crew.CrewRules;
import com.wf.combinedassault.tow.TowLinks;
import com.wf.combinedassault.dynamics.part.PartClipTracks;
import com.wf.combinedassault.vehicle.part.SwitchableUnit;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.AbstractVehicleWeapon;
import com.wf.wflib.ammo.api.AmmoItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/** M777: recoil clip on the fire shot, crew gate: gunner + loader to load, second loader reloads faster. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class M777GameTest {

    private static final ResourceLocation M777 = CombinedAssault.modLocation("m777");
    private static final ResourceLocation HE = ResourceLocation.fromNamespaceAndPath("wflib", "155mm/he");

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void theBarrelKicksBackOnTheShot(GameTestHelper helper) {
        AbstractVehicle gun = spawn(helper);
        WeaponUnit unit = gun == null ? null : (WeaponUnit) gun.getPartUnit("gun").orElse(null);
        if (unit == null) {
            return;
        }
        PartClipTracks.Track fire = gun.clipTracks() == null ? null : gun.clipTracks().track("gun.fire");
        PartClipTracks.Track deploy = gun.clipTracks() == null ? null : gun.clipTracks().track("deploy");
        AbstractVehicleWeapon<?> weapon = unit.getCurrentWeapon().orElseThrow();
        deploy(gun, true);
        float idle = fire == null ? -1 : fire.progress(0);
        weapon.setRemainAmmo(1);
        weapon.setLoadedRound(AmmoItems.stack(HE, 1));
        boolean fired = weapon.shoot(new ArrayList<>(List.of(unit.aimContext())), null);
        float kick = fire == null ? -1 : fire.progress(0);
        gun.discard();
        if (fire == null || deploy == null || idle != 1 || !fired || kick > 0.1f) {
            helper.fail("fire clip " + fire + ", deploy clip " + deploy + ", idle " + idle + ", fired " + fired + ", after shot " + kick);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aCrewedGunNeedsALoaderAndTwoLoadersReloadFaster(GameTestHelper helper) {
        AbstractVehicle gun = spawn(helper);
        WeaponUnit unit = gun == null ? null : (WeaponUnit) gun.getPartUnit("gun").orElse(null);
        if (unit == null) {
            return;
        }
        boolean crew = VehicleConfig.crewRequired().get();
        try {
            VehicleConfig.crewRequired().set(true);
            int base = unit.getCurrentWeapon().orElseThrow().getData().getReload().getTime();
            boolean emptyLoads = CrewRules.canLoad(unit);
            int emptyTicks = CrewRules.reloadTicks(unit, base);
            seat(gun, "loader");
            boolean oneLoads = CrewRules.canLoad(unit);
            int oneTicks = CrewRules.reloadTicks(unit, base);
            seat(gun, "assistant");
            int twoTicks = CrewRules.reloadTicks(unit, base);
            VehicleConfig.crewRequired().set(false);
            boolean soloLoads = CrewRules.canLoad(unit);
            int soloTicks = CrewRules.reloadTicks(unit, base);
            if (emptyLoads || !oneLoads || !soloLoads || emptyTicks != base || oneTicks != base || soloTicks != base
                    || twoTicks >= base) {
                helper.fail("base " + base + ": empty " + emptyLoads + "/" + emptyTicks + ", one loader " + oneLoads + "/"
                        + oneTicks + ", two loaders " + twoTicks + ", crew off " + soloLoads + "/" + soloTicks);
                return;
            }
        } finally {
            VehicleConfig.crewRequired().set(crew);
            gun.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void aStowedGunWillNotFireAndDeployedTrailsBlockTheHitch(GameTestHelper helper) {
        AbstractVehicle gun = spawn(helper);
        WeaponUnit unit = gun == null ? null : (WeaponUnit) gun.getPartUnit("gun").orElse(null);
        if (unit == null) {
            return;
        }
        AbstractVehicleWeapon<?> weapon = unit.getCurrentWeapon().orElseThrow();
        weapon.setRemainAmmo(1);
        weapon.setLoadedRound(AmmoItems.stack(HE, 1));
        boolean stowedStowed = TowLinks.stowed(gun);
        boolean stowedFired = weapon.shoot(new ArrayList<>(List.of(unit.aimContext())), null);
        deploy(gun, true);
        boolean deployedStowed = TowLinks.stowed(gun);
        boolean deployedSupported = unit.supported();
        gun.discard();
        if (!stowedStowed || stowedFired || deployedStowed || !deployedSupported) {
            helper.fail("stowed: stowed " + stowedStowed + ", fired " + stowedFired + "; deployed: stowed " + deployedStowed
                    + ", supported " + deployedSupported);
            return;
        }
        helper.succeed();
    }

    private static final Quaternionf TRAIL_L_STOWED = new Quaternionf(0.0864f, 0.13f, -0.0114f, 0.9877f).normalize();
    private static final Quaternionf TRAIL_L_DEPLOYED = new Quaternionf(0, 0.3255f, 0, 0.9456f).normalize();

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void deployedTrailsMoveTheirColliders(GameTestHelper helper) {
        AbstractVehicle gun = spawn(helper);
        if (gun == null) {
            return;
        }
        float stowed = trailAngle(gun, TRAIL_L_STOWED);
        deploy(gun, true);
        helper.runAfterDelay(100, () -> {
            float deployed = trailAngle(gun, TRAIL_L_DEPLOYED);
            float stillStowed = trailAngle(gun, TRAIL_L_STOWED);
            gun.discard();
            if (stowed > 0.01f || deployed > 0.01f || stillStowed < 0.3f) {
                helper.fail("trail collider off the clip by (rad): stowed " + stowed + ", deployed " + deployed
                        + ", deployed vs stowed pose " + stillStowed);
                return;
            }
            helper.succeed();
        });
    }

    /** Angle between the left trail group's rotation and {@code expected}, in the vehicle frame. */
    private static float trailAngle(AbstractVehicle vehicle, Quaternionf expected) {
        for (var part : vehicle.getPartUnits()) {
            for (var cube : part.getPartCubeOBBs()) {
                if (cube.group != null && cube.group.name.equals("04_REAR_TRAIL_L")) {
                    return cube.group.globalTransform().rotation().difference(expected, new Quaternionf()).angle();
                }
            }
        }
        throw new IllegalStateException("no collider rides 04_REAR_TRAIL_L");
    }

    private static void deploy(AbstractVehicle vehicle, boolean on) {
        ((SwitchableUnit<?>) vehicle.getPartUnit("deploy").orElseThrow()).setOn(on);
    }

    private static void seat(AbstractVehicle vehicle, String part) {
        vehicle.seats.stream().filter(s -> s.partUnit.getId().equals(part)).findFirst().orElseThrow().passengerId = 1000;
    }

    private static AbstractVehicle spawn(GameTestHelper helper) {
        BaseVehicleData<?> data = CommonAssetsManager.vehicleDataManager().getVehicleData(M777).orElse(null);
        if (data == null) {
            helper.fail(M777 + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle vehicle = data.construct(helper.getLevel(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(M777);
        vehicle.setNoGravity(true);
        helper.getLevel().addFreshEntity(vehicle);
        return vehicle;
    }
}
