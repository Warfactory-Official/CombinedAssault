package com.wf.combinedassault.pack.script;

import org.mozillaa.javascript.Context;
import org.mozillaa.javascript.Scriptable;
import org.mozillaa.javascript.ScriptableObject;

public class ScriptUtils {

    // Wraps vanilla types: scripts hit mapping obfuscation + ClassShutter restrictions on raw access.
    public static void inject(Scriptable scope) {
        Object jsVec3Class = Context.javaToJS(ScriptMath.INSTANCE, scope);
        ScriptableObject.putProperty(scope, "MathUtil", jsVec3Class);

        Object particleUtil = Context.javaToJS(ScriptParticles.INSTANCE, scope);
        ScriptableObject.putProperty(scope, "ParticleUtil", particleUtil);
    }
}
