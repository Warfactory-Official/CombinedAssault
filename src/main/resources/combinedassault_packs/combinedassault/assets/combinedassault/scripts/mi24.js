// EMB4 mi-24 (tools/stage_emb4_heli.py).
function updateBones(context) {
    var spin = (context.getFloat("rotor", 0) + context.getPower() / 5) % 360;
    context.setFloat("rotor", spin);
    var builder = createPoseBuilder();
    builder.setRotation("rotor", 0, spin, 0);
    builder.setRotation("tail_rotor", spin * 5, 0, 0);
    builder.setRotation("gun", 0, -context.getPartYRot("gun"), 0);
    builder.setRotation("gun_up", context.getPartXRot("gun"), 0, 0);
    return builder;
}
