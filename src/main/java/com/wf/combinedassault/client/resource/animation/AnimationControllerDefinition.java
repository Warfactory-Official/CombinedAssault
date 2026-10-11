package com.wf.combinedassault.client.resource.animation;

import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;

public class AnimationControllerDefinition {

    @SerializedName("name")
    private String name;

    @SerializedName("description")
    private String description;

    /** Shared by every {@code script} graph node in this controller; not per-node. */
    @SerializedName("script")
    private ResourceLocation script;

    @SerializedName("parameters")
    private Map<String, ParameterDefinition> parameters;

    @SerializedName("graph")
    private PoseNodeDefinition graph;

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public ResourceLocation getScript() {
        return script;
    }

    public Map<String, ParameterDefinition> getParameters() {
        return parameters;
    }

    public PoseNodeDefinition getGraph() {
        return graph;
    }

}
