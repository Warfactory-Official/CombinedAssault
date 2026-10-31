package com.wf.combinedassault.pack.client.animation;

import com.google.gson.annotations.SerializedName;

public class ParameterDefinition {
    @SerializedName("type")
    private String type;

    @SerializedName("default")
    private Object defaultValue;

    public ParameterType getType() {
        if (type == null) {
            return ParameterType.FLOAT;
        }
        return switch (type.toLowerCase()) {
            case "float" -> ParameterType.FLOAT;
            case "bool", "boolean" -> ParameterType.BOOL;
            case "int", "integer" -> ParameterType.INT;
            default -> throw new IllegalArgumentException("Unknown parameter type: " + type);
        };
    }

    public float getDefaultFloat() {
        if (defaultValue instanceof Number) {
            return ((Number) defaultValue).floatValue();
        }
        return 0.0f;
    }

    public boolean getDefaultBool() {
        if (defaultValue instanceof Boolean) {
            return (Boolean) defaultValue;
        }
        return false;
    }

    public int getDefaultInt() {
        if (defaultValue instanceof Number) {
            return ((Number) defaultValue).intValue();
        }
        return 0;
    }
}
