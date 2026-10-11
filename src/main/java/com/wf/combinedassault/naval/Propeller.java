package com.wf.combinedassault.naval;

import net.minecraft.world.phys.Vec3;

/** Screw / waterjet: hub in the vehicle frame (glTF {@code propeller} socket). Client FX only. */
public record Propeller(Vec3 offset, float radius) {
}
