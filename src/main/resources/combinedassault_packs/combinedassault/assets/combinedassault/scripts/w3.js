// EMB4 w3 (REDACTED).
function updateBones(context) {
    var spin = (context.getFloat("rotor", 0) + context.getPower() / 5) % 360;
    context.setFloat("rotor", spin);
    var builder = createPoseBuilder();
    builder.setRotation("rotor", 0, spin, 0);
    builder.setRotation("tail_rotor", spin * 5, 0, 0);
    builder.setRotation("sight", 0, -context.getPartYRot("sighting_system"), 0);
    var guns = ["nose_gun", "door_gun"];
    for (var i = 0; i < 2; i++) {
        builder.setRotation(guns[i], 0, -context.getPartYRot(guns[i]), 0);
        builder.setRotation(guns[i] + "_up", context.getPartXRot(guns[i]), 0, 0);
    }
    return builder;
}
