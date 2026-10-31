package com.wf.combinedassault.ballistics.component.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.component.ComponentKind;
import com.wf.combinedassault.ballistics.component.HullComponents;
import com.wf.combinedassault.ballistics.component.VehicleRotors;
import com.wf.combinedassault.ballistics.component.VehicleSystems;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.ballistics.hit.HitMesh;
import com.wf.combinedassault.ballistics.hit.VehicleHits;

import java.util.List;
import java.util.Optional;

@PrefixGameTestTemplate(false)
public class RotorSystemsGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation[] HELIS = {CombinedAssault.modLocation("ah64d"), CombinedAssault.modLocation("ah_6")};

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void attackHelicoptersHaveRotorSystems(GameTestHelper helper) {
        for (ResourceLocation id : HELIS) {
            AbstractVehicle heli = spawn(helper, id);
            if (heli == null) {
                return;
            }
            List<String> labels = VehicleSystems.cells(heli).stream().map(VehicleSystems.Cell::label).toList();
            if (!labels.containsAll(List.of("ROTR", "TAIL"))) {
                helper.fail(id + " cells " + labels);
                return;
            }
            for (String name : new String[]{"rotor", "tail_rotor"}) {
                int at = heli.hits() == null ? -1 : heli.hits().model().indexOf(name);
                if (at < 0 || heli.hits().model().nodes().get(at).mesh().disc() == null) {
                    helper.fail(id + " " + name + ": no rotor disc in the hit model");
                    return;
                }
            }
        }
        helper.succeed();
    }

    /** Unpowered Ka-52 from 25 blocks: free fall (no rotor, no drag cap) => ~22 m/s into the ground. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void aDeadHelicopterFallingIsWrecked(GameTestHelper helper) {
        ResourceLocation id = CombinedAssault.modLocation("ka52");
        BlockPos at = helper.absolutePos(new BlockPos(1, 27, 1));
        AbstractVehicle heli = CommonAssetsManager.vehicleDataManager().getVehicleData(id).orElseThrow()
                .construct(helper.getLevel(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        heli.setDisplayId(id);
        helper.getLevel().addFreshEntity(heli);
        float max = heli.getMaxHealth();
        helper.runAfterDelay(120, () -> {
            float health = heli.isDestroyed() ? 0 : heli.getHealth();
            double y = heli.getY() - helper.absolutePos(BlockPos.ZERO).getY();
            heli.discard();
            if (health > max * 0.7f) {
                helper.fail("ka52 fell 25 blocks: health " + health + " / " + max + ", y " + y);
                return;
            }
            helper.succeed();
        });
    }

    /** Blade tip from above, tail disc from its own side: the surface trace DamageSystem routes by. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void aShotThroughADiscFindsThatRotor(GameTestHelper helper) {
        for (ResourceLocation id : HELIS) {
            AbstractVehicle heli = spawn(helper, id);
            if (heli == null || heli.hits() == null) {
                helper.fail(id + " has no hit model");
                return;
            }
            VehicleHits hits = heli.hits();
            int main = hits.model().indexOf("rotor");
            int tail = hits.model().indexOf("tail_rotor");
            HitMesh.Disc disc = hits.model().nodes().get(main).mesh().disc();
            Vec3 tip = hits.centre(main).add(disc.radius() * 0.9, 0, 0);
            VehicleHits.Hit hit = hits.surface(tip.add(0, 4, 0), tip.add(0, -4, 0));
            if (hit == null || hits.model().nodes().get(hit.node()).kind() != ComponentKind.ROTOR) {
                helper.fail(id + " blade tip shot found " + describe(hits, hit));
                return;
            }
            HitMesh.Disc tailDisc = hits.model().nodes().get(tail).mesh().disc();
            Vec3 blade = hits.centre(tail).add(0, tailDisc.radius() * 0.8, 0); // past the gearbox fairing
            hit = hits.surface(blade.add(4, 0, 0), blade.add(-4, 0, 0));
            if (hit == null || hits.model().nodes().get(hit.node()).kind() != ComponentKind.TAIL_ROTOR) {
                helper.fail(id + " tail rotor shot found " + describe(hits, hit));
                return;
            }
        }
        helper.succeed();
    }

    private static String describe(VehicleHits hits, @Nullable VehicleHits.Hit hit) {
        return hit == null ? "nothing" : hits.model().nodes().get(hit.node()).mesh().name();
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void brokenRotorsCutLiftAndAntiTorque(GameTestHelper helper) {
        AbstractVehicle heli = spawn(helper, HELIS[0]);
        if (heli == null) {
            return;
        }
        if (!VehicleRotors.of(heli).equals(VehicleRotors.FULL)) {
            helper.fail("fresh rotors read " + VehicleRotors.of(heli));
            return;
        }
        HullComponents pools = heli.hullComponents();
        int main = pools.indexOf("rotor");
        int tail = pools.indexOf("tail rotor");
        pools.setHealth(tail, 0);
        VehicleRotors rotors = VehicleRotors.of(heli);
        if (rotors.antiTorque() != 0 || rotors.lift() != 1) {
            helper.fail("broken tail rotor read " + rotors);
            return;
        }
        pools.setHealth(main, pools.maxHealth(main) * 0.4f);
        float damaged = VehicleRotors.of(heli).lift();
        pools.setHealth(main, 0);
        if (!(damaged > 0 && damaged < 1) || VehicleRotors.of(heli).lift() != 0) {
            helper.fail("main rotor lift damaged " + damaged + ", broken " + VehicleRotors.of(heli).lift());
            return;
        }
        helper.succeed();
    }

    @Nullable
    private static AbstractVehicle spawn(GameTestHelper helper, ResourceLocation id) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            helper.fail("the test vehicle " + id + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(id);
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail("the test vehicle could not be added to the world");
            return null;
        }
        vehicle.updateOBBs();
        return vehicle;
    }
}
