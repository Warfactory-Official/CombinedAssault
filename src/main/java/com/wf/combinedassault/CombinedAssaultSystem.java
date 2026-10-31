package com.wf.combinedassault;

import com.wf.wflib.core.api.WFSystem;
import com.wf.wflib.core.api.WFSystems;

/** A CombinedAssault system: on/off in {@code combinedassault-systems.toml}; own ids bare in {@link #requires()}, wflib's {@code wflib:}-qualified. */
public interface CombinedAssaultSystem extends WFSystem {

    @Override
    default boolean enabled() {
        return WFSystems.enabled(CombinedAssault.MOD_ID + ":" + id());
    }
}
