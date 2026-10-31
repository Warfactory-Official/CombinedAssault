package com.wf.combinedassault.render.animation.context;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Shared across state machines; ITickable => double tick; ticked manually (VehicleAnimationInstance). */
public abstract class BaseAnimationContext {

    protected float partialTick;
    private final Map<String, Object> parameters = new ConcurrentHashMap<>();
    protected final Set<String> events = new HashSet<>();

    public BaseAnimationContext() {}

    public void tick() {
    }

    public void offerEvent(String event) {
        events.add(event);
    }

    public boolean hasEvent(String event) {
        return events.contains(event);
    }

    public void clearEvents() {
        events.clear();
    }

    public void setPartialTick(float partialTick) {
        this.partialTick = partialTick;
    }

    public float getPartialTick() {
        return partialTick;
    }

    // region 参数管理
    public void setParameter(String name, Object value) {
        parameters.put(name, value);
    }

    public void setFloat(String name, float value) {
        parameters.put(name, value);
    }

    public void setBool(String name, boolean value) {
        parameters.put(name, value);
    }

    public void setInt(String name, int value) {
        parameters.put(name, value);
    }

    public Object getParameter(String name) {
        return parameters.get(name);
    }

    public float getFloat(String name, float defaultValue) {
        Object value = parameters.get(name);
        if (value instanceof Number number) {
            return number.floatValue();
        }
        return defaultValue;
    }

    public boolean getBool(String name, boolean defaultValue) {
        Object value = parameters.get(name);
        if (value instanceof Boolean bool) {
            return bool;
        }
        return defaultValue;
    }

    public int getInt(String name, int defaultValue) {
        Object value = parameters.get(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return defaultValue;
    }
    // endregion

    public float getBindingValue(String source, Float param) {
        return 0f;
    }

    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

}
