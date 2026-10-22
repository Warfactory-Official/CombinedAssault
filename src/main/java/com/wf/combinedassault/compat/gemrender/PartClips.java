package com.wf.combinedassault.compat.gemrender;

import com.wf.gemrender.gltf.GemRenderGltfModel;
import com.wf.gemrender.gltf.GltfAnimation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.part.PartClipTracks;
import com.wf.combinedassault.vehicle.part.SwitchableUnit;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** GemRender clips at {@link PartClipTracks} progress; applied over the graph pose. */
@OnlyIn(Dist.CLIENT)
final class PartClips {

    private final Bound[] bound;

    private PartClips(Bound[] bound) {
        this.bound = bound;
    }

    /** Null: no clip names a tracked part. */
    /** @param part carrying part id of a mount model ({@link PartClipTracks#mountClip}); null = vehicle model */
    static PartClips bind(GemRenderGltfModel gltf, PartClipTracks tracks, @Nullable String part) {
        List<Bound> bound = new ArrayList<>();
        for (GltfAnimation clip : gltf.animations().values()) {
            PartClipTracks.Track track = tracks.track(part == null ? clip.name()
                    : PartClipTracks.mountClip(clip.name(), part));
            if (track != null) {
                bound.add(new Bound(clip, track, bound.stream().noneMatch(b -> b.track == track)));
            }
        }
        return bound.isEmpty() ? null : new PartClips(bound.toArray(Bound[]::new));
    }

    /** @return true if any progress changed */
    boolean advance(float partialTick) {
        boolean moved = false;
        for (Bound b : bound) {
            moved |= b.advance(partialTick);
        }
        return moved;
    }

    void apply(float[] state) {
        for (Bound b : bound) {
            b.clip.apply(b.progress * b.clip.duration(), state);
        }
    }

    private static final class Bound {
        final GltfAnimation clip;
        final PartClipTracks.Track track;
        /** First clip of its part: plays the toggle sound. */
        final boolean voiced;
        float progress = Float.NaN;
        Boolean on;

        Bound(GltfAnimation clip, PartClipTracks.Track track, boolean voiced) {
            this.clip = clip;
            this.track = track;
            this.voiced = voiced;
        }

        boolean advance(float partialTick) {
            if (voiced && track.part() instanceof SwitchableUnit<?> switchable) {
                if (on != null && on != switchable.isOn()) {
                    sound(switchable);
                }
                on = switchable.isOn();
            }
            float next = track.progress(partialTick);
            if (next == progress) {
                return false;
            }
            progress = next;
            return true;
        }

        private void sound(SwitchableUnit<?> switchable) {
            AbstractVehicle vehicle = switchable.getVehicle();
            vehicle.level().playSound(LocalVehiclePlayer.instance.getPlayer(), vehicle.blockPosition(),
                    switchable.isOn() ? SoundEvents.IRON_TRAPDOOR_OPEN : SoundEvents.IRON_TRAPDOOR_CLOSE,
                    SoundSource.PLAYERS, 1f, 1f);
        }
    }
}
