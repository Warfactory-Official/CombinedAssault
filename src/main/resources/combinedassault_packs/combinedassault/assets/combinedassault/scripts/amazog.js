// Amazog octocopter (tools/internal/stage_amazog.py): ROTOR_01..08, cw/ccw alternating.
var ROTORS = [];
for (var i = 0; i < 8; i++) {
    ROTORS.push(["ROTOR_0" + (i + 1) + "_SPIN", "rotor R" + (i + 1), i % 2 == 0 ? -1 : 1]);
}

function updateBones(context) {
    var step = context.getPower() / 5;
    var builder = createPoseBuilder();
    // Broken rotor (unit pool) holds its last angle.
    for (var i = 0; i < ROTORS.length; i++) {
        var key = "spin_" + i;
        var spin = context.getSystemEffectiveness(ROTORS[i][1]) > 0
            ? (context.getFloat(key, 0) + step) % 360 : context.getFloat(key, 0);
        context.setFloat(key, spin);
        builder.setRotation(ROTORS[i][0], 0, ROTORS[i][2] * spin, 0);
    }
    return builder;
}
