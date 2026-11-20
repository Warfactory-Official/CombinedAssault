package com.wf.combinedassault.aircraft.part.data;

import com.wf.combinedassault.vehicle.part.data.RotatableUnitData;

public class ControlSurfaceUnitData extends RotatableUnitData {

    /** Full travel in 3 ticks (0.15 s) unless rot_info.x_rot_speed says otherwise. */
    private static final float TRAVEL_TICKS = 3;

    private final float pitch;
    private final float roll;
    private final float yaw;
    private final float full;

    public ControlSurfaceUnitData(ControlSurfaceUnitPojo pojo) {
        super(pojo);
        this.pitch = pojo.pitch;
        this.roll = pojo.roll;
        this.yaw = pojo.yaw;
        this.full = Math.abs(pitch) + Math.abs(roll) + Math.abs(yaw);
        if (full == 0) {
            throw new IllegalArgumentException(getId() + ": control_surface needs a non-zero pitch, roll or yaw");
        }
        rotInfo.xRotMin = -full;
        rotInfo.xRotMax = full;
        rotInfo.needPower = false;
        if (rotInfo.xRotSpeed == 0) {
            rotInfo.xRotSpeed = full / TRAVEL_TICKS;
        }
    }

    public float pitch() {
        return pitch;
    }

    public float roll() {
        return roll;
    }

    public float yaw() {
        return yaw;
    }

    /** |pitch| + |roll| + |yaw|: deflection range is +-full. */
    public float full() {
        return full;
    }
}
