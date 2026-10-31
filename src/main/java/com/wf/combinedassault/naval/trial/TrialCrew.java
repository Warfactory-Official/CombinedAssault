package com.wf.combinedassault.naval.trial;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.physics.api.DeckSnapshot;
import com.wf.wflib.physics.api.VehicleParenting;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Deck load for a trial: pacing hands + parked vehicles.
 *
 * <p>Hands walk the ordinary mob travel/move/hull-clip path, the same one a player rides, so a
 * deck-walking regression here is real, not a harness artifact. An empty deck costs nothing.
 */
public final class TrialCrew {

    /** Edge margin, capped so a small boat still keeps a beat to walk. */
    private static final double EDGE = 2.0;
    private static final double EDGE_SHARE = 0.2;
    private static final double LANE_SPACING = 4.0;
    private static final int MAX_LANES = 5;
    private static final double STALLED_STEP = 0.02;
    private static final int STALL_TICKS = 5;

    /** One hand: paces fore and aft in its own lane, in the deck's frame. */
    private static final class Hand {
        private final IronGolem mob;
        private final double laneX;
        private final double aftZ;
        private final double foreZ;
        private final double deckY;
        private boolean forward = true;
        private final Vector3d local = new Vector3d();
        private double lastLocalX;
        private double lastLocalZ;
        private boolean paced;
        private int stalled;

        Hand(IronGolem mob, double laneX, double aftZ, double foreZ, double deckY) {
            this.mob = mob;
            this.laneX = laneX;
            this.aftZ = aftZ;
            this.foreZ = foreZ;
            this.deckY = deckY;
        }
    }

    private final List<Hand> hands = new ArrayList<>();
    private final List<AbstractVehicle> parked = new ArrayList<>();
    private final List<Vector3d> parkedAt = new ArrayList<>();
    private final Vector3d scratch = new Vector3d();
    private final Vector3d scratchWorld = new Vector3d();

    /** Mean per-tick hand displacement, deck frame, over hands still aboard. */
    public double pace;
    /** Max per-hand displacement this tick: catches the walk-speed halving from a rider losing its
     *  velocity every tick, which the mean alone hides. */
    public double topPace;
    /** Hands with feet still over the deck. */
    public int aboard;
    /** Max parked-vehicle displacement from its set-down point, hull frame. */
    public double parkedDrift;

