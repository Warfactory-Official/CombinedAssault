package com.wf.combinedassault.client.resource.animation;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class PoseNodeDefinition {

    @SerializedName("type")
    private String type;

    /** merge node only. */
    @SerializedName("inputs")
    private List<PoseNodeDefinition> inputs;

    /** blend node only. */
    @SerializedName("a")
    private PoseNodeDefinition a;

    @SerializedName("b")
    private PoseNodeDefinition b;

    /** layered_blend: pairs w/ {@code layers}. additive: pairs w/ {@code add}. */
    @SerializedName("base")
    private PoseNodeDefinition base;

    @SerializedName("layers")
    private List<LayerDefinition> layers;

    @SerializedName("add")
    private PoseNodeDefinition add;

    /** script node only. */
    @SerializedName("function")
    private String function;

    /** blend node only. */
    @SerializedName("weight")
    private Object weight;

    /** bone_binding node only. */
    @SerializedName("special_bindings")
    private List<SpecialBindingDefinition> specialBindings;

    @SerializedName("part_bindings")
    private List<PartBindingDefinition> partBindings;

    public String getType() {
        return type;
    }

    public PoseNodeDefinition getA() {
        return a;
    }

    public PoseNodeDefinition getB() {
        return b;
    }

    public PoseNodeDefinition getBase() {
        return base;
    }

    public List<LayerDefinition> getLayers() {
        return layers;
    }

    public PoseNodeDefinition getAdd() {
        return add;
    }

    public String getFunction() {
        return function;
    }

    public Object getWeight() {
        return weight;
    }

    public List<SpecialBindingDefinition> getSpecialBindings() {
        return specialBindings;
    }

    public List<PartBindingDefinition> getPartBindings() {
        return partBindings;
    }

    public List<PoseNodeDefinition> getInputs() {
        return inputs;
    }


    public static class LayerDefinition {
        @SerializedName("pose")
        private PoseNodeDefinition pose;

        @SerializedName("weight")
        private Object weight;

        public PoseNodeDefinition getPose() {
            return pose;
        }

        public Object getWeight() {
            return weight;
        }
    }

    public static class SpecialBindingDefinition {
        @SerializedName("bones")
        private List<String> bones;

        /** e.g. {@code wheel_rotation}, {@code steering_angle}; legal set is per vehicle-context subclass. */
        @SerializedName("source")
        private String source;

        @SerializedName("axis")
        private String axis;

        @SerializedName("multiplier")
        private float multiplier = 1.0f;

        @SerializedName("min")
        private Float min;

        @SerializedName("max")
        private Float max;

        @SerializedName("param")
        private Float param;

        public List<String> getBones() {
            return bones;
        }

        public String getSource() {
            return source;
        }

        public String getAxis() {
            return axis;
        }

        public float getMultiplier() {
            return multiplier;
        }

        public Float getMin() {
            return min;
        }

        public Float getMax() {
            return max;
        }

        public Float getParam() {
            return param;
        }
    }

    public static class PartBindingDefinition {
        @SerializedName("bone")
        private String bone;

        @SerializedName("part")
        private String part;

        @SerializedName("rotation_type")
        private String rotationType;

        @SerializedName("axis")
        private String axis;

        @SerializedName("invert")
        private boolean invert;

        public String getBone() {
            return bone;
        }

        public String getPart() {
            return part;
        }

        public String getRotationType() {
            return rotationType;
        }

        public String getAxis() {
            return axis;
        }

        public boolean isInvert() {
            return invert;
        }
    }

}
