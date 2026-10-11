// Mavic 3 Pro (tools/stage_mavic.py). Spin sense from the export's rotor extras; gimbal follows the camera part.
var CW = ["13_ROTOR_FL", "13_ROTOR_RR"];
var CCW = ["13_ROTOR_FR", "13_ROTOR_RL"];

function updateBones(context) {
    var spin = (context.getFloat("rotor", 0) + context.getPower() / 5) % 360;
    context.setFloat("rotor", spin);
    var builder = createPoseBuilder();
    for (var i = 0; i < CW.length; i++) {
        builder.setRotation(CW[i], 0, -spin, 0);
        builder.setRotation(CCW[i], 0, spin, 0);
    }
    builder.setRotation("20_GIMBAL_yaw", 0, -context.getPartYRot("sighting_system"), 0);
    builder.setRotation("22_GIMBAL_pitch", context.getPartXRot("sighting_system"), 0, 0);
    return builder;
}
