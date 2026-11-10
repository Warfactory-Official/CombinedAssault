package com.wf.combinedassault.aircraft.data;

import com.wf.combinedassault.aircraft.AircraftContent;
import com.wf.combinedassault.aircraft.FixedWingVehicle;
import com.wf.combinedassault.aircraft.part.AirbrakeUnit;
import com.wf.combinedassault.aircraft.part.FlapUnit;
import com.wf.combinedassault.aircraft.part.ThrustUnit;
import com.wf.combinedassault.aircraft.pojo.Exhaust;
import com.wf.combinedassault.dynamics.part.LandingGearUnit;
import com.wf.combinedassault.dynamics.structure.VehicleCubeOBB;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class FixedWingVehicleData extends BaseVehicleData<FixedWingVehicle> {

    public VehicleCubeOBB aerodynamicCubeOBB;
    public float thrust = 8000f;
    public float thrustK = 1.5f;
    public float ceiling = 512;
    public float xRotInputStep = 0.2f;
    public float yRotInputStep = 0.5f;
    public float zRotInputStep = 0.2f;
    public float airDragKMin = 2f;
    public float airDragKMax = 8f;
    public float liftToDragK = 6;
    public float angleOfAttackMin = -10f;
    public float angleOfAttackMax = 25f;
    public float xRotInputDragK = 1f;
    public float yRotInputDragK = 1f / 4;
    public float zRotInputDragK = 1f / 8;
    public float turnRateBySpeed = 1f / 2.5f;
    public float xTurnRate = 2;
    public float yTurnRate = 3;
    public float zTurnRate = 8;
    public float vtolTurnRate = 0;
    public List<Vec3> vortexOffsets;
    public List<Vec3> aerobaticSmokeOffsets;
    public List<Exhaust> exhausts;
    public String landingGearPartId;
    public String airbrakePartId;
    public String thrustPartId;

    @Override
    public AbstractVehicle fromCustom(Level level) {
        return new FixedWingVehicle(AircraftContent.FIXED_WING_VEHICLE.get(), level);
    }

    @Override
    public PartUnitsAndSeats createPartUnits(AbstractVehicle vehicle) {
        if (vehicle instanceof FixedWingVehicle fixedWingVehicle) {
            PartUnitsAndSeats result = super.createPartUnits(vehicle);
            PartUnit<?> landingGearUnit = result.partUnitMap().get(this.landingGearPartId);
            if (landingGearUnit instanceof LandingGearUnit switchableUnit) {
                fixedWingVehicle.landingGear = switchableUnit;
            }
            PartUnit<?> airbrakePartUnit = result.partUnitMap().get(this.airbrakePartId);
            if (airbrakePartUnit instanceof AirbrakeUnit airbrakeUnit) {
                fixedWingVehicle.airbrakeUnit = airbrakeUnit;
            }
            fixedWingVehicle.flapUnits = result.partUnitMap().values().stream()
                    .filter(FlapUnit.class::isInstance).map(FlapUnit.class::cast).toList();
            PartUnit<?> thrustPartUnit = result.partUnitMap().get(this.thrustPartId);
            if (thrustPartUnit instanceof ThrustUnit thrustUnit) {
                fixedWingVehicle.thrustUnit = thrustUnit;
            }
            return result;
        }
        return null;
    }

    public void build(FixedWingVehicleDataPojo pojo) {
        super.build(pojo);
        this.aerodynamicCubeOBB = this.mainCubeOBB;
        this.thrust = pojo.attributes.thrust;
        this.thrustK = pojo.attributes.thrustK;
        this.ceiling = pojo.attributes.ceiling;
        this.xRotInputStep = pojo.attributes.xRotInputStep;
        this.yRotInputStep = pojo.attributes.yRotInputStep;
        this.zRotInputStep = pojo.attributes.zRotInputStep;
        this.airDragKMin = pojo.attributes.airDragKMin;
        this.airDragKMax = pojo.attributes.airDragKMax;
        this.liftToDragK = pojo.attributes.liftToDragK;
        this.angleOfAttackMin = pojo.attributes.angleOfAttackMin;
        this.angleOfAttackMax = pojo.attributes.angleOfAttackMax;
        this.xRotInputDragK = pojo.attributes.xRotInputDragK;
        this.yRotInputDragK = pojo.attributes.yRotInputDragK;
        this.zRotInputDragK = pojo.attributes.zRotInputDragK;
        this.turnRateBySpeed = pojo.attributes.turnRateBySpeed;
        this.xTurnRate = pojo.attributes.xTurnRate;
        this.yTurnRate = pojo.attributes.yTurnRate;
        this.zTurnRate = pojo.attributes.zTurnRate;
        this.vtolTurnRate = pojo.attributes.vtolTurnRate;
        this.vortexOffsets = pojo.attributes.vortexOffsets;
        this.exhausts = List.copyOf(pojo.attributes.exhausts);
        for (int i = 0; i < exhausts.size(); i++) {
            Exhaust e = exhausts.get(i);
            if (e.offset == null || !(e.radius > 0) || e.direction.lengthSqr() < 1e-6) {
                throw new IllegalArgumentException("exhausts[" + i + "] needs offset, radius > 0, direction");
            }
            if (e.pivot != null && pojo.thrustPartId == null) {
                throw new IllegalArgumentException("exhausts[" + i + "] pivot needs thrust_part_id");
            }
            e.direction = e.direction.normalize();
        }
        this.aerobaticSmokeOffsets = pojo.attributes.aerobaticSmokeOffsets.isEmpty() ? pojo.attributes.vortexOffsets : pojo.attributes.aerobaticSmokeOffsets;
        this.landingGearPartId = pojo.landingGearPartId;
        this.airbrakePartId = pojo.airbrakePartId;
        this.thrustPartId = pojo.thrustPartId;
    }

    public void inject(FixedWingVehicle vehicle) {
        vehicle.aerodynamicCubeOBB = new VehicleCubeOBB(this.aerodynamicCubeOBB);
        vehicle.thrust = this.thrust;
        vehicle.thrustK = this.thrustK;
        vehicle.ceiling = this.ceiling;
        vehicle.xRotInputStep = this.xRotInputStep;
        vehicle.yRotInputStep = this.yRotInputStep;
        vehicle.zRotInputStep = this.zRotInputStep;
        vehicle.airDragKMin = this.airDragKMin;
        vehicle.airDragKMax = this.airDragKMax;
        vehicle.liftToDragK = this.liftToDragK;
        vehicle.angleOfAttackMin = this.angleOfAttackMin;
        vehicle.angleOfAttackMax = this.angleOfAttackMax;
        vehicle.xRotInputDragK = this.xRotInputDragK;
        vehicle.yRotInputDragK = this.yRotInputDragK;
        vehicle.zRotInputDragK = this.zRotInputDragK;
        vehicle.turnRateBySpeed = this.turnRateBySpeed;
        vehicle.xTurnRate = this.xTurnRate;
        vehicle.yTurnRate = this.yTurnRate;
        vehicle.zTurnRate = this.zTurnRate;
        vehicle.vtolTurnRate = this.vtolTurnRate;
        vehicle.vortexOffsets = this.vortexOffsets;
        vehicle.aerobaticSmokeOffsets = this.aerobaticSmokeOffsets;
        vehicle.exhausts = this.exhausts;
    }

}
