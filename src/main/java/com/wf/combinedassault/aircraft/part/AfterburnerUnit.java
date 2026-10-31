package com.wf.combinedassault.aircraft.part;

import com.wf.combinedassault.vehicle.part.data.PartUnitData;
import com.wf.combinedassault.vehicle.part.data.PartUnitPojo;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.dynamics.structure.VehicleCubeOBB;
import com.wf.wflib.geom.api.OBB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import com.wf.combinedassault.vehicle.part.SwitchableUnit;

// TODO: flame visual gone with the unshipped Bedrock asset; on/off state only, no render.
public class AfterburnerUnit extends SwitchableUnit<PartUnitData> {

    public AfterburnerUnit(int index, AbstractVehicle vehicle, Vec3 afterburnerOffset) {
        super(index, vehicle, createData(index));
        this.setPivotOffset(afterburnerOffset);
    }

    private static PartUnitData createData(int index) {
        PartUnitPojo pojo = new PartUnitPojo();
        pojo.id = "afterburner_" + index;
        pojo.name = "afterburner_" + index;
        pojo.isSeat = false;
        return new PartUnitData(pojo);
    }

    public List<OBB> getOBBs() {
        return List.of();
    }

    public List<VehicleCubeOBB> getPartCubeOBBs() {
        return List.of();
    }
}
