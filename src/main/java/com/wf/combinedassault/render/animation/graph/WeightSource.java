package com.wf.combinedassault.render.animation.graph;

import com.wf.combinedassault.pack.IAnimationInstance;

public interface WeightSource {
    /** Typically {@code [0, 1]}. */
    float getWeight(IAnimationInstance<?> context);

    class Static implements WeightSource {
        private final float value;

        public Static(float value) {
            this.value = value;
        }

        @Override
        public float getWeight(IAnimationInstance<?> context) {
            return value;
        }
    }

    class Parameter implements WeightSource {
        private final String parameterName;
        private final float defaultValue;

        public Parameter(String parameterName, float defaultValue) {
            this.parameterName = parameterName;
            this.defaultValue = defaultValue;
        }

        @Override
        public float getWeight(IAnimationInstance<?> context) {
            return context.getContext().getFloat(parameterName, defaultValue);
        }
    }
}
