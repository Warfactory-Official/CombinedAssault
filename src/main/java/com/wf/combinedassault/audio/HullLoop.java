package com.wf.combinedassault.audio;

import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.wflib.audio.AudioCategory;
import com.wf.wflib.audio.EngineSound;
import com.wf.wflib.audio.SoundSpec;
import com.wf.wflib.audio.client.Emitter;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/** One loop on a hull point, gain slewed toward on/off by {@code fade} per tick; owner ticks it every client tick. */
@OnlyIn(Dist.CLIENT)
public final class HullLoop {
    private final SoundSpec spec;
    private final AudioCategory category;
    private final float fade;
    private Emitter emitter;
    private float gain;

    public HullLoop(SoundSpec spec, AudioCategory category, float fade) {
        this.spec = spec;
        this.category = category;
        this.fade = fade;
    }

    /** {@code offset}: vehicle-local. */
    public void tick(AbstractVehicle vehicle, Vec3 offset, boolean on, float pitchScale) {
        gain += Math.clamp((on ? 1 : 0) - gain, -fade, fade);
        if (emitter != null && (emitter.isStopped() || gain <= EngineSound.SILENT)) {
            emitter.end();
            emitter = null;
        }
        if (gain <= EngineSound.SILENT) {
            return;
        }
        if (emitter == null) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.gameRenderer.getMainCamera().getPosition().distanceTo(vehicle.position()) >= spec.range()) {
                return;
            }
            emitter = Emitter.tracking(spec, category,
                    () -> vehicle.isRemoved() ? null : vehicle.relativeRotPos(vehicle.position().add(offset), false), true);
            emitter.setLevel(gain);
            emitter.setPitchScale(pitchScale);
            emitter.refresh();
            mc.getSoundManager().play(emitter);
        }
        emitter.setLevel(gain);
        emitter.setPitchScale(pitchScale);
    }

    public void stop() {
        if (emitter != null) {
            emitter.end();
            emitter = null;
        }
        gain = 0;
    }
}
