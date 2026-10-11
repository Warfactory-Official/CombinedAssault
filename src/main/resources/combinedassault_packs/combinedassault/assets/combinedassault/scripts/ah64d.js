// EMB4 ah-64 (tools/stage_emb4_heli.py). setRotation +angle == MCHeli AddRotor axis.
function updateBones(context) {
    var step = context.getPower() / 5;
    var spin = (context.getFloat("rotor", 0) + (context.getRotorLift() > 0 ? step : 0)) % 360;
    var tail = (context.getFloat("tail_rotor", 0) + (context.getRotorAntiTorque() > 0 ? step * 5 : 0)) % 360;
    context.setFloat("rotor", spin);
    context.setFloat("tail_rotor", tail);
    var builder = createPoseBuilder();
    builder.setRotation("rotor", 0, spin, 0);
    builder.setRotation("tail_rotor", tail, 0, 0);
    builder.setRotation("gun", 0, -context.getPartYRot("auto_cannon"), 0);
    builder.setRotation("gun_up", context.getPartXRot("auto_cannon"), 0, 0);
    builder.setRotation("pnvs", 0, -context.getPartYRot("sighting_system"), 0);
    builder.setRotation("tads", 0, -context.getPartYRot("sighting_system"), 0);
    builder.setRotation("tads_up", context.getPartXRot("sighting_system"), 0, 0);
    return builder;
}
