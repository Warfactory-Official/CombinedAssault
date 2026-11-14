function updateBones(context) {
    var step = context.getPower() / 5;
    var builder = createPoseBuilder();
    var spin_0 = (context.getFloat("spin_0", 0) + step) % 360;
    context.setFloat("spin_0", spin_0);
    builder.setRotation("prop_0", 0, 0, spin_0);
    return builder;
}
