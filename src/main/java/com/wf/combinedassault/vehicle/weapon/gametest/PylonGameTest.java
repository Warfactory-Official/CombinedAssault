package com.wf.combinedassault.vehicle.weapon.gametest;

import com.wf.wflib.util.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.entity.vehicle.FixedWingVehicle;
import com.wf.combinedassault.vehicle.part.MissileHolderUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.pojo.AimContext;
import com.wf.combinedassault.vehicle.pojo.MissileSize;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;
import com.wf.combinedassault.vehicle.weapon.VehicleMissile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The MQ-9's six stations: racked and launched from their own rails. WFLib-dependent tests pass untested
 * without {@code -PwithWFLib}; no WFLib type is named here (see {@link BallisticsShellGameTest}).
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class PylonGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation MQ9 = CombinedAssault.modLocation("mq9");
    /** A WFLib round in combinedassault:missile/medium: seeded into stations through the save format, refused by tryLoad. */
    private static final ResourceLocation MEDIUM_ROUND = ResourceLocation.fromNamespaceAndPath("wflib", "missile_cruise");
    private static final ResourceLocation MISSILE_ENTITY_NAMESPACE_PROBE =
            ResourceLocation.fromNamespaceAndPath("wflib", "missile");
    /** Clear of the arena grid and the other lanes (+x 600.., +z ship templates). */
    private static final int FLIGHT_LANE_X = -900;
    private static final int FLIGHT_LENGTH_CHUNKS = 14;

    /** Station i is rail i, inboard to outboard alternating port/starboard, under the wing. */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void theMq9HasSixStationsUnderItsWings(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, arena(helper));
        MissileHolderUnit holder = vehicle == null ? null : holder(helper, vehicle);
        if (holder == null) {
            return;
        }
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            vehicle.discard();
            if (holder.getCapacity() != 6) {
                helper.fail("the rack holds " + holder.getCapacity() + ", not 6");
                return;
            }
            double[] xs = new double[6];
            for (int i = 0; i < 6; i++) {
                var station = holder.stationInModel(i, 1);
                if (station == null) {
                    helper.fail("slot " + i + " has no station");
                    return;
                }
                xs[i] = station.offset().x;
                if (station.offset().y > 1.2 || station.offset().y < 0.5) {
                    helper.fail(String.format(Locale.ROOT, "station %d centred at y %.2f, not under a rail", i,
                            station.offset().y));
                    return;
                }
            }
            for (int i = 0; i < 6; i += 2) {
                if (xs[i] <= 0 || xs[i + 1] >= 0 || Math.abs(xs[i] + xs[i + 1]) > 0.01) {
                    helper.fail(String.format(Locale.ROOT, "stations %d/%d at x %.2f/%.2f are not a port/starboard pair",
                            i, i + 1, xs[i], xs[i + 1]));
                    return;
                }
                if (i > 0 && xs[i] <= xs[i - 2]) {
                    helper.fail("station " + i + " is not outboard of station " + (i - 2));
                    return;
                }
            }
            AbstractVehicleWeapon<?> launcher = launcher(vehicle);
            if (!(launcher instanceof VehicleMissile missile) || missile.missileHolder() != holder
                    || !missile.getData().isHolderOnly()) {
                helper.fail("the hardpoints do not fire holder-only from the pylons: " + launcher);
                return;
            }
            helper.succeed();
        });
    }

    /** Stations are positional across a save and fire lowest first; a medium round does not fit. */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void aRackedRoundKeepsItsStation(GameTestHelper helper) {
        ItemStack round = round(helper);
        AbstractVehicle vehicle = round.isEmpty() ? null : spawn(helper, arena(helper));
        MissileHolderUnit holder = vehicle == null ? null : holder(helper, vehicle);
        if (holder == null) {
            return;
        }
        boolean refused = !MissileHolderUnit.tryLoad(vehicle, round.copy());
        seed(helper, holder, round, 0, 2);
        CompoundTag saved = holder.serializeNBT(helper.getLevel().registryAccess());
        holder.take(0);
        holder.take(2);
        holder.deserializeNBT(helper.getLevel().registryAccess(), saved);
        boolean[] after = {!holder.round(0).isEmpty(), !holder.round(1).isEmpty(), !holder.round(2).isEmpty()};
        int first = holder.nextLoaded();
        holder.take(0);
        int second = holder.nextLoaded();
        vehicle.discard();
        if (!refused) {
            helper.fail("a small-only rack took " + MEDIUM_ROUND);
        } else if (!after[0] || after[1] || !after[2]) {
            helper.fail("a saved rack of 0 and 2 read back as " + after[0] + "/" + after[1] + "/" + after[2]);
        } else if (first != 0 || second != 2) {
            helper.fail("stations 0 and 2 fire in the order " + first + ", " + second);
        } else {
            helper.succeed();
        }
    }

    /** The next round leaves its own station's tail on the launcher's velocity, and only that station empties. */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void aRoundLaunchesFromItsStation(GameTestHelper helper) {
        ItemStack round = round(helper);
        AbstractVehicle vehicle = round.isEmpty() ? null : spawn(helper, arena(helper));
        MissileHolderUnit holder = vehicle == null ? null : holder(helper, vehicle);
        if (holder == null) {
            return;
        }
        seed(helper, holder, round, 1);
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            AbstractVehicleWeapon<?> launcher = launcher(vehicle);
            AimContext expected = holder.launchContext(1);
            if (launcher == null || expected == null) {
                vehicle.discard();
                helper.fail("no launcher, or station 1 has no launch pose");
                return;
            }
            if (launcher.getRemainAmmo() != 1) {
                vehicle.discard();
                helper.fail("one round racked, the launcher counts " + launcher.getRemainAmmo());
                return;
            }
            AimContext aim = new AimContext();
            aim.from = vehicle.position().add(0, 30, 0);
            aim.direction = new net.minecraft.world.phys.Vec2(0, 0);
            aim.position = vehicle.position().add(0, 0, 60);
            Vec3 carrier = vehicle.getDeltaMovement();
            boolean fired = launcher.shoot(new ArrayList<>(List.of(aim)), null);
            ServerLevel level = helper.getLevel();
            List<Entity> missiles = level.getEntities((Entity) null, new AABB(expected.from, expected.from).inflate(3),
                    e -> BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getNamespace()
                            .equals(MISSILE_ENTITY_NAMESPACE_PROBE.getNamespace()));
            boolean emptied = holder.round(1).isEmpty();
            vehicle.discard();
            missiles.forEach(Entity::discard);
            if (!fired || missiles.size() != 1) {
                helper.fail("fired " + fired + ", " + missiles.size() + " WFLib missiles at station 1");
            } else if (missiles.get(0).position().distanceTo(expected.from) > 0.05) {
                helper.fail(String.format(Locale.ROOT, "the round left %.2f from station 1's tail",
                        missiles.get(0).position().distanceTo(expected.from)));
            } else if (missiles.get(0).getDeltaMovement().distanceTo(carrier) > 1.0e-6) {
                helper.fail("the round left at " + missiles.get(0).getDeltaMovement() + ", the launcher at " + carrier);
            } else if (!emptied || holder.loaded() != 0) {
                helper.fail("station 1 still holds its round, or another station lost one");
            } else {
                helper.succeed();
            }
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void anEmptyRackFiresNothing(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, arena(helper));
        if (vehicle == null) {
            return;
        }
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            AbstractVehicleWeapon<?> launcher = launcher(vehicle);
            AimContext aim = new AimContext();
            aim.from = vehicle.position().add(0, 30, 0);
            aim.direction = new net.minecraft.world.phys.Vec2(-90, 0);
            aim.position = vehicle.position().add(0, 0, 60);
            boolean fired = launcher != null && launcher.shoot(new ArrayList<>(List.of(aim)), null);
            List<Entity> projectiles = helper.getLevel().getEntities((Entity) null,
                    vehicle.getBoundingBox().inflate(40), e -> e != vehicle && !(e instanceof AbstractVehicle)
                            && !(e instanceof net.minecraft.world.entity.LivingEntity));
            vehicle.discard();
            if (launcher == null) {
                helper.fail("the MQ-9 has no launcher");
            } else if (launcher.getRemainAmmo() != 0) {
                helper.fail("an empty rack counts " + launcher.getRemainAmmo() + " rounds");
            } else if (fired || !projectiles.isEmpty()) {
                helper.fail("an empty rack fired: " + projectiles);
            } else {
                helper.succeed();
            }
        });
    }

    /** Flown hands-off at cruise it stays in the air and at a flying speed: the pack numbers are an aircraft. */
    @GameTest(template = TEMPLATE, timeoutTicks = 160)
    public static void theMq9FliesLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos lane = helper.absolutePos(BlockPos.ZERO).offset(FLIGHT_LANE_X, 80, 0);
        force(level, lane, true);
        AbstractVehicle spawned = spawn(helper, new Vec3(lane.getX() + 0.5, lane.getY(), lane.getZ() + 0.5));
        if (!(spawned instanceof FixedWingVehicle plane)) {
            force(level, lane, false);
            helper.fail("the MQ-9 is not a fixed-wing aircraft");
            return;
        }
        plane.setEnergy(1800);
        plane.toggleEngine(true);
        plane.setPower(100);
        plane.setThrottleLevel(100);
        plane.setDeltaMovement(0, 0, 2.0);
        double startY = plane.getY();
        helper.startSequence().thenIdle(100).thenExecute(() -> {
            double speed = plane.getDeltaMovement().length();
            double lost = startY - plane.getY();
            int ticked = plane.tickCount;
            plane.discard();
            force(level, lane, false);
            if (ticked < 80) {
                helper.fail("the aircraft only ticked " + ticked + " times; the lane is not loaded");
            } else if (speed < 1.2 || speed > 3.2) {
                helper.fail(String.format(Locale.ROOT, "hands-off at full throttle it is doing %.2f b/t", speed));
            } else if (lost > 12) {
                helper.fail(String.format(Locale.ROOT, "hands-off at full throttle it lost %.1f blocks in %d ticks",
                        lost, ticked));
            } else {
                helper.succeed();
            }
        });
    }


    private static ItemStack round(GameTestHelper helper) {
        ItemStack round = new ItemStack(BuiltInRegistries.ITEM.get(MEDIUM_ROUND));
        if (MissileSize.of(round) != MissileSize.MEDIUM) {
            helper.fail(MEDIUM_ROUND + " is not tagged a medium missile");
            return ItemStack.EMPTY;
        }
        return round;
    }

    /** Puts {@code round} in the given slots the way a save does, past the size check. */
    private static void seed(GameTestHelper helper, MissileHolderUnit holder, ItemStack round, int... slots) {
        ListTag rounds = new ListTag();
        for (int slot : slots) {
            CompoundTag entry = (CompoundTag) round.save(helper.getLevel().registryAccess());
            entry.putByte("Slot", (byte) slot);
            rounds.add(entry);
        }
        CompoundTag tag = holder.serializeNBT(helper.getLevel().registryAccess());
        tag.put("Rounds", rounds);
        holder.deserializeNBT(helper.getLevel().registryAccess(), tag);
    }

    private static Vec3 arena(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        return new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
    }

    @Nullable
    private static AbstractVehicle spawn(GameTestHelper helper, Vec3 at) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(MQ9);
        if (data.isEmpty()) {
            helper.fail(MQ9 + " is not in the roster");
            return null;
        }
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(), at, 0, 0);
        vehicle.setDisplayId(MQ9);
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail("the MQ-9 could not be added to the world");
            return null;
        }
        return vehicle;
    }

    @Nullable
    private static MissileHolderUnit holder(GameTestHelper helper, AbstractVehicle vehicle) {
        if (vehicle.getPartUnit("pylons").orElse(null) instanceof MissileHolderUnit holder) {
            return holder;
        }
        vehicle.discard();
        helper.fail("the MQ-9 has no pylons holder");
        return null;
    }

    @Nullable
    private static AbstractVehicleWeapon<?> launcher(AbstractVehicle vehicle) {
        // Listed on the sight, aimed from the hardpoints.
        return vehicle.getPartUnit("sighting_system").orElse(null) instanceof WeaponUnit unit
                ? unit.getIndexedWeapons().stream().filter(VehicleMissile.class::isInstance).findFirst().orElse(null)
                : null;
    }

    private static void force(ServerLevel level, BlockPos lane, boolean forced) {
        int cx = lane.getX() >> 4;
        int cz = lane.getZ() >> 4;
        for (int dz = -1; dz <= FLIGHT_LENGTH_CHUNKS; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                ForcedChunks.set(level, cx + dx, cz + dz, forced);
            }
        }
    }
}
