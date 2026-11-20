package com.wf.combinedassault.aircraft.part;

import com.wf.combinedassault.aircraft.FixedWingVehicle;
import com.wf.combinedassault.aircraft.RotaryWingVehicle;
import com.wf.combinedassault.aircraft.part.data.ControlSurfaceUnitData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.RotatableUnit;
import net.minecraft.util.Mth;

/** Elevator / aileron / rudder / stabilator / elevon: part xRot follows the pilot's control inputs. */
public class ControlSurfaceUnit extends RotatableUnit<ControlSurfaceUnitData> {

    public ControlSurfaceUnit(int index, AbstractVehicle vehicle, ControlSurfaceUnitData data) {
        super(index, vehicle, data);
    }

    @Override
    public void tick() {
        float pitch = 0;
        float roll = 0;
        float yaw = 0;
        if (vehicle.getDriver() != null && vehicle.hasPower()) {
            if (vehicle instanceof FixedWingVehicle plane) {
                pitch = plane.getPitchInput();
                roll = plane.getRollInput();
                yaw = -plane.getYawInput();
            } else if (vehicle instanceof RotaryWingVehicle heli) {
                pitch = -heli.getPitchInput();
                roll = heli.getRollInput();
            }
        }
        float full = data.full();
        setXAimRot(Mth.clamp(data.pitch() * pitch + data.roll() * roll + data.yaw() * yaw, -full, full));
        super.tick();
    }

    /** Clip progress: 0.5 neutral, 0 / 1 at -/+ full. */
    public float clipProgress(float partialTick) {
        return Mth.clamp(0.5f + getViewXRot(partialTick) / (2 * data.full()), 0, 1);
    }
}
