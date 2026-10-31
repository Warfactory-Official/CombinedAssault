package com.wf.combinedassault.indirect;

/**
 * @param elevationDegrees above horizon, positive up: opposite sign to Minecraft's xRot
 * @param azimuthDegrees   world yaw per {@link com.wf.combinedassault.vehicle.util.VectorUtil#rotToVec}: 0 = +Z
 * @param flightTicks      muzzle to target
 * @param apexHeight       blocks above muzzle at arc top
 * @param impactSpeed      b/t on arrival; what the armour model sees
 * @param range            horizontal distance solved for
 */
public record FireSolution(double elevationDegrees,
                           double azimuthDegrees,
                           double flightTicks,
                           double apexHeight,
                           double impactSpeed,
                           double range,
                           FireArc arc) {

    public double flightSeconds() {
        return flightTicks / 20.0;
    }

    public FireSolution withAzimuth(double azimuth) {
        return new FireSolution(elevationDegrees, azimuth, flightTicks, apexHeight, impactSpeed, range, arc);
    }

}
