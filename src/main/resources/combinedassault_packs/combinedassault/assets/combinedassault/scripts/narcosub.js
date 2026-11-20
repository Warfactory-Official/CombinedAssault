// Model-local frame: bow -X, stern +X. Screw 30 deg/tick at full telegraph; rudder 30 deg at full, + = starboard.
function updateBones(context) {
    var builder = createPoseBuilder();
    builder.setRotation("PROPELLER_SPIN", (context.getShaft() * 30) % 360, 0, 0);
    builder.setRotation("RUDDER_YAW", 0, context.getRudder() * 30, 0);
    return builder;
}
