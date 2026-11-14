function updateBones(context) {
    var step = context.getPower() / 5;
    var builder = createPoseBuilder();
    var spin_0 = (context.getFloat("spin_0", 0) + step) % 360;
    context.setFloat("spin_0", spin_0);
    builder.setRotation("rotor", 0, spin_0, 0);
    var spin_1 = (context.getFloat("spin_1", 0) + step * 5) % 360;
    context.setFloat("spin_1", spin_1);
    builder.setRotation("tail_rotor", spin_1, 0, 0);
    return builder;
}
