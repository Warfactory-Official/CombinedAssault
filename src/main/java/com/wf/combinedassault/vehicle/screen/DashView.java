package com.wf.combinedassault.vehicle.screen;

import com.wf.combinedassault.ballistics.component.ComponentKind;
import com.wf.combinedassault.ballistics.component.ComponentState;
import com.wf.combinedassault.ballistics.component.VehicleSystems;
import com.wf.combinedassault.hud.HudSubjects;
import com.wf.combinedassault.vehicle.drive.Transmission;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.entity.GroundVehicle;
import com.wf.wflib.ui.api.Signals;
import com.wf.wflib.ui.api.Subject;

/** Instrument cluster figures of one vehicle ({@code dash} subject), refreshed at most every {@link #REFRESH_NANOS}. */
public final class DashView implements ScreenView {
    public static final Subject<DashView> SUBJECT = Subject.register("dash", DashView.class);
    private static final long REFRESH_NANOS = 40_000_000L;
    private static final double KMH_PER_BLOCK_PER_TICK = 72.0;

    private final AbstractVehicle vehicle;
    private long refreshed = Long.MIN_VALUE / 2;
    double speed;
    double rpm;
    double maxRpm;
    double fuel;
    String gear = "";
    boolean checkEngine;
    boolean checkTires;
    boolean handbrake;
    boolean engineOn;

    public DashView(AbstractVehicle vehicle) {
        this.vehicle = vehicle;
    }

    @Override
    public void refresh(float partialTick) {
        long now = System.nanoTime();
        if (now - refreshed < REFRESH_NANOS) {
            return;
        }
        refreshed = now;
        engineOn = vehicle.isEngineOn();
        float capacity = vehicle.energyInfo.capacityUnits();
        fuel = capacity > 0 ? vehicle.getEnergy() / capacity : 0;
        if (vehicle instanceof GroundVehicle ground) {
            speed = Math.abs(ground.getForwardSpeed()) * KMH_PER_BLOCK_PER_TICK;
            Transmission t = ground.transmission;
            rpm = ground.rpm();
            maxRpm = t == null ? Double.NaN : t.maxRpm;
            gear = t == null ? "" : Transmission.label(ground.getGear());
            handbrake = (ground.getPedals() & GroundVehicle.HANDBRAKE) != 0;
        } else {
            speed = vehicle.getDeltaMovement().length() * KMH_PER_BLOCK_PER_TICK;
            rpm = Double.NaN;
            maxRpm = Double.NaN;
        }
        checkEngine = false;
        checkTires = false;
        for (VehicleSystems.Cell cell : VehicleSystems.cells(vehicle)) {
            boolean worn = cell.state() != ComponentState.PRISTINE;
            if (cell.kind() == ComponentKind.ENGINE) {
                checkEngine |= worn;
            } else if (cell.kind() == ComponentKind.WHEEL) {
                checkTires |= worn;
            }
        }
    }

    @Override
    public Object subject(Subject<?> subject) {
        if (subject == SUBJECT) {
            return this;
        }
        return subject == HudSubjects.VEHICLE ? vehicle : null;
    }

    public static void register() {
        Subject<DashView> s = SUBJECT;
        Signals.num(s, "speed", d -> d.speed);
        Signals.num(s, "rpm", d -> d.rpm);
        Signals.num(s, "max_rpm", d -> d.maxRpm);
        Signals.num(s, "fuel", d -> d.fuel);
        Signals.text(s, "gear", 2, d -> d.gear);
        Signals.bool(s, "check_engine", d -> d.checkEngine);
        Signals.bool(s, "check_tires", d -> d.checkTires);
        Signals.bool(s, "handbrake", d -> d.handbrake);
        Signals.bool(s, "engine_on", d -> d.engineOn);
    }
}
