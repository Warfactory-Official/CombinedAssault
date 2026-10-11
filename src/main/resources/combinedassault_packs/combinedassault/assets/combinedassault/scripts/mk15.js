// Mk 15 (REDACTED). Barrels spin for 10 ticks after an ammo drop.
function updateBones(context) {
    var builder = createPoseBuilder();
    builder.setRotation("mount", 0, -context.getPartYRot("ciws"), 0);
    builder.setRotation("head", context.getPartXRot("ciws"), 0, 0);
    var ammo = context.getWeaponRemainAmmo("ciws", 0);
    var now = context.tickCount();
    if (ammo < context.getFloat("ammo", ammo)) {
        context.setFloat("fired", now);
    }
    context.setFloat("ammo", ammo);
    var spin = context.getFloat("spin", 0);
    if (now - context.getFloat("fired", -100) < 10) {
        spin = (spin + 40) % 360;
        context.setFloat("spin", spin);
    }
    builder.setRotation("barrels", 0, 0, spin);
    return builder;
}
