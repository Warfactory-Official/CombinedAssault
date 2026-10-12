// EMB4 mh-60l_dap (tools/internal/stage_emb4_heli.py).
function updateBones(context) {
    var spin = (context.getFloat("rotor", 0) + context.getPower() / 5) % 360;
    context.setFloat("rotor", spin);
    var builder = createPoseBuilder();
    builder.setRotation("rotor", 0, spin, 0);
    builder.setRotation("tail_rotor", spin * 5, 0, 0);
    builder.setRotation("flir", 0, -context.getPartYRot("sighting_system"), 0);
    var sides = ["r", "l"];
    for (var i = 0; i < 2; i++) {
        var part = "door_gun_" + sides[i];
        builder.setRotation(part, 0, -context.getPartYRot(part), 0);
        builder.setRotation(part + "_up", context.getPartXRot(part), 0, 0);
    }
    return builder;
}
