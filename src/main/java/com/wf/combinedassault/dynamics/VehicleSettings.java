package com.wf.combinedassault.dynamics;

/** Vehicle-side config snapshot; engine keys live in wflib {@code PhysicsSettings}. */
public record VehicleSettings(boolean restingVehicleSleep, boolean deckHarness, boolean vehicleMounts,
                              boolean predictDriving, boolean canDestroyBlock, double ramBreakMomentum, double rightingEffortPerPlayer) {

    public static final VehicleSettings DEFAULTS = new VehicleSettings(true, true, true, true, true, 125,
            30000.0);

    private static volatile VehicleSettings configured = DEFAULTS;

    public static VehicleSettings current() {
        return configured;
    }

    public static void configure(VehicleSettings settings) {
        configured = settings;
    }
}
