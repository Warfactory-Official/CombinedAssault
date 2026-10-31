package com.wf.combinedassault.naval.trial;

import net.minecraft.util.Mth;
import com.wf.combinedassault.naval.ShipVehicle;
import com.wf.combinedassault.naval.trial.TrialCourse.Mark;

import java.util.List;

/** Pure-pursuit helm on a fixed centreline: deterministic, so two runs differ only by code under test. */
public final class TrialPilot {

    /** Lookahead, hull lengths. Shorter weaves; longer cuts corners. */
    private static final double LOOKAHEAD_HULLS = 1.5;
    private static final double RUDDER_BAND = 20.0;
    /** Beyond this bearing error, throttle eases: a hard turn should not also carry full speed onto the bank. */
    private static final double EASE_BEARING = 45.0;
    private static final double EASED_THROTTLE = 0.4;
    private static final double STOP_HULLS = 0.75;

    private final List<Mark> marks;
    private final double hullLength;
    private final double halfBeam;
    private int leg = 1;
    private boolean finished;

    private double crossTrack;
    private double margin;
    private double bearingError;

    public TrialPilot(List<Mark> marks, double hullLength, double beam) {
        this.marks = marks;
        this.hullLength = hullLength;
        this.halfBeam = beam * 0.5;
    }

    public int leg() {
        return leg;
    }

    public String legName() {
        return marks.get(Math.min(leg, marks.size() - 1)).leg();
    }

    /** Signed offset from the channel centreline, positive to starboard of it. */
    public double crossTrack() {
        return crossTrack;
    }

    /** Hull-edge-to-bank clearance, blocks; negative => in the bank. */
    public double margin() {
        return margin;
    }

    public double bearingError() {
        return bearingError;
    }

    public boolean finished() {
        return finished;
    }

    public void tick(ShipVehicle ship) {
        Mark from = marks.get(leg - 1);
        Mark to = marks.get(leg);
        double dx = to.x() - from.x();
        double dz = to.z() - from.z();
        double legLength = Math.hypot(dx, dz);
        double ux = dx / legLength;
        double uz = dz / legLength;

        double px = ship.getX() - from.x();
        double pz = ship.getZ() - from.z();
        double along = px * ux + pz * uz;
        // Negated: leg normal vs hull's port/starboard convention are opposite senses.
        crossTrack = -(px * uz - pz * ux);

        // Projects the hull's footprint onto the channel normal at this heading: margin is the
        // hull's edge, not the centre point's.
        double heading = Math.toRadians(Mth.wrapDegrees(ship.getYRot() - (float) to.heading()));
        double half = halfBeam * Math.abs(Math.cos(heading))
                + hullLength * 0.5 * Math.abs(Math.sin(heading));
        margin = to.halfWidth() - Math.abs(crossTrack) - half;

        if (along >= legLength && leg < marks.size() - 1) {
            leg++;
            tick(ship);
            return;
        }

        if (leg == marks.size() - 1 && legLength - along < STOP_HULLS * hullLength) {
            ship.setHelm(0, 0);
            finished = true;
            return;
        }
        // Lead crosses into following legs: clamped to this leg's end, it would aim the bow at the
        // bank on a corner tighter than the turning circle.
        double lead = along + LOOKAHEAD_HULLS * hullLength;
        double aimX;
        double aimZ;
        int ahead = leg;
        double base = 0;
        while (true) {
            Mark legFrom = marks.get(ahead - 1);
            Mark legTo = marks.get(ahead);
            double lx = legTo.x() - legFrom.x();
            double lz = legTo.z() - legFrom.z();
            double length = Math.hypot(lx, lz);
            if (lead - base <= length || ahead == marks.size() - 1) {
                double t = Math.min(lead - base, length) / length;
                aimX = legFrom.x() + lx * t;
                aimZ = legFrom.z() + lz * t;
                break;
            }
            base += length;
            ahead++;
        }

        double bearing = Math.toDegrees(Math.atan2(-(aimX - ship.getX()), aimZ - ship.getZ()));
        bearingError = Mth.wrapDegrees((float) (bearing - ship.getYRot()));
        double rudder = Mth.clamp(bearingError / RUDDER_BAND, -1.0, 1.0);
        double throttle = Math.abs(bearingError) > EASE_BEARING ? EASED_THROTTLE : 1.0;
        ship.setHelm(throttle, rudder);
    }

}
