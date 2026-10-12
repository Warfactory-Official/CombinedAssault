// EMB4 ka52 (tools/internal/stage_emb4_heli.py). AddRotor axes: upper -y, lower +y.
function updateBones(context) {
    var spin = (context.getFloat("rotor", 0) + context.getPower() / 5) % 360;
    context.setFloat("rotor", spin);
    var builder = createPoseBuilder();
    builder.setRotation("rotor_upper", 0, -spin, 0);
    builder.setRotation("rotor_lower", 0, spin, 0);
    builder.setRotation("sight", 0, -context.getPartYRot("sighting_system"), 0);
    builder.setRotation("sight_up", context.getPartXRot("sighting_system"), 0, 0);
    builder.setRotation("gun", context.getPartXRot("auto_cannon"), -context.getPartYRot("auto_cannon"), 0);
    return builder;
}
