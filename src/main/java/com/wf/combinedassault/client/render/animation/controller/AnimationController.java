package com.wf.combinedassault.client.render.animation.controller;

import com.wf.combinedassault.client.render.animation.context.BaseAnimationContext;
import com.wf.combinedassault.client.render.animation.graph.PoseGraph;
import com.wf.combinedassault.client.resource.animation.ParameterDefinition;

import java.util.Map;

public class AnimationController<T extends BaseAnimationContext> {

    private final String name;
    private final Map<String, ParameterDefinition> parameters;
    private final PoseGraph poseGraph;

    public AnimationController(String name, Map<String, ParameterDefinition> parameters, PoseGraph poseGraph) {
        this.name = name;
        this.parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
        this.poseGraph = poseGraph;
    }

    /** Script parameter defaults into {@code context}. */
    public void initialize(T context) {
        for (Map.Entry<String, ParameterDefinition> entry : parameters.entrySet()) {
            ParameterDefinition definition = entry.getValue();
            switch (definition.getType()) {
                case FLOAT -> context.setFloat(entry.getKey(), definition.getDefaultFloat());
                case BOOL -> context.setBool(entry.getKey(), definition.getDefaultBool());
                case INT -> context.setInt(entry.getKey(), definition.getDefaultInt());
            }
        }
    }

    public String getName() {
        return name;
    }

    public PoseGraph getPoseGraph() {
        return poseGraph;
    }

    public Map<String, ParameterDefinition> getParameters() {
        return parameters;
    }
}
