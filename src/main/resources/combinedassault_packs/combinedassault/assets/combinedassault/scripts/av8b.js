// EMB4 harrier (tools/internal/stage_emb4_jet.py).
function updateBones(context) {
    var builder = createPoseBuilder();
    builder.setRotation("nozzle_0", -context.getPartXRot("thrust"), 0, 0);
    builder.setRotation("nozzle_1", -context.getPartXRot("thrust"), 0, 0);
    builder.setRotation("nozzle_2", -context.getPartXRot("thrust"), 0, 0);
    builder.setRotation("nozzle_3", -context.getPartXRot("thrust"), 0, 0);
    return builder;
}
