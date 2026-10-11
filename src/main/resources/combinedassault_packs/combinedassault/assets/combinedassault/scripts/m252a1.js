// The whole mortar's pose.
//
// The tube and the bipod are an ordinary elevation and traverse. The elevating screw between them is
// the part that needs arithmetic: its foot is pinned to the bipod, which stands still, and its head to
// the bridge, which the tube carries round, so the two pins move apart as the tube comes up and no
// fixed-length rod spans them. It is therefore two rods that overlap: a housing standing on the lower
// pin and a spindle hanging from the upper one, each aimed along the line between the pins. That is
// both what a real one does and the only thing this renderer can draw, because a posed bone's rotation
// moves its geometry and its translation and scale do not.
//
// Every figure is in blocks from the socket the tube pivots in, measured off the export by
// REDACTED, which prints them.
var FOOT_Y = 0.355, FOOT_Z = 0.585;         // the lower pin, in the bipod's frame
var HEAD_Y = -0.14953, HEAD_Z = 0.83493;    // the upper pin, in the tube's, with the tube laid flat

function mortarPose(context) {
    var builder = createPoseBuilder();
    var elevation = context.getPartXRot("mortar");
    builder.setRotation("traverse", 0, -context.getPartYRot("mortar"), 0);
    builder.setRotation("tube", elevation, 0, 0);

    var radians = elevation * Math.PI / 180;
    var cos = Math.cos(radians), sin = Math.sin(radians);
    var y = HEAD_Y * cos - HEAD_Z * sin - FOOT_Y;
    var z = HEAD_Y * sin + HEAD_Z * cos - FOOT_Z;
    var tilt = Math.atan2(z, y) * 180 / Math.PI;
    // The housing stands in the bipod's frame, so the line between the pins is the angle it wants. The
    // spindle hangs in the tube's, which the elevation has already turned, so it wants that much less.
    builder.setRotation("screw_housing", tilt, 0, 0);
    builder.setRotation("screw_spindle", tilt - elevation, 0, 0);
    return builder;
}
