package com.wf.combinedassault.client.render.animation.graph.node;

import com.maydaymemory.mae.basic.Pose;
import org.mozillaa.javascript.Function;
import org.mozillaa.javascript.Scriptable;
import org.mozillaa.javascript.Wrapper;
import com.wf.combinedassault.api.animation.IAnimationInstance;
import com.wf.combinedassault.api.scripts.ScriptContextFactory;
import com.wf.combinedassault.client.render.animation.util.PoseHelper;

public class ScriptPoseNode implements PoseNode {
    
    private final Function scriptFunction;
    private final Scriptable scope;

    public ScriptPoseNode(Function scriptFunction, Scriptable scope) {
        this.scriptFunction = scriptFunction;
        this.scope = scope;
    }

    @Override
    public Pose evaluate(IAnimationInstance<?> context) {
        if (scriptFunction == null) {
            return null;
        }
        try (var ctx = ScriptContextFactory.get().enterContext()) {
            Object result = scriptFunction.call(
                    ctx,
                    scope,
                    scope,
                    new Object[]{context.getContext()}
            );
            if (result instanceof Wrapper wrapper) {
                result = wrapper.unwrap();
            }
            if (result instanceof PoseHelper pose) {
                return pose.build();
            }
        }
        return null;
    }

}
