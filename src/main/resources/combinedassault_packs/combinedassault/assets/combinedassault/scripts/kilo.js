// Screw 30 deg/tick at full telegraph (~90 rpm); rudders 30 deg at full, + = starboard.
function updateBones(context) {
    var builder = createPoseBuilder();
    builder.setRotation("16_PROPELLER", 0, 0, (context.getShaft() * 30) % 360);
    var rudder = context.getRudder() * 30;
    builder.setRotation("14_LOWER_RUDDER", 0, rudder, 0);
    builder.setRotation("15_UPPER_RUDDER", 0, rudder, 0);
    return builder;
}
