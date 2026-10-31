package com.wf.combinedassault.ballistics.hit;

import java.util.regex.Pattern;

/** Hit face material by glTF material name token ({@code _ . space} delimited); untagged = steel. Effects only. */
public enum SurfaceMaterial {
    STEEL(null),
    RUBBER(token("rubber")),
    GLASS(token("glass")),
    SOFT(token("soft|fabric|cloth|canvas"));

    private static final SurfaceMaterial[] VALUES = values();

    private final Pattern pattern;

    SurfaceMaterial(Pattern pattern) {
        this.pattern = pattern;
    }

    public static SurfaceMaterial of(String materialName) {
        for (SurfaceMaterial m : VALUES) {
            if (m.pattern != null && m.pattern.matcher(materialName).find()) {
                return m;
            }
        }
        return STEEL;
    }

    public static SurfaceMaterial byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : STEEL;
    }

    private static Pattern token(String words) {
        return Pattern.compile("(?i)(?:^|[_ .])(?:" + words + ")(?:$|[_ .])");
    }
}