    /**
     * @param parkedId vehicle to park; null => none
     * @return false: hull has no deck
     */
    public boolean muster(ServerLevel level, AbstractVehicle carrier, int crew, int vehicles,
                          @Nullable ResourceLocation parkedId) {
        DeckSnapshot deck = carrier.deckSnapshot();
        float[] boxes = deck.deckCount() > 0 ? deck.deckBoxes() : deck.boxes();
        int count = deck.deckCount() > 0 ? deck.deckCount() : deck.count();
        int best = widest(boxes, count);
        if (best < 0) {
            return false;
        }
        int o = best * 6;
        double x0 = boxes[o];
        double top = boxes[o + 4];
        double z0 = boxes[o + 2];
        double x1 = boxes[o + 3];
        double z1 = boxes[o + 5];
        double midX = (x0 + x1) * 0.5;
        double edge = Math.min(EDGE, Math.min(x1 - x0, z1 - z0) * EDGE_SHARE);
        double aft = z0 + edge;
        double fore = z1 - edge;
        double laneSpan = Math.max(0, (x1 - x0) - 2 * edge);
        int lanes = Math.max(1, Math.min(MAX_LANES, (int) (laneSpan / LANE_SPACING) + 1));

        for (int i = 0; i < crew; i++) {
            double lane = lanes == 1 ? midX
                    : x0 + edge + laneSpan * (i % lanes) / (lanes - 1.0);
            double startZ = aft + (fore - aft) * (i + 0.5) / Math.max(1, crew);
            Vec3 at = world(deck, lane, top + 0.1, startZ);
            // IronGolem: not Animal (discarded when spawn-animals=off) nor Monster (discarded on peaceful).
            IronGolem mob = EntityType.IRON_GOLEM.create(level);
            if (mob == null) {
                continue;
            }
            mob.moveTo(at.x, at.y, at.z, carrier.getYRot(), 0);
            mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()),
                    MobSpawnType.COMMAND, null);
            mob.goalSelector.removeAllGoals(goal -> true);
            mob.targetSelector.removeAllGoals(goal -> true);
            mob.setPersistenceRequired();
            mob.setInvulnerable(true);
            if (level.addFreshEntity(mob)) {
                hands.add(new Hand(mob, lane, aft, fore, top));
            }
        }

        if (parkedId != null) {
            Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(parkedId);
            if (data.isPresent()) {
                for (int i = 0; i < vehicles; i++) {
                    double lane = lanes == 1 ? midX
                            : x0 + edge + laneSpan * (i % lanes) / (lanes - 1.0);
                    double spot = aft + (fore - aft) * (i + 0.5) / Math.max(1, vehicles);
                    Vec3 at = world(deck, lane, top + 1.0, spot);
                    AbstractVehicle vehicle = data.get().construct(level, at, 0, carrier.getYRot());
                    vehicle.setDisplayId(parkedId);
                    if (level.addFreshEntity(vehicle)) {
                        vehicle.setEnergy(vehicle.energyInfo.capacityUnits());
                        parked.add(vehicle);
                        // null: real origin captured next tick once it settles; spawned a block clear of the deck.
                        parkedAt.add(null);
                    }
                }
            }
        }
        return !hands.isEmpty() || !parked.isEmpty();
    }

    public void tick(AbstractVehicle carrier) {
        DeckSnapshot deck = carrier.deckSnapshot();
        double paced = 0;
        int pacing = 0;
        aboard = 0;
        topPace = 0;
        for (Hand hand : hands) {
            if (hand.mob.isRemoved()) {
                continue;
            }
            VehicleParenting.toLocal(deck, hand.mob.getX(), hand.mob.getY(), hand.mob.getZ(), hand.local);
            // +-4 blocks of the deck's own y: a level stepped down still counts aboard; the sea doesn't.
            if (hand.local.y > hand.deckY - 4 && hand.local.y < hand.deckY + 4) {
                aboard++;
            }
            if (hand.paced) {
                double dx = hand.local.x - hand.lastLocalX;
                double dz = hand.local.z - hand.lastLocalZ;
                double step = Math.sqrt(dx * dx + dz * dz);
                paced += step;
                topPace = Math.max(topPace, step);
                pacing++;
                hand.stalled = step < STALLED_STEP ? hand.stalled + 1 : 0;
            }
            hand.lastLocalX = hand.local.x;
            hand.lastLocalZ = hand.local.z;
            hand.paced = true;

            // Deck box overruns the real walkable surface (step/coaming/gap); stall => turn about
            // finds the actual beat.
            if (hand.stalled > STALL_TICKS) {
                hand.forward = !hand.forward;
                hand.stalled = 0;
            } else if (hand.forward && hand.local.z > hand.foreZ) {
                hand.forward = false;
            } else if (!hand.forward && hand.local.z < hand.aftZ) {
                hand.forward = true;
            }
            double targetZ = hand.forward ? hand.foreZ : hand.aftZ;
            Vec3 want = world(deck, hand.laneX, hand.deckY + 0.1, targetZ);
            // Re-issued every tick: the deck target moves, and moveControl idles once it arrives.
            hand.mob.getMoveControl().setWantedPosition(want.x, want.y, want.z, 1.0);
        }
        pace = pacing == 0 ? 0 : paced / pacing;

        parkedDrift = 0;
        for (int i = 0; i < parked.size(); i++) {
            AbstractVehicle vehicle = parked.get(i);
            if (vehicle.isRemoved()) {
                continue;
            }
            VehicleParenting.toLocal(deck, vehicle.getX(), vehicle.getY(), vehicle.getZ(), scratch);
            Vector3d origin = parkedAt.get(i);
            if (origin == null) {
                parkedAt.set(i, new Vector3d(scratch));
                continue;
            }
            parkedDrift = Math.max(parkedDrift, scratch.distance(origin));
        }
    }

    public int crewCount() {
        return hands.size();
    }

    public int parkedCount() {
        return parked.size();
    }

    public void dismiss() {
        for (Hand hand : hands) {
            hand.mob.discard();
        }
        for (AbstractVehicle vehicle : parked) {
            vehicle.discard();
        }
        hands.clear();
        parked.clear();
        parkedAt.clear();
    }

    private static int widest(float[] boxes, int count) {
        int best = -1;
        double bestArea = 0;
        for (int i = 0; i < count; i++) {
            int o = i * 6;
            double area = (boxes[o + 3] - boxes[o]) * (boxes[o + 5] - boxes[o + 2]);
            if (area > bestArea) {
                bestArea = area;
                best = i;
            }
        }
        return best;
    }

    private Vec3 world(DeckSnapshot deck, double x, double y, double z) {
        return VehicleParenting.toWorld(deck, scratch.set(x, y, z), scratchWorld);
    }

}
