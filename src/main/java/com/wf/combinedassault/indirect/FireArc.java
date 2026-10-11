package com.wf.combinedassault.indirect;

/** Firing decision, not a gun property: same barrel can use either arc if the mount elevates far enough. */
public enum FireArc {

    /** Flat, fast; no terrain clearance. */
    LOW("low"),
    /** Lofted, slow; clears what's between. */
    HIGH("high");

    private final String id;

    FireArc(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public FireArc other() {
        return this == LOW ? HIGH : LOW;
    }

}
