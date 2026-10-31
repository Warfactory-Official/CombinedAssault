package com.wf.combinedassault.uav.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.gametest.TestLanes;
import com.wf.wflib.grenade.api.ChargeSpec;
import com.wf.wflib.drone.api.DroneNetBlock;
import com.wf.wflib.cam.api.Datalink;
import com.wf.wflib.grenade.api.GrenadeWarheads;
import com.wf.wflib.grenade.api.ThrownCharge;
import com.wf.wflib.recon.api.Band;
import com.wf.wflib.recon.api.ContactClass;
import com.wf.wflib.recon.api.ReconTargets;
import com.wf.wflib.recon.api.Signature;
import com.wf.wflib.recon.api.SourceIds;
import com.wf.wflib.recon.api.SensorSpec;
import com.wf.wflib.recon.api.Atmosphere;
import com.wf.wflib.recon.api.Interference;
import com.wf.wflib.recon.api.SeismicEvents;
import com.wf.wflib.recon.api.SensorSnapshot;
import com.wf.wflib.recon.api.TargetSnapshot;
import com.wf.wflib.warhead.api.EMPWarhead;
import com.wf.wflib.warhead.api.WarheadCarrier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import com.wf.wflib.drone.api.DroneContent;
import com.wf.wflib.recon.api.ReconContent;

/** Attacker counterplay against drones and the passive picture: net, EMP, jammer, thermite glare, blast noise. */
@PrefixGameTestTemplate(false)
public class CounterplayGameTest {
    private static final String TEMPLATE = "empty";
    private static final ResourceLocation MAVIC = CombinedAssault.modLocation("mavic");
    /** Lanes: thermite fire, EMP r48 and jammer r32 reach neighbouring arenas. */
    private static final int THERMITE_LANE = -2700, EMP_LANE = -2800, JAMMER_LANE = -2900;

    private static AbstractVehicle hoveringMavic(GameTestHelper helper, Vec3 at) {
        return powered(TestLanes.spawn(helper, MAVIC, at));
    }

    private static AbstractVehicle powered(AbstractVehicle quad) {
        quad.setEnergy(quad.energyInfo.capacityUnits());
        quad.toggleEngine(true);
        quad.setPower(100);
        quad.controlUnit.yRotKeep = true;
        return quad;
    }

    private static ArmorStand pilot(GameTestHelper helper, AbstractVehicle quad) {
        ArmorStand pilot = new ArmorStand(helper.getLevel(), quad.getX(), quad.getY(), quad.getZ());
        helper.getLevel().addFreshEntity(pilot);
        helper.assertTrue(pilot.startRiding(quad, true), "pilot could not board");
        quad.setDetachedBodyAnchor(pilot, quad.position());
        return pilot;
    }

