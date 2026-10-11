package com.wf.combinedassault.api.scripts;

import org.mozillaa.javascript.*;

public final class ScriptContextFactory extends ContextFactory {
    private static final ScriptContextFactory INSTANCE;
    static {
        // TODO: fire event allowing addon extensions to this list; hardcoded for now
        ClassShutter shutter = className -> className.startsWith("com.wf.combinedassault") || className.equals("java.lang.String");
        INSTANCE = new ScriptContextFactory(null, shutter);
    }

    private final Scriptable globalScope;
    private final WrapFactory wrapFactory;
    private final ClassShutter classShutter;

    public ScriptContextFactory(WrapFactory wrapFactory, ClassShutter classShutter) {
        this.wrapFactory = wrapFactory;
        this.classShutter = classShutter;
        try (Context ctx = this.enterContext()) {
            Scriptable scope = ctx.initSafeStandardObjects();
            ScriptUtils.inject(scope);
            if (scope instanceof ScriptableObject so) {
                so.sealObject();
            }
            this.globalScope = scope;
        }
    }

    public static ScriptContextFactory get() {
        return INSTANCE;
    }

    public Scriptable createScope(Context ctx) {
        Scriptable scope = ctx.newObject(globalScope);
        scope.setPrototype(globalScope);
        scope.setParentScope(null);
        return scope;
    }

    @Override
    public Context makeContext() {
        Context ctx = super.makeContext();
        ctx.setInterpretedMode(false);
        if (wrapFactory != null) {
            ctx.setWrapFactory(wrapFactory);
        }
        if (classShutter != null) {
            ctx.setClassShutter(classShutter);
        }
        return ctx;
    }
}
