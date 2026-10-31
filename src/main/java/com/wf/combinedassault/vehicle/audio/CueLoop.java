package com.wf.combinedassault.vehicle.audio;

import com.wf.wflib.audio.api.AudioCategory;
import com.wf.wflib.audio.api.SoundSpec;
import com.wf.wflib.audio.api.client.Cue;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** Looping head-locked alert switched on/off; field type in common classes (no client type in their bytecode). */
@OnlyIn(Dist.CLIENT)
public final class CueLoop {
    private final SoundSpec spec;
    private Cue cue;

    public CueLoop(SoundSpec spec) {
        this.spec = spec;
    }

    public void set(boolean on) {
        if (cue != null && (!on || cue.isStopped())) {
            cue.fadeOut(0);
            cue = null;
        }
        if (on && cue == null) {
            cue = new Cue(spec, AudioCategory.ALERTS, true);
            Minecraft.getInstance().getSoundManager().play(cue);
        }
    }
}