    /** Net wall across a Mavic's path: pinned in the strands; a stand's collision there is empty. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void aDroneNetHoldsAMavicNotAWalker(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos base = helper.absolutePos(new BlockPos(1, 30, 1));
        int netZ = base.getZ() + 5;
        for (int x = -2; x <= 2; x++) {
            for (int y = -2; y <= 2; y++) {
                level.setBlockAndUpdate(new BlockPos(base.getX() + x, base.getY() + y, netZ),
                        DroneContent.DRONE_NET.get().defaultBlockState());
            }
        }
        AbstractVehicle quad = hoveringMavic(helper, Vec3.atBottomCenterOf(base));
        ArmorStand walker = new ArmorStand(level, base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
        level.addFreshEntity(walker);
        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    BlockPos net = new BlockPos(base.getX(), base.getY(), netZ);
                    var state = level.getBlockState(net);
                    helper.assertTrue(state.getCollisionShape(level, net, CollisionContext.of(walker)).isEmpty(),
                            "net blocks a walker");
                    quad.controlUnit.forward = true;
                })
                .thenIdle(60)
                .thenExecute(() -> {
                    double z = quad.getZ();
                    boolean caught = DroneNetBlock.caught(level, quad);
                    for (int x = -2; x <= 2; x++) {
                        for (int y = -2; y <= 2; y++) {
                            level.setBlockAndUpdate(new BlockPos(base.getX() + x, base.getY() + y, netZ),
                                    Blocks.AIR.defaultBlockState());
                        }
                    }
                    quad.discard();
                    walker.discard();
                    helper.assertTrue(z < netZ + 1.5 && caught, "Mavic at z " + (z - netZ) + " past the net, caught "
                            + caught);
                })
                .thenSucceed();
    }

    /** EMP over a piloted Mavic: link dead, inputs dropped. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 300)
    public static void anEmpCutsAUavLink(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ArmorStand[] pilot = new ArmorStand[1];
        TestLanes.run(helper, EMP_LANE, 4, 4, MAVIC, (seq, v) -> seq
                .thenExecute(() -> pilot[0] = pilot(helper, powered(v.get())))
                .thenIdle(3)
                .thenExecute(() -> {
                    helper.assertFalse(v.get().linkLost(), "link lost before the EMP");
                    EMPWarhead.detonate(carrier(level), v.get().position().add(0, 4, 0));
                })
                .thenIdle(3)
                .thenExecute(() -> {
                    boolean lost = v.get().linkLost();
                    boolean jammed = Datalink.jammed(level, v.get());
                    pilot[0].stopRiding();
                    pilot[0].discard();
                    helper.assertTrue(lost && jammed, "after EMP: link lost " + lost + ", jammed " + jammed);
                }), lane -> {
        });
    }

    /** A switched-on jammer carried beside the pilot's body: the link drops. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 300)
    public static void aCarriedJammerCutsTheLink(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ItemStack jammer = new ItemStack(ReconContent.PORTABLE_JAMMER.get());
        jammer.set(ReconContent.ACTIVE.get(), true);
        ArmorStand[] pilot = new ArmorStand[1];
        TestLanes.run(helper, JAMMER_LANE, 4, 4, MAVIC, (seq, v) -> seq
                .thenExecute(() -> pilot[0] = pilot(helper, powered(v.get())))
                .thenIdle(3)
                .thenExecute(() -> helper.assertFalse(v.get().linkLost(), "link lost before jamming"))
                .thenExecute(() -> jammer.getItem().inventoryTick(jammer, level, pilot[0], 0, false))
                .thenIdle(1)
                .thenExecute(() -> {
                    boolean lost = v.get().linkLost();
                    float link = v.get().link();
                    pilot[0].stopRiding();
                    pilot[0].discard();
                    helper.assertTrue(lost, "jammed link " + link);
                }), lane -> {
        });
    }

    /** Burning thermite: targets beside it present x glare on thermal; the burn itself is a heat contact. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 300)
    public static void thermiteGlaresAndShowsAHeatContact(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ThrownCharge[] charge = new ThrownCharge[1];
        Vec3[] at = new Vec3[1];
        TestLanes.run(helper, THERMITE_LANE, 4, 4, MAVIC, (seq, v) -> seq
                .thenExecute(() -> {
                    at[0] = v.get().position().add(3, 0, 0);
                    charge[0] = ThrownCharge.react(level, at[0], ItemStack.EMPTY,
                            new ChargeSpec(GrenadeWarheads.THERMITE, 0, 0.1f, false, false, 0, 0, 0),
                            new Vec3(0, 1, 0), null, null, false);
                })
                .thenIdle(25)
                .thenExecute(() -> {
                    float glare = Interference.factorAt(level, Band.THERMAL, at[0].add(3, 0, 0));
                    List<TargetSnapshot> picture = ReconTargets.collect(level, new AABB(at[0], at[0]).inflate(16));
                    TargetSnapshot heat = picture.stream().filter(t -> SourceIds.isHeat(t.sourceId())).findFirst()
                            .orElse(null);
                    charge[0].discard();
                    helper.assertTrue(glare == GrenadeWarheads.THERMITE_GLARE_FACTOR, "glare factor " + glare);
                    helper.assertTrue(heat != null && heat.kind() == ContactClass.UNKNOWN
                                    && heat.signature().thermal() == GrenadeWarheads.THERMITE_SIGNATURE,
                            "heat contact " + heat);
                }), lane -> {
        });
    }

    /** A blast rings the ground: a geophone near it hears a far target x NOISE_FACTOR; one far away is unaffected. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aBlastDeafensNearbyGeophones(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 blast = helper.absoluteVec(new Vec3(2.5, 1, 2.5));
        helper.runAfterDelay(1, () -> {
            SeismicEvents.report(level, blast.x, blast.y, blast.z, SeismicEvents.TNT_POWER);
            TargetSnapshot target = TargetSnapshot.of(1L, ContactClass.VEHICLE, blast.x + 100, blast.y, blast.z,
                    0.1, 0, 0, new Signature(0, 1.0f, 0, 0, 0), 0L, false);
            TargetSnapshot presented = Interference.apply(Interference.live(level), target);
            SensorSpec spec = SensorSpec.seismicArray(1L, 128);
            long now = level.getGameTime();
            float near = presented.presentedTo(new SensorSnapshot(1L, blast.x + 10, blast.y, blast.z, spec, 0,
                    Atmosphere.STANDARD, now)).seismicEnergy();
            float far = presented.presentedTo(new SensorSnapshot(2L, blast.x + 300, blast.y, blast.z, spec, 0,
                    Atmosphere.STANDARD, now)).seismicEnergy();
            helper.assertTrue(near == SeismicEvents.NOISE_FACTOR, "geophone by the blast hears x" + near);
            helper.assertTrue(far == 1.0f, "distant geophone hears x" + far);
            helper.succeed();
        });
    }

    private static WarheadCarrier carrier(Level level) {
        return new WarheadCarrier() {
            @Override
            public Level level() {
                return level;
            }

            @Override
            public int getFragmentCount() {
                return 0;
            }

            @Override
            public Vec3 angle() {
                return new Vec3(0, -1, 0);
            }
        };
    }
}
