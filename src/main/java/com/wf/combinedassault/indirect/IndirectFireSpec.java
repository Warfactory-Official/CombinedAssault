package com.wf.combinedassault.indirect;

/** Station's pack {@code indirect_fire}. {@code launchElevation}: degrees up, self-guided rounds only. */
public record IndirectFireSpec(boolean map, float launchElevation) {
}
