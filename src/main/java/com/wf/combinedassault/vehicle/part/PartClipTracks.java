package com.wf.combinedassault.vehicle.part;

import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.hit.ClipRig;
import com.wf.combinedassault.vehicle.hit.GltfReader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Part progress p in [0,1] per clip-driven part, ticked on both sides: the server poses hits by it, the client draws
 * by it. Clip {@code <part id>} or {@code <part id>.<any>}; clip time = p * clip duration.
 *
 * <table>
 *   <tr><th>part</th><th>p</th></tr>
 *   <tr><td>switchable</td><td>-> on ? 1 : 0 at 1/span per second; span = part's longest clip</td></tr>
 *   <tr><td>flaps</td><td>deflection / full</td></tr>
 *   <tr><td>linked hinge</td><td>rotation / open rotation</td></tr>
 * </table>
 */
public final class PartClipTracks {

    /** model + clip, warned once. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    public static final class Track {
        private final PartUnit<?> part;
        /** Indices into {@link ClipRig#clips}. */
        private final int[] clips;
        private final float span;
        private float progress = Float.NaN;
        private float progressO;

        private Track(PartUnit<?> part, int[] clips, float span) {
            this.part = part;
            this.clips = clips;
            this.span = span;
        }

        public PartUnit<?> part() {
            return part;
        }

        public float progress(float partialTick) {
            return switch (part) {
                case FlapUnit flaps -> Mth.clamp(flaps.level(partialTick), 0, 1);
                case LinkedHingeUnit hinge -> Mth.clamp(hinge.openness(partialTick), 0, 1);
                default -> Float.isNaN(progress) ? target() : Mth.lerp(partialTick, progressO, progress);
            };
        }

        private float target() {
            return ((SwitchableUnit<?>) part).isOn() ? 1 : 0;
        }

        private boolean tick() {
            if (!(part instanceof SwitchableUnit<?>)) {
                // Rotatable: p read off the part; progressO = last ticked value.
                float before = progressO;
                progressO = progress(1);
                return progressO != before;
            }
            float target = target();
            if (Float.isNaN(progress)) {
                progress = progressO = target;
                return true;
            }
            progressO = progress;
            float step = span <= 0 ? 1 : 1 / (span * 20);
            progress = target > progress ? Math.min(target, progress + step) : Math.max(target, progress - step);
            return progress != progressO;
        }
    }

    private final ClipRig rig;
    private final Track[] tracks;
    private int version;

    private PartClipTracks(ClipRig rig, Track[] tracks) {
        this.rig = rig;
        this.tracks = tracks;
    }

    /** Null: no clip names a progressive part. */
    @Nullable
    public static PartClipTracks bind(ClipRig rig, Map<String, PartUnit<?>> parts, String model, String vehicle) {
        Map<PartUnit<?>, List<Integer>> byPart = new LinkedHashMap<>();
        List<GltfReader.Clip> clips = rig.clips();
        for (int c = 0; c < clips.size(); c++) {
            String name = clips.get(c).name();
            int dot = name.indexOf('.');
            PartUnit<?> part = parts.get(dot < 0 ? name : name.substring(0, dot));
            if (part == null || !progressive(part)) {
                if (WARNED.add(model + "#" + name)) {
                    CombinedAssault.LOGGER.warn("{}: clip '{}' names no switchable, flaps or linked hinge part of {}; "
                            + "never plays", model, name, vehicle);
                }
                continue;
            }
            byPart.computeIfAbsent(part, key -> new ArrayList<>()).add(c);
        }
        if (byPart.isEmpty()) {
            return null;
        }
        List<Track> tracks = new ArrayList<>(byPart.size());
        byPart.forEach((part, indices) -> {
            float span = 0;
            for (int c : indices) {
                span = Math.max(span, clips.get(c).duration());
            }
            tracks.add(new Track(part, indices.stream().mapToInt(Integer::intValue).toArray(), span));
        });
        return new PartClipTracks(rig, tracks.toArray(Track[]::new));
    }

    private static boolean progressive(PartUnit<?> part) {
        return part instanceof SwitchableUnit<?> || part instanceof FlapUnit || part instanceof LinkedHingeUnit;
    }

    public ClipRig rig() {
        return rig;
    }

    @Nullable
    public Track track(String partId) {
        for (Track track : tracks) {
            if (track.part.getId().equals(partId)) {
                return track;
            }
        }
        return null;
    }

    /** After the parts tick. */
    public void tick() {
        boolean changed = false;
        for (Track track : tracks) {
            changed |= track.tick();
        }
        if (changed) {
            version++;
        }
    }

    /** Bumps whenever a ticked pose changes. */
    public int version() {
        return version;
    }

    /** Per {@link ClipRig#clips} index: seconds, NaN for a clip no part drives. */
    public float[] clipTimes(float partialTick) {
        float[] times = new float[rig.clips().size()];
        Arrays.fill(times, Float.NaN);
        for (Track track : tracks) {
            float p = track.progress(partialTick);
            for (int c : track.clips) {
                times[c] = p * rig.clips().get(c).duration();
            }
        }
        return times;
    }
}
