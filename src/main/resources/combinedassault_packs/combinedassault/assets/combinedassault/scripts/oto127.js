// EMB4 oto127 (tools/internal/stage_emb4_ce.py). Barrel slides back RECOIL blocks on an ammo drop, runs out over RUN_OUT ticks:
// rotation of "barrel", pivot 20 blocks below the bore.
var RECOIL = 0.4, RUN_OUT = 12, SLIDE = 20;
function updateBones(context) {
    var builder = createPoseBuilder();
    builder.setRotation("mount", 0, -context.getPartYRot("gun"), 0);
    builder.setRotation("cradle", context.getPartXRot("gun"), 0, 0);
    var ammo = context.getWeaponRemainAmmo("gun", 0);
    var now = context.tickCount();
    if (ammo < context.getFloat("ammo", ammo)) {
        context.setFloat("fired", now);
    }
    context.setFloat("ammo", ammo);
    var back = RECOIL * Math.max(0, 1 - (now - context.getFloat("fired", -100)) / RUN_OUT);
    builder.setRotation("barrel", -back / SLIDE * 180 / Math.PI, 0, 0);
    return builder;
}
