package com.wf.combinedassault.indirect.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.indirect.IndirectFireSpec;
import com.wf.combinedassault.vehicle.part.AccessActions;
import com.wf.combinedassault.weapon.part.MissileHolderUnit;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.weapon.part.WeaponBayUnit;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.pojo.AimContext;
import com.wf.combinedassault.weapon.pojo.MissileSize;
import com.wf.combinedassault.weapon.VehicleMissile;
import com.wf.combinedassault.weapon.pojo.Station;
import com.wf.wflib.missile.api.MissileEntity;
import com.wf.wflib.magazine.api.MagazineItems;
import com.wf.wflib.magazine.api.PodItem;
import com.wf.wflib.magazine.api.PodTubes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
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
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** M142: six GMLRS cells on the elevating pod, mixed-pod round selection, indirect fire on artillery only; M1129 hatches. */
@PrefixGameTestTemplate(false)
public class HimarsGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation M142 = CombinedAssault.modLocation("m142");
    private static final Item GMLRS = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("wflib", "missile_gmlrs"));
    private static final Item GMLRS_AW =
            BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("wflib", "missile_gmlrs_aw"));
    private static final Item ATACMS = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("wflib", "missile_atacms"));
    private static final Item CRUISE = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("wflib", "missile_cruise"));

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 40)
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
                MissileHolderUnit.StationPose station = pod.station(i);
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

    /** GMLRS pod tubes = the model's six stations; a dry pod swaps for a stored ATACMS pod, one tube at their centre. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 40)
    public static void aDryPodSwapsForAStoredAtacmsPodAndAPodSeatsByHand(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, M142);
        MissileHolderUnit pod = vehicle == null ? null : pod(helper, vehicle);
        WeaponUnit launcher = vehicle == null ? null : launcher(helper, vehicle);
        if (pod == null || launcher == null) {
            return;
        }
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            List<Station> stations = pod.getData().getStations();
            Vec3 centre = Vec3.ZERO;
            for (int i = 0; i < 6; i++) {
                Vec3 tube = pod.stationInModel(i, 1).offset();
                centre = centre.add(tube.scale(1 / 6.0));
                for (int j = 0; j < i; j++) {
                    double want = stations.get(i).offset.distanceTo(stations.get(j).offset);
                    double got = tube.distanceTo(pod.stationInModel(j, 1).offset());
                    helper.assertTrue(Math.abs(want - got) < 1e-3, "tubes " + i + "," + j + " " + got + " apart, stations "
                            + want);
                }
            }
            ItemStack atacms = new ItemStack(MagazineItems.POD_ATACMS.get());
            atacms.set(MagazineItems.POD_TUBES.get(), new PodTubes(List.of(new ItemStack(ATACMS))));
            vehicle.getItemStacks().set(0, atacms);
            ((VehicleMissile) launcher.currentWeapon()).reload();
            helper.assertTrue(pod.getCapacity() == 1 && pod.round(0).is(ATACMS), "ATACMS pod seated");
            helper.assertTrue(pod.stationInModel(0, 1).offset().distanceTo(centre) < 1e-3, "one tube, centred");
            helper.assertTrue(vehicle.getItemStacks().get(0).is(MagazineItems.POD_GMLRS.get()), "empty GMLRS pod stored");
            helper.assertFalse(MissileHolderUnit.tryLoad(vehicle, new ItemStack(GMLRS)), "GMLRS in an ATACMS tube");

            Player player = helper.makeMockPlayer(GameType.SURVIVAL);
            ItemStack gmlrs = new ItemStack(MagazineItems.POD_GMLRS.get());
            gmlrs.set(MagazineItems.POD_TUBES.get(), new PodTubes(Collections.nCopies(6, new ItemStack(GMLRS_AW))));
            helper.assertTrue(MissileHolderUnit.trySeatPod(vehicle, player, gmlrs) && gmlrs.isEmpty(), "seated by hand");
            helper.assertTrue(pod.getCapacity() == 6 && pod.count(GMLRS_AW) == 6, "six AW racked");
            helper.assertTrue(player.getInventory().items.stream().anyMatch(s -> s.is(MagazineItems.POD_ATACMS.get())
                    && PodItem.contents(s).loaded() == 1), "ATACMS pod handed back loaded");
            vehicle.discard();
        }).thenSucceed();
    }

    /** The pod frame rides the launcher's pitch frame: elevating the launcher tilts every station. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
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
            MissileHolderUnit.StationPose station = pod.station(0);
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

    /** A round leaves along its tube: level pod => flat flight, laid pod => climbs at the lay, not vertical. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 300)
    public static void aRocketLeavesAlongItsTube(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, M142);
        MissileHolderUnit pod = vehicle == null ? null : pod(helper, vehicle);
        WeaponUnit launcher = vehicle == null ? null : launcher(helper, vehicle);
        if (pod == null || launcher == null) {
            return;
        }
        List<String> wrong = new ArrayList<>();
        var sequence = helper.startSequence();
        for (float lay : new float[] {0, 30}) {
            MissileEntity[] flying = new MissileEntity[1];
            List<Long> path = new ArrayList<>();
            float[] tube = new float[1];
            sequence.thenExecute(() -> launcher.setXAimRot(-lay))
                    .thenWaitUntil(() -> helper.assertTrue(Math.abs(-launcher.getXRot() - lay) <= 0.5,
                            "laid to " + -launcher.getXRot()))
                    .thenExecute(() -> {
                        seed(helper, pod, GMLRS);
                        VehicleMissile missile = missile(launcher);
                        missile.endCooldown();
                        missile.tick();
                        Vector3f nose = pod.station(0).forward();
                        tube[0] = (float) Math.toDegrees(Math.asin(nose.y));
                        AimContext aim = new AimContext();
                        aim.position = vehicle.position().add(new Vec3(nose.x, 0, nose.z).normalize().scale(300));
                        List<MissileEntity> before = flying(helper, vehicle);
                        missile.shoot(new ArrayList<>(List.of(aim)), null);
                        flying[0] = flying(helper, vehicle).stream().filter(m -> !before.contains(m)).findFirst()
                                .orElseThrow(() -> new IllegalStateException("laid " + lay + " fired nothing"));
                    })
                    .thenExecuteFor(4, () -> {
                        Vec3 v = flying[0].getDeltaMovement();
                        path.add(Math.round(Math.toDegrees(Math.atan2(v.y, v.horizontalDistance()))));
                    })
                    .thenExecute(() -> {
                        flying[0].discard();
                        if (path.stream().anyMatch(e -> Math.abs(e - tube[0]) > 15)) {
                            wrong.add(String.format(Locale.ROOT, "tube at %.0f deg flew %s", tube[0], path));
                        }
                    });
        }
        sequence.thenExecute(() -> {
            vehicle.discard();
            if (wrong.isEmpty()) {
                helper.succeed();
            } else {
                helper.fail(String.join("; ", wrong));
            }
        });
    }

    private static List<MissileEntity> flying(GameTestHelper helper, AbstractVehicle vehicle) {
        return helper.getLevel().getEntitiesOfClass(MissileEntity.class, vehicle.getBoundingBox().inflate(32));
    }

    /** Mixed pod: the selected kind leaves, the others stay; a kind no longer loaded fires nothing. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 140)
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
            missile.selectRound(GMLRS_AW.getDefaultInstance());
            missile.tick();
            seen[0] = fire(helper, vehicle, missile) == 1 && pod.round(2).isEmpty() && pod.count(GMLRS) == 2;
        }).thenIdle(40).thenExecute(() -> {
            VehicleMissile missile = missile(launcher);
            missile.endCooldown();
            missile.selectRound(ItemStack.EMPTY);
            missile.tick();
            int fired = fire(helper, vehicle, missile);
            seen[1] = fired == 1 && pod.round(0).isEmpty() && !pod.round(1).isEmpty();
            second[0] = "fired " + fired + ", pod " + pod.kinds();
        }).thenIdle(40).thenExecute(() -> {
            VehicleMissile missile = missile(launcher);
            missile.endCooldown();
            missile.selectRound(GMLRS_AW.getDefaultInstance());
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

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 40)
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

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 40)
    public static void theMortarHatchesOpenWithTheEngineOff(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, CombinedAssault.modLocation("m1129"));
        if (vehicle == null) {
            return;
        }
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            WeaponUnit mortar = (WeaponUnit) part(vehicle, "mortar");
            WeaponBayUnit hatches = (WeaponBayUnit) part(vehicle, "hatches");
            helper.assertFalse(vehicle.hasPower(), "spawned with power");
            mortar.shoot(0, aim(vehicle), null);
            helper.assertTrue(hatches.isOn(), "first shot left the hatches shut");
            helper.assertTrue(AccessActions.isDoor(part(vehicle, "ramp")), "ramp is not a door");
            helper.assertTrue(AccessActions.isDoor(hatches), "hatches have no access node");
            vehicle.discard();
        }).thenSucceed();
    }

    private static PartUnit<?> part(AbstractVehicle vehicle, String id) {
        return vehicle.getPartUnits().stream().filter(p -> p.getId().equals(id)).findFirst().orElseThrow();
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

    private static Vector3f forward(MissileHolderUnit.StationPose station) {
        return station.forward();
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
