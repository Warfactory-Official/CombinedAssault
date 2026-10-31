package com.wf.combinedassault.cargo.gametest;

import com.wf.wflib.testkit.api.ForcedChunks;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.tow.HitchInfo;
import com.wf.combinedassault.tow.TowLinks;
import com.wf.combinedassault.tow.TowMode;
import com.wf.combinedassault.cargo.VehicleParachute;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.SwitchableUnit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Holds (C-5, CH-47): stow on close, persistence, unload, close veto; airdrop by parachute, hard landing, sling.
 * Own lanes, stone pads.
 */
@PrefixGameTestTemplate(false)
public class CargoGameTest {

    private static final String TEMPLATE = "empty";
    /** Clear of every other lane (settle 1400, climb 2000, physics 12000). */
    private static final int LANE_Z = 4000;
    private static final int LANE_PITCH = 160;
    /** PAD_X covers the sling carry (12) + rope (8) + load: past the forced chunks a load stops ticking. */
    private static final int PAD_X = 32, PAD_FRONT = 16, PAD_BACK = 70;
    /** Hold floor top (C-5 frame) + clearance. */
    private static final double FLOOR = 2.6;
    /** Sling carry speed, blocks/tick (6 m/s). */
    private static final double CARRY = 0.3;
    /** C-5 airdrop speed, blocks/tick (50 m/s). */
    private static final double FLIGHT = 2.5;
    /** Stone + forced chunks past the pad front, blocks: a flight-speed drop lands well ahead. */
    private static final int RUNWAY = 220;

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 600)
    public static void aHoldStowsAndUnloadsAVehicle(GameTestHelper helper) {
        stowAndUnload(helper, 0, "c5", "humvee", new Vec3(0, FLOOR, -10));
    }

    /** Drive-in load (ca_c5_load): a 65 t tank fits the bay and stows. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 600)
    public static void aGalaxyStowsAndUnloadsAMerkava(GameTestHelper helper) {
        stowAndUnload(helper, 6, "c5", "merkava", new Vec3(0, FLOOR, -10));
    }

    /** Ramp hold: humvee parked on the sloped cabin floor. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 600)
    public static void aChinookStowsAndUnloadsAHumvee(GameTestHelper helper) {
        stowAndUnload(helper, 5, "ch47", "humvee", new Vec3(0, 1.6, -6.5));
    }

    private static void stowAndUnload(GameTestHelper helper, int index, String carrierId, String cargoId, Vec3 parking) {
        Lane lane = new Lane(helper, index);
        AbstractVehicle[] carrier = new AbstractVehicle[1];
        AbstractVehicle[] cargo = new AbstractVehicle[1];
        UUID[] id = new UUID[1];
        Vec3[] local = new Vec3[1];
        float[] before = new float[3];
        lane.ready()
                .thenExecute(() -> carrier[0] = lane.spawn(carrierId, Vec3.ZERO))
                .thenIdle(40)
                .thenExecute(() -> doors(carrier[0]).setOn(true))
                .thenIdle(60)
                .thenExecute(() -> cargo[0] = lane.spawnIn(carrier[0], cargoId, parking))
                .thenIdle(60)
                .thenExecute(() -> {
                    id[0] = cargo[0].getUUID();
                    local[0] = cargo[0].position().subtract(carrier[0].position());
                    before[0] = cargo[0].getHealth();
                    before[1] = carrier[0].physicsEngine.mass;
                    before[2] = cargo[0].physicsEngine.mass;
                    lane.check(carrier[0].cargoHold().mayClose(doors(carrier[0]), null), "closing refused with a parked " + cargoId + " in the bay");
                    doors(carrier[0]).setOn(false);
                })
                .thenIdle(2)
                .thenExecute(() -> {
                    lane.check(cargo[0].isRemoved() && lane.level.getEntity(id[0]) == null, cargoId + " still in the world after stowing");
                    lane.check(Math.abs(carrier[0].physicsEngine.mass - before[1] - before[2]) < 1, String.format(Locale.ROOT,
                            "carrier mass %.0f, expected %.0f + %.0f", carrier[0].physicsEngine.mass, before[1], before[2]));
                    CompoundTag saved = new CompoundTag();
                    carrier[0].saveWithoutId(saved);
                    AbstractVehicle copy = (AbstractVehicle) carrier[0].getType().create(lane.level);
                    copy.load(saved);
                    lane.check(copy.cargoHold() != null && Math.abs(copy.cargoHold().mass() - before[2]) < 1,
                            "stowed " + cargoId + " lost in a save round trip");
                    copy.discard();
                    doors(carrier[0]).setOn(true);
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    AbstractVehicle back = lane.level.getEntity(id[0]) instanceof AbstractVehicle v ? v : null;
                    lane.check(back != null, cargoId + " not unloaded with the doors open on the ground");
                    Vec3 drift = back.position().subtract(carrier[0].position()).subtract(local[0]);
                    lane.check(drift.length() < 0.1, "unloaded " + drift.length() + " from its stowed pose");
                    lane.check(back.getHealth() == before[0], "health " + back.getHealth() + " != " + before[0]);
                    lane.check(carrier[0].cargoHold().isEmpty(), "hold not empty after unloading");
                })
                .thenExecute(lane::close)
                .thenSucceed();
    }

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 400)
    public static void anOccupiedVehicleKeepsTheHoldOpen(GameTestHelper helper) {
        Lane lane = new Lane(helper, 1);
        AbstractVehicle[] carrier = new AbstractVehicle[1];
        AbstractVehicle[] humvee = new AbstractVehicle[1];
        lane.ready()
                .thenExecute(() -> carrier[0] = lane.spawn("c5", Vec3.ZERO))
                .thenIdle(40)
                .thenExecute(() -> doors(carrier[0]).setOn(true))
                .thenIdle(60)
                .thenExecute(() -> humvee[0] = lane.spawnIn(carrier[0], "humvee", new Vec3(0, FLOOR, -10)))
                .thenIdle(40)
                .thenExecute(() -> {
                    ArmorStand rider = new ArmorStand(EntityType.ARMOR_STAND, lane.level);
                    rider.setPos(humvee[0].position());
                    lane.level.addFreshEntity(rider);
                    lane.check(rider.startRiding(humvee[0], true), "armour stand would not board");
                    lane.check(!carrier[0].cargoHold().mayClose(doors(carrier[0]), null), "hold closable over an occupied vehicle");
                    rider.stopRiding();
                    rider.discard();
                    lane.check(carrier[0].cargoHold().mayClose(doors(carrier[0]), null), "hold still refused once empty");
                })
                .thenExecute(lane::close)
                .thenSucceed();
    }

    /** Pinned in the air, doors open: the chuted humvee leaves and lands slowly; the rearmost, unchuted, stays. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 900)
    public static void anAirdropLeavesOnlyWithAParachute(GameTestHelper helper) {
        Lane lane = new Lane(helper, 2);
        AbstractVehicle[] carrier = new AbstractVehicle[1];
        Vec3[] pinned = new Vec3[1];
        AbstractVehicle[] cargo = new AbstractVehicle[2];
        UUID[] chuted = new UUID[1];
        float[] health = new float[1];
        List<String> trace = new ArrayList<>();
        double[] peakFall = new double[1];
        boolean[] opened = new boolean[1];
        var sequence = lane.ready()
                .thenExecute(() -> {
                    carrier[0] = lane.spawn("c5", new Vec3(0, 40, 0));
                    pinned[0] = carrier[0].position();
                    doors(carrier[0]).setOn(true);
                })
                .thenExecuteFor(60, () -> pin(carrier[0], pinned[0]))
                .thenExecute(() -> {
                    cargo[0] = lane.spawnIn(carrier[0], "humvee", new Vec3(0, FLOOR, -8));
                    cargo[1] = lane.spawnIn(carrier[0], "humvee", new Vec3(0, FLOOR, -24));
                    cargo[0].parachute().setFitted(true);
                    chuted[0] = cargo[0].getUUID();
                    health[0] = cargo[0].getHealth();
                })
                .thenExecuteFor(60, () -> pin(carrier[0], pinned[0]))
                .thenExecute(() -> {
                    lane.check(carrier[0].cargoHold().mayClose(doors(carrier[0]), null), "hold refused two parked humvees");
                    doors(carrier[0]).setOn(false);
                })
                .thenExecuteFor(5, () -> pin(carrier[0], pinned[0]))
                .thenExecute(() -> {
                    lane.check(cargo[0].isRemoved() && cargo[1].isRemoved(), "both humvees should be stowed");
                    doors(carrier[0]).setOn(true);
                })
                .thenExecuteFor(400, () -> {
                    pin(carrier[0], pinned[0]);
                    if (lane.level.getEntity(chuted[0]) instanceof AbstractVehicle dropped) {
                        opened[0] |= dropped.parachuteDeployed();
                        if (!dropped.onGround()) {
                            peakFall[0] = Math.max(peakFall[0], -dropped.getDeltaMovement().y);
                        }
                        trace.add(String.format(Locale.ROOT, "y%.1f vy%.2f %s", dropped.getY() - lane.floorY,
                                dropped.getDeltaMovement().y, dropped.parachuteDeployed() ? "C" : "-"));
                    }
                });
        sequence.thenExecute(() -> {
            AbstractVehicle dropped = lane.level.getEntity(chuted[0]) instanceof AbstractVehicle v ? v : null;
            lane.check(dropped != null, "chuted humvee never left the hold");
            String report = String.format(Locale.ROOT, "[airdrop] opened %s, ground %s, health %.1f -> %.1f, hold %.0f kg; %s",
                    opened[0], dropped.onGround(), health[0], dropped.getHealth(), carrier[0].cargoHold().mass(),
                    String.join(" ", trace.subList(Math.max(0, trace.size() - 60), trace.size())));
            CombinedAssault.LOGGER.info(report);
            lane.check(opened[0], "canopy never opened; " + report);
            lane.check(dropped.onGround(), "not down after 400 ticks; " + report);
            lane.check(!dropped.parachute().fitted(), "chute not spent on landing; " + report);
            lane.check(dropped.getHealth() >= health[0] * 0.99f, "hurt under canopy; " + report);
            lane.check(Math.abs(carrier[0].cargoHold().mass() - cargo[1].physicsEngine.mass) < 1, "unchuted humvee left the aircraft; " + report);
        }).thenExecute(lane::close).thenSucceed();
    }

    /**
     * Carrier driven along +Z at {@link #FLIGHT} (pose set each tick), doors opened in flight: the chuted humvee
     * leaves at the carrier's speed, the canopy opens and bleeds it, it lands ahead of the exit point unhurt.
     */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 900)
    public static void anAirdropAtFlightSpeedLandsUnhurt(GameTestHelper helper) {
        Lane lane = new Lane(helper, 7);
        lane.runway(true);
        AbstractVehicle[] carrier = new AbstractVehicle[1];
        Vec3[] at = new Vec3[1];
        UUID[] chuted = new UUID[1];
        float[] health = new float[1];
        Vec3[] exit = new Vec3[1];
        double[] exitSpeed = new double[1];
        boolean[] opened = new boolean[1];
        List<String> trace = new ArrayList<>();
        Runnable fly = () -> {
            at[0] = at[0].add(0, 0, FLIGHT);
            carrier[0].setPos(at[0]);
            carrier[0].setDeltaMovement(new Vec3(0, 0, FLIGHT));
        };
        var sequence = lane.ready()
                .thenExecute(() -> {
                    carrier[0] = lane.spawn("c5", new Vec3(0, 40, -PAD_BACK + 20));
                    at[0] = carrier[0].position();
                    doors(carrier[0]).setOn(true);
                })
                .thenExecuteFor(40, () -> pin(carrier[0], at[0]))
                .thenExecute(() -> {
                    AbstractVehicle humvee = lane.spawnIn(carrier[0], "humvee", new Vec3(0, FLOOR, -8));
                    humvee.parachute().setFitted(true);
                    chuted[0] = humvee.getUUID();
                    health[0] = humvee.getHealth();
                })
                .thenExecuteFor(60, () -> pin(carrier[0], at[0]))
                .thenExecute(() -> doors(carrier[0]).setOn(false))
                .thenExecuteFor(5, () -> pin(carrier[0], at[0]))
                .thenExecute(() -> lane.check(carrier[0].cargoHold().mass() > 0, "humvee not stowed"))
                .thenExecuteFor(10, fly)
                .thenExecute(() -> doors(carrier[0]).setOn(true))
                .thenExecuteFor(400, () -> {
                    if (carrier[0].getZ() < lane.centre.getZ() + RUNWAY - 40) {
                        fly.run();
                    } else if (!carrier[0].isRemoved()) {
                        carrier[0].discard();
                    }
                    if (lane.level.getEntity(chuted[0]) instanceof AbstractVehicle dropped) {
                        if (exit[0] == null) {
                            exit[0] = dropped.position();
                            exitSpeed[0] = dropped.getDeltaMovement().horizontalDistance();
                        }
                        opened[0] |= dropped.parachuteDeployed();
                        trace.add(String.format(Locale.ROOT, "y%.1f z%.1f vz%.2f %s", dropped.getY() - lane.floorY,
                                dropped.getZ() - lane.centre.getZ(), dropped.getDeltaMovement().z,
                                dropped.parachuteDeployed() ? "C" : "-"));
                    }
                });
        sequence.thenExecute(() -> {
            AbstractVehicle dropped = lane.level.getEntity(chuted[0]) instanceof AbstractVehicle v ? v : null;
            lane.check(dropped != null && exit[0] != null, "chuted humvee never left the hold");
            double carried = dropped.getZ() - exit[0].z;
            String report = String.format(Locale.ROOT, "[airdrop flight] exit speed %.2f b/t, carried %.1f, opened %s,"
                            + " ground %s, health %.1f -> %.1f; %s", exitSpeed[0], carried, opened[0], dropped.onGround(),
                    health[0], dropped.getHealth(), String.join(" ", trace.subList(0, Math.min(40, trace.size()))));
            CombinedAssault.LOGGER.info(report);
            lane.check(exitSpeed[0] > FLIGHT * 0.8, "left slower than the carrier; " + report);
            lane.check(opened[0], "canopy never opened; " + report);
            lane.check(dropped.onGround(), "not down; " + report);
            lane.check(carried > 5, "no forward carry; " + report);
            lane.check(dropped.getHealth() >= health[0] * 0.99f, "hurt; " + report);
        }).thenExecute(() -> lane.runway(false)).thenExecute(lane::close).thenSucceed();
    }

    /** Same 40-block fall: free => hurt; under canopy => unhurt. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 600)
    public static void aHardLandingHurtsAndAParachuteDoesNot(GameTestHelper helper) {
        Lane lane = new Lane(helper, 3);
        AbstractVehicle[] pair = new AbstractVehicle[2];
        float[] start = new float[2];
        double[] peak = new double[2];
        lane.ready().thenExecute(() -> {
            pair[0] = lane.spawn("humvee", new Vec3(-6, 40, 0));
            pair[1] = lane.spawn("humvee", new Vec3(6, 40, 0));
            pair[1].parachute().setFitted(true);
            start[0] = pair[0].getHealth();
            start[1] = pair[1].getHealth();
        }).thenExecuteFor(300, () -> {
            AbstractVehicle free = pair[0], chuted = pair[1];
            peak[0] = Math.max(peak[0], -free.getDeltaMovement().y);
            peak[1] = Math.max(peak[1], chuted.parachuteDeployed() ? -chuted.getDeltaMovement().y : 0);
        }).thenExecute(() -> {
            AbstractVehicle free = pair[0], chuted = pair[1];
            String report = String.format(Locale.ROOT,
                    "[hard landing] free %.1f -> %.1f (peak %.2f b/t); chuted %.1f -> %.1f (canopy peak %.2f b/t, terminal %.2f)",
                    start[0], free.getHealth(), peak[0], start[1], chuted.getHealth(), peak[1], VehicleParachute.TERMINAL);
            CombinedAssault.LOGGER.info(report);
            lane.check((free.isDestroyed() || free.onGround()) && chuted.onGround(), "not down; " + report);
            lane.check(free.isDestroyed() || free.getHealth() < start[0] * 0.9f, "free fall barely hurt; " + report);
            lane.check(chuted.getHealth() >= start[1] * 0.99f, "hurt under canopy; " + report);
        }).thenExecute(lane::close).thenSucceed();
    }

    /** Pinned MH-60L hook: humvee lifted clear, hangs within the sling, carried sideways, swing damps; released => falls. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 800)
    public static void aHelicopterLiftsASlungHumvee(GameTestHelper helper) {
        Lane lane = new Lane(helper, 4);
        AbstractVehicle[] pair = new AbstractVehicle[2];
        Vec3[] hover = new Vec3[1];
        double[] hung = new double[3];
        lane.ready()
                .thenExecute(() -> {
                    pair[1] = lane.spawn("humvee", Vec3.ZERO);
                    pair[0] = lane.spawn("mh60l", new Vec3(0, 8, 0));
                    hover[0] = pair[0].position();
                })
                .thenExecuteFor(40, () -> pin(pair[0], hover[0]))
                .thenExecute(() -> {
                    HitchInfo hook = pair[0].towing.hitch("cargo_hook");
                    lane.check(hook != null, "mh60l has no cargo hook");
                    TowLinks.Refusal refusal = TowLinks.check(pair[0], pair[1], hook, TowMode.SLING, true);
                    lane.check(refusal == TowLinks.Refusal.NONE, "sling refused: " + refusal + ", hook "
                            + TowLinks.hitchPoint(pair[0], hook).subtract(lane.centre.getX(), lane.floorY, lane.centre.getZ())
                            + ", eye " + TowLinks.attachPoint(pair[1], TowMode.SLING).subtract(lane.centre.getX(), lane.floorY, lane.centre.getZ()));
                    lane.check(TowLinks.couple(pair[0], pair[1], hook, TowMode.SLING), "sling not coupled");
                })
                .thenExecuteFor(60, () -> {
                    hover[0] = hover[0].add(0, 0.25, 0);
                    pin(pair[0], hover[0]);
                })
                .thenExecuteFor(40, () -> pin(pair[0], hover[0]))
                .thenExecute(() -> {
                    hung[0] = pair[1].getY() - lane.floorY;
                    hung[1] = TowLinks.hitchPoint(pair[0], pair[0].towing.hitch("cargo_hook"))
                            .distanceTo(TowLinks.attachPoint(pair[1], TowMode.SLING));
                })
                .thenExecuteFor(40, () -> {
                    hover[0] = hover[0].add(CARRY, 0, 0);
                    pin(pair[0], hover[0]);
                    pair[0].setDeltaMovement(CARRY, 0, 0);
                })
                .thenExecuteFor(160, () -> {
                    pin(pair[0], hover[0]);
                    hung[2] = Math.max(hung[2], Math.abs(pair[1].getX() - pair[0].getX()));
                })
                .thenExecute(() -> {
                    double settled = Math.abs(pair[1].getX() - pair[0].getX());
                    String report = String.format(Locale.ROOT, "[sling] lifted to %.2f above the pad, rope %.2f (8), swing after stop %.2f, settled %.2f",
                            hung[0], hung[1], hung[2], settled);
                    CombinedAssault.LOGGER.info(report);
                    lane.check(hung[0] > 3 && !pair[1].onGround(), "not lifted; " + report);
                    lane.check(hung[1] < 8.3, "rope stretched; " + report);
                    lane.check(hung[2] < 6 && settled < 1.5, "load swing undamped; " + report);
                    lane.check(TowLinks.uncouple(pair[1]), "release failed");
                })
                .thenExecuteFor(120, () -> pin(pair[0], hover[0]))
                .thenExecute(() -> lane.check(pair[1].onGround(), "released load still in the air"))
                .thenExecute(lane::close)
                .thenSucceed();
    }

    // ---------------------------------------------------------------- lane

    private static void pin(AbstractVehicle vehicle, Vec3 at) {
        vehicle.setPos(at);
        vehicle.setDeltaMovement(Vec3.ZERO);
    }

    private static SwitchableUnit<?> doors(AbstractVehicle carrier) {
        for (PartUnit<?> part : carrier.getPartUnits()) {
            if (part instanceof SwitchableUnit<?> door && carrier.cargoHold() != null && carrier.cargoHold().isDoor(part)) {
                return door;
            }
        }
        throw new IllegalStateException(carrier.getVehicleId() + " has no cargo door");
    }

    private static final class Lane {

        final GameTestHelper helper;
        final ServerLevel level;
        final BlockPos centre;
        final double floorY;
        final List<AbstractVehicle> spawned = new ArrayList<>();

        Lane(GameTestHelper helper, int index) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.centre = helper.absolutePos(BlockPos.ZERO).offset(0, 0, LANE_Z + index * LANE_PITCH);
            this.floorY = centre.getY() + 1;
            pad(Blocks.STONE.defaultBlockState(), true);
        }

        net.minecraft.gametest.framework.GameTestSequence ready() {
            return helper.startSequence().thenWaitUntil(() -> helper.assertTrue(level.isPositionEntityTicking(centre), "lane not ticking yet"));
        }

        AbstractVehicle spawn(String name, Vec3 offset) {
            AbstractVehicle vehicle = CommonAssetsManager.vehicleDataManager().getVehicleData(CombinedAssault.modLocation(name))
                    .orElseThrow().construct(level, new Vec3(centre.getX() + 0.5, floorY, centre.getZ() + 0.5).add(offset), 0, 0);
            vehicle.setDisplayId(CombinedAssault.modLocation(name));
            level.addFreshEntity(vehicle);
            spawned.add(vehicle);
            return vehicle;
        }

        /** {@code local}: carrier model frame; carrier yaw 0 => world offset. */
        AbstractVehicle spawnIn(AbstractVehicle carrier, String name, Vec3 local) {
            Vec3 at = carrier.position().add(local);
            return spawn(name, at.subtract(centre.getX() + 0.5, floorY, centre.getZ() + 0.5));
        }

        void check(boolean ok, String failure) {
            if (!ok) {
                close();
                helper.fail(failure);
            }
        }

        void close() {
            for (AbstractVehicle vehicle : spawned) {
                if (!vehicle.isRemoved()) {
                    vehicle.discard();
                }
            }
            for (AbstractVehicle vehicle : level.getEntitiesOfClass(AbstractVehicle.class,
                    new net.minecraft.world.phys.AABB(centre).inflate(PAD_BACK, 80, PAD_BACK))) {
                vehicle.discard();
            }
            pad(Blocks.AIR.defaultBlockState(), false);
        }

        /** Strip {@code PAD_FRONT..RUNWAY} ahead, x within 12: placed + forced, or cleared + released. */
        void runway(boolean on) {
            BlockState state = (on ? Blocks.STONE : Blocks.AIR).defaultBlockState();
            for (int x = -12; x <= 12; x++) {
                for (int z = PAD_FRONT + 1; z <= RUNWAY; z++) {
                    level.setBlockAndUpdate(centre.offset(x, 0, z), state);
                }
            }
            for (int cx = (centre.getX() - 12) >> 4; cx <= (centre.getX() + 12) >> 4; cx++) {
                for (int cz = (centre.getZ() + PAD_FRONT + 1) >> 4; cz <= (centre.getZ() + RUNWAY) >> 4; cz++) {
                    ForcedChunks.set(level, cx, cz, on);
                }
            }
        }

        private void pad(BlockState state, boolean force) {
            for (int x = -PAD_X; x <= PAD_X; x++) {
                for (int z = -PAD_BACK; z <= PAD_FRONT; z++) {
                    level.setBlockAndUpdate(centre.offset(x, 0, z), state);
                }
            }
            for (int cx = (centre.getX() - PAD_X) >> 4; cx <= (centre.getX() + PAD_X) >> 4; cx++) {
                for (int cz = (centre.getZ() - PAD_BACK) >> 4; cz <= (centre.getZ() + PAD_FRONT) >> 4; cz++) {
                    ForcedChunks.set(level, cx, cz, force);
                }
            }
        }

    }

}
