function updateBones(context) {
    var step = context.getPower() / 5;
    var builder = createPoseBuilder();
    var spin_0 = (context.getFloat("spin_0", 0) + step) % 360;
    context.setFloat("spin_0", spin_0);
    builder.setRotation("prop_0", 0, 0, spin_0);
    var spin_1 = (context.getFloat("spin_1", 0) + step) % 360;
    context.setFloat("spin_1", spin_1);
    builder.setRotation("prop_1", 0, 0, -spin_1);
    var spin_2 = (context.getFloat("spin_2", 0) + step) % 360;
    context.setFloat("spin_2", spin_2);
    builder.setRotation("prop_2", 0, 0, spin_2);
    var spin_3 = (context.getFloat("spin_3", 0) + step) % 360;
    context.setFloat("spin_3", spin_3);
    builder.setRotation("prop_3", 0, 0, -spin_3);
    return builder;
}
