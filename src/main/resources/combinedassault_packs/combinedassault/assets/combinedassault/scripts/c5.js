// EMB4 c5 (tools/stage_emb4_jet.py).
function updateBones(context) {
    var builder = createPoseBuilder();
    var fan_0 = (context.getFloat("spin_fan_0", 0) + context.getPower() * 0.06) % 360;
    context.setFloat("spin_fan_0", fan_0);
    builder.setRotation("fan_0", fan_0, 0, 0);
    var fan_1 = (context.getFloat("spin_fan_1", 0) + context.getPower() * 0.06) % 360;
    context.setFloat("spin_fan_1", fan_1);
    builder.setRotation("fan_1", fan_1, 0, 0);
    var fan_2 = (context.getFloat("spin_fan_2", 0) + context.getPower() * 0.06) % 360;
    context.setFloat("spin_fan_2", fan_2);
    builder.setRotation("fan_2", fan_2, 0, 0);
    var fan_3 = (context.getFloat("spin_fan_3", 0) + context.getPower() * 0.06) % 360;
    context.setFloat("spin_fan_3", fan_3);
    builder.setRotation("fan_3", fan_3, 0, 0);
    builder.setRotation("ramp", -1 * context.getPartXRot("ramp"), 0, 0);
    return builder;
}
