// EMB4 f22a (tools/internal/stage_emb4_jet.py).
function updateBones(context) {
    var builder = createPoseBuilder();
    builder.setRotation("nozzle_top", 20 * Math.min(1, context.getThrottleLevel() / 100), 0, 0);
    builder.setRotation("nozzle_bottom", -20 * Math.min(1, context.getThrottleLevel() / 100), 0, 0);
    return builder;
}
