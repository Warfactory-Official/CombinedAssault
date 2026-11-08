package com.wf.combinedassault.vehicle.screen;

import com.wf.wflib.ui.api.UiContext;

/** What a vehicle screen's layout reads; refreshed by the renderer while the screen draws. */
public interface ScreenView extends UiContext {
    void refresh(float partialTick);
}
