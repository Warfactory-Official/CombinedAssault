package com.wf.combinedassault.naval.data;

import net.minecraft.world.level.Level;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.naval.ShipVehicle;
import com.wf.combinedassault.naval.Propeller;
import com.wf.combinedassault.naval.ShipHydrodynamics;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.naval.NavalContent;

public class ShipVehicleData extends BaseVehicleData<ShipVehicle> {

    public final ShipHydrodynamics.Params params = new ShipHydrodynamics.Params();
    private List<Propeller> propellers = List.of();

    @Override
    public AbstractVehicle fromCustom(Level level) {
        return new ShipVehicle(NavalContent.SHIP.get(), level);
    }

    public void build(ShipVehicleDataPojo pojo) {
        // A hull is always wet, and the box buoyancy would fight the hull model.
        pojo.canWade = true;
        pojo.physicsInfo.density = 0;
        super.build(pojo);
        ShipVehicleDataPojo.ShipAttributes a = pojo.attributes;
        params.waterline = a.waterline;
        params.draft = a.draft;
        params.maxSpeedForward = a.maxSpeedForward;
        params.maxSpeedBackward = a.maxSpeedBackward;
        params.accelerationTime = a.accelerationTime;
        params.throttleRate = a.throttleRate;
        params.rudderRate = a.rudderRate;
        params.turnRate = a.turnRate;
        params.turnLag = a.turnLag;
        params.lowSpeedTurn = a.lowSpeedTurn;
        params.drift = a.drift;
        params.heel = a.heel;
        params.trim = a.trim;
        params.heavePeriod = a.heavePeriod;
        params.rightingRate = a.rightingRate;
        params.minKeelClearance = a.minKeelClearance;
        params.shallowDrag = a.shallowDrag;
        ShipVehicleDataPojo.Submarine sub = a.submarine;
        params.submarine = sub != null;
        if (sub != null) {
            params.maxDepth = sub.maxDepth;
            params.crushDepth = sub.crushDepth;
            params.diveRate = sub.diveRate;
            params.maxSpeedSubmerged = sub.maxSpeedSubmerged;
            params.diveAngle = sub.diveAngle;
        }
        propellers = a.propellers.stream().map(p -> {
            if (p.length != 4) {
                throw new IllegalArgumentException("propellers entry: [x, y, z, radius], got " + p.length + " numbers");
            }
            return new Propeller(new Vec3(p[0], p[1], p[2]), (float) p[3]);
        }).toList();
        if (params.draft < 0 && mainCubeOBB != null) {
            params.draft = params.waterline - (mainCubeOBB.offset().y - mainCubeOBB.obb().extents().y);
        }
    }

    @Override
    public void inject(ShipVehicle vehicle) {
        vehicle.setParams(params);
        vehicle.setPropellers(propellers);
    }

}
