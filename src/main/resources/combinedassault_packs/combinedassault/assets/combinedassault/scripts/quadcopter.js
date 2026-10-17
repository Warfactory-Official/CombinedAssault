// Mavic 3 Pro (tools/internal/stage_mavic.py). Spin sense from the export's rotor extras; gimbal follows the camera part.
var ROTORS = [["13_ROTOR_FL", "rotor FL", -1], ["13_ROTOR_FR", "rotor FR", 1], ["13_ROTOR_RL", "rotor RL", 1],
    ["13_ROTOR_RR", "rotor RR", -1]];

function updateBones(context) {
    var step = context.getPower() / 5;
    var builder = createPoseBuilder();
    // Broken rotor (unit pool) holds its last angle.
    for (var i = 0; i < ROTORS.length; i++) {
        var key = "spin_" + i;
        var spin = context.getSystemEffectiveness(ROTORS[i][1]) > 0
            ? (context.getFloat(key, 0) + step) % 360 : context.getFloat(key, 0);
        context.setFloat(key, spin);
        builder.setRotation(ROTORS[i][0], 0, ROTORS[i][2] * spin, 0);
    }
    builder.setRotation("20_GIMBAL_yaw", 0, -context.getPartYRot("sighting_system"), 0);
    builder.setRotation("22_GIMBAL_pitch", context.getPartXRot("sighting_system"), 0, 0);
    context.setBoneVisible("20_GIMBAL_yaw", !context.isOwnLensView());
    return builder;
}
