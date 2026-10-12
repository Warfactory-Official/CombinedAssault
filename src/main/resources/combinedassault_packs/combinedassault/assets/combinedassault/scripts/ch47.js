// EMB4 ch47 (tools/internal/stage_emb4_ce.py).
function updateBones(context) {
    var spin = (context.getFloat("rotor", 0) + context.getPower() / 5) % 360;
    context.setFloat("rotor", spin);
    var builder = createPoseBuilder();
    builder.setRotation("rotor", 0, spin, 0);
    builder.setRotation("rotor_aft", 0, -spin, 0);
    builder.setRotation("ramp", context.getPartXRot("ramp"), 0, 0);
    var guns = ["door_gun_r", "door_gun_l", "ramp_gun"];
    for (var i = 0; i < 3; i++) {
        builder.setRotation(guns[i], 0, -context.getPartYRot(guns[i]), 0);
        builder.setRotation(guns[i] + "_up", context.getPartXRot(guns[i]), 0, 0);
    }
    return builder;
}
