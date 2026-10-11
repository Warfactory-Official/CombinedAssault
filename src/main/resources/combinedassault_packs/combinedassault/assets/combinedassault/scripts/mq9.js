// Pivots: tools/blender/export_mq9.py. Ailerons turn about vehicle X; ruddervator mounts put their hinge on local X.
var PROP_DEGREES_PER_TICK = 48;
var SURFACE_DEGREES = 20;

function updateBones(context) {
    var builder = createPoseBuilder();
    var tick = context.tickCount() + context.getPartialTick();
    var spin = context.getEngineSpeed() > 0 ? (tick * PROP_DEGREES_PER_TICK * context.getThrottleLevel() / 100) % 360 : 0;
    builder.setRotation("propeller", 0, 0, spin);

    var roll = context.getRollInput() * SURFACE_DEGREES;
    builder.setRotation("left_aileron", roll, 0, 0);
    builder.setRotation("right_aileron", -roll, 0, 0);
    // V-tail: pitch deflects both together, yaw differentially.
    var pitch = -context.getPitchInput() * SURFACE_DEGREES;
    var yaw = context.getYawInput() * SURFACE_DEGREES;
    builder.setRotation("left_ruddervator", pitch + yaw, 0, 0);
    builder.setRotation("right_ruddervator", pitch - yaw, 0, 0);

    context.setBoneVisible("gear", !context.isPartOn("landing_gear")); // on == gear up
    return builder;
}
