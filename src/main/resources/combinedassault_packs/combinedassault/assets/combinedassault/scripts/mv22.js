// MCHeli MV-22 (tools/internal/stage_mv22.py): nacelles = body nodes posed by thrust xRot (0 cruise, -90 up); proprotors counter-rotate.
function updateBones(context) {
    var builder = createPoseBuilder();
    var tilt = context.getPartXRot("thrust");
    builder.setRotation("nacelle_l", tilt, 0, 0);
    builder.setRotation("nacelle_r", tilt, 0, 0);
    var step = context.getPower() / 5;
    var sides = [["prop_l", "rotor L", 1], ["prop_r", "rotor R", -1]];
    // Broken proprotor holds its last angle.
    for (var i = 0; i < 2; i++) {
        var key = "spin_" + i;
        var spin = context.getSystemEffectiveness(sides[i][1]) > 0
            ? (context.getFloat(key, 0) + step) % 360 : context.getFloat(key, 0);
        context.setFloat(key, spin);
        builder.setRotation(sides[i][0], 0, 0, sides[i][2] * spin);
    }
    return builder;
}
