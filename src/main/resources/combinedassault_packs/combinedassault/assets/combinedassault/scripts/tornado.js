// EMB4 tornado-gr4 (tools/stage_emb4_jet.py).
function updateBones(context) {
    var builder = createPoseBuilder();
    builder.setRotation("wing_l", 35 * context.getPartYRot("wing_l") / -35, 0, 0);
    builder.setRotation("pylon_l0", 35 * context.getPartYRot("wing_l") / -35, 0, 0);
    builder.setRotation("pylon_l1", 35 * context.getPartYRot("wing_l") / -35, 0, 0);
    builder.setRotation("wing_r", 35 * context.getPartYRot("wing_r") / 35, 0, 0);
    builder.setRotation("pylon_r0", 35 * context.getPartYRot("wing_r") / 35, 0, 0);
    builder.setRotation("pylon_r1", 35 * context.getPartYRot("wing_r") / 35, 0, 0);
    return builder;
}
