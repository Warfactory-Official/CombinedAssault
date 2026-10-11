package com.wf.combinedassault.indirect.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.indirect.IndirectFireSpec;
import com.wf.combinedassault.vehicle.part.MissileHolderUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.pojo.AimContext;
import com.wf.combinedassault.vehicle.pojo.MissileSize;
import com.wf.combinedassault.vehicle.structure.VehicleCubeOBB;
import com.wf.combinedassault.vehicle.weapon.VehicleMissile;
import com.wf.wflib.MissileEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** M142: six GMLRS cells on the elevating pod, mixed-pod round selection, indirect fire on artillery only. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class HimarsGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation M142 = CombinedAssault.modLocation("m142");
    private static final Item GMLRS = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("wflib", "missile_gmlrs"));
    private static final Item GMLRS_AW =
            BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("wflib", "missile_gmlrs_aw"));
    private static final Item CRUISE = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("wflib", "missile_cruise"));

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void thePodTakesSixGmlrsAndNothingElse(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, M142);
        MissileHolderUnit pod = vehicle == null ? null : pod(helper, vehicle);
        if (pod == null) {
            return;
        }
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            boolean cruise = MissileHolderUnit.tryLoad(vehicle, new ItemStack(CRUISE));
            int taken = 0;
            for (int i = 0; i < 7; i++) {
                taken += MissileHolderUnit.tryLoad(vehicle, new ItemStack(i % 2 == 0 ? GMLRS : GMLRS_AW)) ? 1 : 0;
            }
            List<String> stations = new ArrayList<>();
            for (int i = 0; i < pod.getCapacity(); i++) {
                VehicleCubeOBB station = pod.station(i);
                if (station == null) {
                    stations.add(i + ": none");
                } else if (forward(station).z < 0.99) {
                    stations.add(i + ": faces " + forward(station));
                }
            }
            vehicle.discard();
            if (MissileSize.of(new ItemStack(GMLRS)) != MissileSize.POD) {
                helper.fail("GMLRS is not tagged a pod round");
            } else if (cruise) {
                helper.fail("the pod took a cruise missile");
            } else if (pod.getCapacity() != 6 || taken != 6) {
                helper.fail("pod of " + pod.getCapacity() + " took " + taken + " of 7 GMLRS");
            } else if (!stations.isEmpty()) {
                helper.fail("stations not facing forward at rest: " + stations);
            } else {
                helper.succeed();
            }
        });
    }

    /** The pod frame rides the launcher's pitch frame: elevating the launcher tilts every station. */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void thePodElevatesWithTheLauncher(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, M142);
        MissileHolderUnit pod = vehicle == null ? null : pod(helper, vehicle);
        WeaponUnit launcher = vehicle == null ? null : launcher(helper, vehicle);
        if (pod == null || launcher == null) {
            return;
        }
        launcher.setXAimRot(-50);
        // Wait on the lay, not a tick count: a busy suite starts the entity ticking late.
        helper.startSequence().thenWaitUntil(() -> helper.assertTrue(Math.abs(-launcher.getXRot() - 50) <= 0.5,
                "the launcher laid to " + -launcher.getXRot() + " degrees, not 50")).thenExecute(() -> {
            VehicleCubeOBB station = pod.station(0);
            Vector3f nose = station == null ? new Vector3f() : forward(station);
            // World elevation: the 3x3 arena tips the hull, the pod must tip with it.
            float world = -launcher.worldRot().x;
            vehicle.discard();
            if (Math.abs(nose.y - Math.sin(Math.toRadians(world))) > 0.02) {
                helper.fail(String.format(Locale.ROOT, "launcher at %.1f deg world, station 0 points %s", world, nose));
            } else {
                helper.succeed();
            }
        });
    }

    /** Mixed pod: the selected kind leaves, the others stay; a kind no longer loaded fires nothing. */
    @GameTest(template = TEMPLATE, timeoutTicks = 140)
    public static void theOperatorPicksTheRoundFromAMixedPod(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, M142);
        MissileHolderUnit pod = vehicle == null ? null : pod(helper, vehicle);
        WeaponUnit launcher = vehicle == null ? null : launcher(helper, vehicle);
        if (pod == null || launcher == null) {
            return;
        }
        seed(helper, pod, GMLRS, GMLRS, GMLRS_AW);
        boolean[] seen = new boolean[3];
        String[] second = new String[1];
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            VehicleMissile missile = missile(launcher);
            missile.setSelectedRound(GMLRS_AW);
            missile.tick();
            seen[0] = fire(helper, vehicle, missile) == 1 && pod.round(2).isEmpty() && pod.count(GMLRS) == 2;
        }).thenIdle(40).thenExecute(() -> {
            VehicleMissile missile = missile(launcher);
            missile.endCooldown();
            missile.setSelectedRound(Items.AIR);
            missile.tick();
            int fired = fire(helper, vehicle, missile);
            seen[1] = fired == 1 && pod.round(0).isEmpty() && !pod.round(1).isEmpty();
            second[0] = "fired " + fired + ", pod " + pod.kinds();
        }).thenIdle(40).thenExecute(() -> {
            VehicleMissile missile = missile(launcher);
            missile.endCooldown();
            missile.setSelectedRound(GMLRS_AW);
            missile.tick();
            seen[2] = fire(helper, vehicle, missile) == 0 && pod.count(GMLRS) == 1;
            vehicle.discard();
            if (!seen[0]) {
                helper.fail("selected AW did not leave alone; pod after: " + pod.kinds());
            } else if (!seen[1]) {
                helper.fail("no selection did not fire the lowest loaded cell: " + second[0]);
            } else if (!seen[2]) {
                helper.fail("an AW selection with no AW left fired a unitary round");
            } else {
                helper.succeed();
            }
        });
    }

    /** @return rounds launched; discarded before they leave the arena. */
    private static int fire(GameTestHelper helper, AbstractVehicle vehicle, VehicleMissile missile) {
        missile.shoot(aim(vehicle), null);
        List<MissileEntity> flying = helper.getLevel().getEntitiesOfClass(MissileEntity.class,
                vehicle.getBoundingBox().inflate(16));
        flying.forEach(Entity::discard);
        return flying.size();
    }

    private static VehicleMissile missile(WeaponUnit launcher) {
        return (VehicleMissile) launcher.getIndexedWeapons().get(0);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void indirectFireIsArtilleryOnly(GameTestHelper helper) {
        List<String> wrong = new ArrayList<>();
        expect(helper, wrong, "m142", "launcher", true, true);
        expect(helper, wrong, "m1129", "mortar", true, true);
        expect(helper, wrong, "m252a1", "mortar", true, false);
        expect(helper, wrong, "m1a2", null, false, false);
        if (wrong.isEmpty()) {
            helper.succeed();
        } else {
            helper.fail(String.join("; ", wrong));
        }
    }

    private static void expect(GameTestHelper helper, List<String> wrong, String id, @Nullable String station,
                               boolean indirect, boolean map) {
        AbstractVehicle vehicle = spawn(helper, CombinedAssault.modLocation(id));
        if (vehicle == null) {
            wrong.add(id + " missing");
            return;
        }
        for (var part : vehicle.getPartUnits()) {
            if (part instanceof WeaponUnit unit) {
                IndirectFireSpec spec = unit.getIndirectFireSpec();
                boolean named = part.getData().getId().equals(station);
                if (named && (spec == null) == indirect || named && spec != null && spec.map() != map
                        || !named && spec != null) {
                    wrong.add(id + "." + part.getData().getId() + " indirect " + spec);
                }
            }
        }
        vehicle.discard();
    }

    private static Vector3f forward(VehicleCubeOBB station) {
        return station.rotation.transform(new Vector3f(0, 0, 1));
    }

    private static List<AimContext> aim(AbstractVehicle vehicle) {
        AimContext aim = new AimContext();
        aim.from = vehicle.position().add(0, 6, 0);
        aim.direction = new Vec2(-50, 0);
        aim.position = vehicle.position().add(0, 0, 400);
        return new ArrayList<>(List.of(aim));
    }

    /** Rounds into slots 0.. the way a save does, past the size check. */
    public static void seed(GameTestHelper helper, MissileHolderUnit pod, Item... rounds) {
        ListTag list = new ListTag();
        for (int i = 0; i < rounds.length; i++) {
            CompoundTag entry = (CompoundTag) new ItemStack(rounds[i]).save(helper.getLevel().registryAccess());
            entry.putByte("Slot", (byte) i);
            list.add(entry);
        }
        CompoundTag tag = pod.serializeNBT(helper.getLevel().registryAccess());
        tag.put("Rounds", list);
        pod.deserializeNBT(helper.getLevel().registryAccess(), tag);
    }

    @Nullable
    private static AbstractVehicle spawn(GameTestHelper helper, ResourceLocation id) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            helper.fail(id + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(), new Vec3(at.getX() + 0.5, at.getY(),
                at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(id);
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail(id + " could not be added to the world");
            return null;
        }
        return vehicle;
    }

    @Nullable
    private static MissileHolderUnit pod(GameTestHelper helper, AbstractVehicle vehicle) {
        if (vehicle.getPartUnit("pod").orElse(null) instanceof MissileHolderUnit pod) {
            return pod;
        }
        vehicle.discard();
        helper.fail("the M142 has no pod");
        return null;
    }

    @Nullable
    private static WeaponUnit launcher(GameTestHelper helper, AbstractVehicle vehicle) {
        if (vehicle.getPartUnit("launcher").orElse(null) instanceof WeaponUnit unit) {
            return unit;
        }
        vehicle.discard();
        helper.fail("the M142 has no launcher station");
        return null;
    }
}
