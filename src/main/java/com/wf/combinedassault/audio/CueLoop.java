package com.wf.combinedassault.audio;

import com.wf.wflib.audio.AudioCategory;
import com.wf.wflib.audio.SoundSpec;
import com.wf.wflib.audio.client.Cue;
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
