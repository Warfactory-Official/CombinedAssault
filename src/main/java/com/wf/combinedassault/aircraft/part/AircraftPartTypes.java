package com.wf.combinedassault.aircraft.part;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.serialize.GsonUtil;
import com.wf.combinedassault.pack.part.PartUnitType;
import com.wf.combinedassault.aircraft.part.data.AirbrakeUnitData;
import com.wf.combinedassault.aircraft.part.data.AirbrakeUnitPojo;
import com.wf.combinedassault.aircraft.part.data.FlapUnitData;
import com.wf.combinedassault.aircraft.part.data.FlapUnitPojo;
import com.wf.combinedassault.aircraft.part.data.VariableSweepUnitData;
import com.wf.combinedassault.aircraft.part.data.VariableSweepUnitPojo;
import com.wf.combinedassault.vehicle.part.data.RotatableUnitData;
import com.wf.combinedassault.vehicle.part.data.RotatableUnitPojo;

public final class AircraftPartTypes {

    public static final PartUnitType<AirbrakeUnit, AirbrakeUnitData> AIRBRAKE =
            PartUnitType.Builder.<AirbrakeUnit, AirbrakeUnitData>of(CombinedAssault.modLocation("airbrake"))
                    .setFactory(AirbrakeUnit::new)
                    .setDataSerializer((json) -> {
                        var pojo = GsonUtil.GSON.fromJson(json, AirbrakeUnitPojo.class);
                        return new AirbrakeUnitData(pojo);
                    })
                    .build();

    public static final PartUnitType<ThrustUnit, RotatableUnitData> THRUST =
            PartUnitType.Builder.<ThrustUnit, RotatableUnitData>of(CombinedAssault.modLocation("thrust"))
                    .setFactory(ThrustUnit::new)
                    .setDataSerializer((json) -> {
                        var pojo = GsonUtil.GSON.fromJson(json, RotatableUnitPojo.class);
                        return new RotatableUnitData(pojo);
                    })
                    .build();

    public static final PartUnitType<VariableSweepUnit, VariableSweepUnitData> VARIABLE_SWEEP =
            PartUnitType.Builder.<VariableSweepUnit, VariableSweepUnitData>of(CombinedAssault.modLocation("variable_sweep"))
                    .setFactory(VariableSweepUnit::new)
                    .setDataSerializer(json -> new VariableSweepUnitData(GsonUtil.GSON.fromJson(json, VariableSweepUnitPojo.class)))
                    .build();

    public static final PartUnitType<FlapUnit, FlapUnitData> FLAPS =
            PartUnitType.Builder.<FlapUnit, FlapUnitData>of(CombinedAssault.modLocation("flaps"))
                    .setFactory(FlapUnit::new)
                    .setDataSerializer(json -> new FlapUnitData(GsonUtil.GSON.fromJson(json, FlapUnitPojo.class)))
                    .build();

    private AircraftPartTypes() {
    }
}
