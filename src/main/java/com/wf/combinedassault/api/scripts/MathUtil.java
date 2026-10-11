package com.wf.combinedassault.api.scripts;

import com.wf.combinedassault.api.scripts.math.ScriptVec3;

public enum MathUtil {
    INSTANCE;

    public ScriptVec3 vec3(double x, double y, double z) {
        return new ScriptVec3(x, y, z);
    }
}
