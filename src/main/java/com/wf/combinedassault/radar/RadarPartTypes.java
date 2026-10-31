package com.wf.combinedassault.radar;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.serialize.GsonUtil;
import com.wf.combinedassault.pack.part.PartUnitType;

public final class RadarPartTypes {

    public static final PartUnitType<RadarUnit, RadarUnitData> RADAR =
            PartUnitType.Builder.<RadarUnit, RadarUnitData>of(CombinedAssault.modLocation("radar"))
                    .setFactory(RadarUnit::new)
                    .setDataSerializer((json) -> {
                        var pojo = GsonUtil.GSON.fromJson(json, RadarUnitPojo.class);
                        return new RadarUnitData(pojo);
                    })
                    .build();

    private RadarPartTypes() {
    }
}
