package com.wf.combinedassault.pack.script;

import com.wf.combinedassault.pack.script.math.ScriptVec3;

public enum ScriptMath {
    INSTANCE;

    public ScriptVec3 vec3(double x, double y, double z) {
        return new ScriptVec3(x, y, z);
    }
}
