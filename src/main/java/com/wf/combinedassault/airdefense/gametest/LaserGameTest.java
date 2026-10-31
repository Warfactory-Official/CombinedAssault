package com.wf.combinedassault.airdefense.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.airdefense.AirDefense;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.gametest.TestLanes;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.wflib.missile.api.InterceptBattery;
import com.wf.wflib.recon.api.PortableJammerItem;
import com.wf.wflib.recon.api.Band;
import com.wf.wflib.recon.api.Interference;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Laser trailer, AUTO + own radar: mass/armour formula => a Mavic burns, an MQ-9 shrugs. */
@PrefixGameTestTemplate(false)
public class LaserGameTest {
    private static final String TEMPLATE = "empty";
    private static final ResourceLocation TRAILER = CombinedAssault.modLocation("laser_trailer");
    /** MIM-104 radar 384 => clear of its lanes (-1500..-1740). */
    private static final int LANE_Z = -5000, LANE_PITCH = -48, FLOOR_X = 3, FLOOR_Z = 10;
    private static final Vec3 HOLD = new Vec3(10, 40, 0);
    private static final int MAVIC_DEADLINE = 120;
    private static final int MQ9_DWELL = 100;

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 400)
    public static void laserBurnsDownAHostileMavic(GameTestHelper helper) {
        AbstractVehicle[] target = new AbstractVehicle[1];
        int[] ticks = {0};
        TestLanes.run(helper, LANE_Z, FLOOR_X, FLOOR_Z, TRAILER, (seq, v) -> seq.thenExecute(() -> {
            arm(helper, v.get());
            target[0] = TestLanes.spawn(helper, CombinedAssault.modLocation("mavic"), v.get().position().add(HOLD));
        }).thenWaitUntil(() -> {
            hold(target[0], v.get());
            helper.assertTrue(target[0].isDestroyed() || ++ticks[0] >= MAVIC_DEADLINE, "burning");
        }).thenExecute(() -> {
            InterceptBattery.State state = turret(helper, v.get()).airDefense().state();
            float health = target[0].getHealth();
            String why = diagnose(helper, v.get(), target[0]);
            target[0].discard();
            helper.assertTrue(target[0].isDestroyed(), "Mavic alive after " + ticks[0] + " ticks, health " + health
                    + ", state " + state + ", " + why);
        }), at -> {
        });
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 400)
    public static void laserBarelyMarksAnMq9(GameTestHelper helper) {
        AbstractVehicle[] target = new AbstractVehicle[1];
        boolean[] fired = {false};
        int[] ticks = {0};
        TestLanes.run(helper, LANE_Z + LANE_PITCH, FLOOR_X, FLOOR_Z, TRAILER, (seq, v) -> seq.thenExecute(() -> {
            arm(helper, v.get());
            target[0] = TestLanes.spawn(helper, CombinedAssault.modLocation("mq9"), v.get().position().add(HOLD));
        }).thenWaitUntil(() -> {
            hold(target[0], v.get());
            fired[0] |= turret(helper, v.get()).airDefense().state() == InterceptBattery.State.FIRED;
            helper.assertTrue(++ticks[0] >= MQ9_DWELL, "burning");
        }).thenExecute(() -> {
            float health = target[0].getHealth() / target[0].getMaxHealth();
            String why = diagnose(helper, v.get(), target[0]);
            target[0].discard();
            helper.assertTrue(fired[0], "the station never engaged the MQ-9: " + why);
            helper.assertTrue(health > 0.95f, "MQ-9 at " + health + " after " + MQ9_DWELL + " beam ticks");
        }), at -> {
        });
    }

    /** Same Mavic with a portable jammer's area on it: the organic radar never holds it, the beam never fires. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 400)
    public static void aJammedMavicIsNeverEngaged(GameTestHelper helper) {
        AbstractVehicle[] target = new AbstractVehicle[1];
        java.util.Set<String> others = new java.util.TreeSet<>();
        boolean[] fired = {false};
        int[] ticks = {0};
        TestLanes.run(helper, LANE_Z + 2 * LANE_PITCH, FLOOR_X, FLOOR_Z, TRAILER, (seq, v) -> seq.thenExecute(() -> {
            arm(helper, v.get());
            target[0] = TestLanes.spawn(helper, CombinedAssault.modLocation("mavic"), v.get().position().add(HOLD));
        }).thenWaitUntil(() -> {
            hold(target[0], v.get());
            ServerLevel level = helper.getLevel();
            Interference.put(level, target[0].getUUID(), new Interference.Area(Band.RADAR, Interference.Scope.TARGET,
                    target[0].position(), PortableJammerItem.RADIUS, PortableJammerItem.FACTOR,
                    level.getGameTime() + 2, 0.0f));
            AirDefense station = turret(helper, v.get()).airDefense();
            // Sibling lanes' unjammed Mavics sit inside laser range => FIRED alone proves nothing.
            if (station.state() == InterceptBattery.State.FIRED) {
                fired[0] |= target[0].getUUID().equals(station.target());
                others.add(TestLanes.describe(helper, station.target(), v.get()));
            }
            helper.assertTrue(++ticks[0] >= MAVIC_DEADLINE, "jamming");
        }).thenExecute(() -> {
            float health = target[0].getHealth();
            target[0].discard();
            helper.assertFalse(fired[0], "the station burned a jammed Mavic; fired at " + others);
            helper.assertTrue(health == target[0].getMaxHealth(), "jammed Mavic at " + health);
        }), at -> {
        });
    }

    /** Battery target, line from the muzzle (first block hit), bore vs line angle, energy. */
    private static String diagnose(GameTestHelper helper, AbstractVehicle trailer, AbstractVehicle target) {
        WeaponUnit unit = turret(helper, trailer);
        Vec3 from = unit.aimContext().from;
        Vec3 to = target.getBoundingBox().getCenter();
        var hit = helper.getLevel().clip(new net.minecraft.world.level.ClipContext(from, to,
                net.minecraft.world.level.ClipContext.Block.COLLIDER, net.minecraft.world.level.ClipContext.Fluid.NONE,
                (net.minecraft.world.entity.Entity) null));
        String blocked = hit.getType() == net.minecraft.world.phys.HitResult.Type.MISS ? "clear"
                : "blocked at " + hit.getBlockPos().subtract(BlockPos.containing(from)) + " by "
                + helper.getLevel().getBlockState(hit.getBlockPos());
        double off = Math.toDegrees(Math.acos(Math.min(1, unit.worldVec().normalize().dot(to.subtract(from).normalize()))));
        return "target " + TestLanes.describe(helper, unit.airDefense().target(), trailer) + ", line "
                + blocked + ", bore off " + Math.round(off) + " deg, energy " + trailer.getEnergy()
                + ", muzzle-trailer " + from.subtract(trailer.position());
    }

    private static void arm(GameTestHelper helper, AbstractVehicle trailer) {
        trailer.setEnergy(trailer.energyInfo.capacityUnits());
        WeaponUnit turret = turret(helper, trailer);
        turret.airDefense().setMode(AirDefense.Mode.AUTO);
        turret.airDefense().setSource(AirDefense.Source.ORGANIC);
    }

    /** Held over the lane: a free airframe falls or wanders into other tests. */
    private static void hold(AbstractVehicle target, AbstractVehicle trailer) {
        Vec3 at = trailer.position().add(HOLD);
        target.moveTo(at.x, at.y, at.z, 0.0f, 0.0f);
        target.setDeltaMovement(Vec3.ZERO);
    }

    private static WeaponUnit turret(GameTestHelper helper, AbstractVehicle vehicle) {
        helper.assertTrue(vehicle.getPartUnit("laser").orElse(null) instanceof WeaponUnit unit
                && unit.airDefense() != null, "the laser trailer has no air defence turret");
        return (WeaponUnit) vehicle.getPartUnit("laser").get();
    }
}
