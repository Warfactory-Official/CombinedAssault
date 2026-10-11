// EMB4 ah-6 (tools/stage_emb4_heli.py).
function updateBones(context) {
    var step = context.getPower() / 5;
    var spin = (context.getFloat("rotor", 0) + (context.getRotorLift() > 0 ? step : 0)) % 360;
    var tail = (context.getFloat("tail_rotor", 0) + (context.getRotorAntiTorque() > 0 ? step * 5 : 0)) % 360;
    context.setFloat("rotor", spin);
    context.setFloat("tail_rotor", tail);
    var builder = createPoseBuilder();
    builder.setRotation("rotor", 0, spin, 0);
    builder.setRotation("tail_rotor", tail, 0, 0);
    return builder;
}
