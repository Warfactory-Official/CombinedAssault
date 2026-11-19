package com.wf.combinedassault.dynamics.part;

import com.google.gson.Gson;
import com.wf.combinedassault.dynamics.part.data.SuspensionUnitData;
import com.wf.combinedassault.dynamics.part.data.SuspensionUnitPojo;
import com.wf.combinedassault.dynamics.part.data.TrackUnitPojo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DrivenWheelFlagsTest {
    private static final Gson GSON = new Gson();

    @Test
    void unspecifiedFlagsStayNull() {
        SuspensionUnitData data = new SuspensionUnitData(GSON.fromJson("{}", SuspensionUnitPojo.class));
        assertNull(data.driven());
        assertNull(data.handbrake());
    }

    @Test
    void wheelFlagsParse() {
        SuspensionUnitData data = new SuspensionUnitData(GSON.fromJson("{\"driven\":true,\"handbrake\":false}", SuspensionUnitPojo.class));
        assertEquals(Boolean.TRUE, data.driven());
        assertEquals(Boolean.FALSE, data.handbrake());
    }

    @Test
    void trackPartsCarryTheSameFlags() {
        TrackUnitPojo pojo = GSON.fromJson("{\"driven\":true}", TrackUnitPojo.class);
        assertEquals(Boolean.TRUE, pojo.driven);
        assertNull(pojo.handbrake);
    }
}
