package com.wf.combinedassault.fpv.payload;

import com.wf.combinedassault.custom.part.PartUnitType;
import com.wf.combinedassault.custom.serialize.GsonUtil;
import com.wf.combinedassault.CombinedAssault;

/** Hardpoint part-unit type; registered in {@code PartUnitTypes} with the rest. */
public final class FpvPayloadUnitType {

    public static final PartUnitType<PayloadUnit, PayloadUnitData> PAYLOAD =
            PartUnitType.Builder.<PayloadUnit, PayloadUnitData>of(CombinedAssault.modLocation("payload"))
                    .setFactory(PayloadUnit::new)
                    .setDataSerializer(json -> {
                        var pojo = GsonUtil.GSON.fromJson(json, PayloadUnitPojo.class);
                        return new PayloadUnitData(pojo);
                    })
                    .build();

    private FpvPayloadUnitType() {
    }

}
