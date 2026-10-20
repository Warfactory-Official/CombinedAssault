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
 * Part progress p in [0,1] per clip-driven track, ticked on both sides: the server poses hits by it, the client draws
 * by it. Clip {@code <part id>} or {@code <part id>.<any>}; clip time = p * clip duration.
 *
 * <table>
 *   <tr><th>part</th><th>clip</th><th>p</th></tr>
 *   <tr><td>switchable</td><td>any</td><td>-> on ? 1 : 0 at 1/span per second; span = track's longest clip</td></tr>
 *   <tr><td>flaps</td><td>any</td><td>deflection / full</td></tr>
 *   <tr><td>linked hinge</td><td>any</td><td>rotation / open rotation</td></tr>
 *   <tr><td>weapon</td><td>{@code .fire}</td><td>time since current weapon's last shot / span; never fired => 1</td></tr>
 *   <tr><td>weapon</td><td>{@code .reload}</td><td>current weapon's reload progress; not reloading => 0; no
 *   {@code .unjam} clip => max with jam clear progress</td></tr>
 *   <tr><td>weapon</td><td>{@code .unjam}</td><td>current weapon's jam clear progress; not clearing => 0</td></tr>
 * </table>
 */
public final class PartClipTracks {

    /** model + clip, warned once. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private enum Kind { TOGGLE, FLAPS, HINGE, FIRE, RELOAD, UNJAM }

    public static final class Track {
        private final PartUnit<?> part;
        private final Kind kind;
        /** Indices into {@link ClipRig#clips}. */
        private final int[] clips;
        private final float span;
        /** RELOAD of a part without an UNJAM track. */
        private boolean clearsJams;
        private float progress = Float.NaN;
        private float progressO;

        private Track(PartUnit<?> part, Kind kind, int[] clips, float span) {
            this.part = part;
            this.kind = kind;
            this.clips = clips;
            this.span = span;
        }

        public PartUnit<?> part() {
            return part;
        }

        /** Longest clip, seconds. */
        public float span() {
            return span;
        }

        public float progress(float partialTick) {
            return switch (kind) {
                case FLAPS -> Mth.clamp(((FlapUnit) part).level(partialTick), 0, 1);
                case HINGE -> Mth.clamp(((LinkedHingeUnit) part).openness(partialTick), 0, 1);
                case FIRE -> ((WeaponUnit) part).getCurrentWeapon()
                        .map(weapon -> span <= 0 ? 1 : Mth.clamp(weapon.secondsSinceShot(partialTick) / span, 0, 1))
                        .orElse(1f);
                case RELOAD -> ((WeaponUnit) part).getCurrentWeapon()
                        .map(weapon -> clearsJams && weapon.barrel() != null
                                ? Math.max(weapon.reloadProgress(partialTick), weapon.barrel().clearProgress(partialTick))
                                : weapon.reloadProgress(partialTick)).orElse(0f);
                case UNJAM -> ((WeaponUnit) part).getCurrentWeapon()
                        .map(weapon -> weapon.barrel() == null ? 0 : weapon.barrel().clearProgress(partialTick))
                        .orElse(0f);
                case TOGGLE -> Float.isNaN(progress) ? target() : Mth.lerp(partialTick, progressO, progress);
            };
        }

        private float target() {
            return ((SwitchableUnit<?>) part).isOn() ? 1 : 0;
        }

        private boolean tick() {
            if (kind != Kind.TOGGLE) {
                // p read off the part; progressO = last ticked value.
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
        record Key(PartUnit<?> part, Kind kind) {}
        Map<Key, List<Integer>> byTrack = new LinkedHashMap<>();
        List<GltfReader.Clip> clips = rig.clips();
        for (int c = 0; c < clips.size(); c++) {
            String name = clips.get(c).name();
            PartUnit<?> part = parts.get(partId(name));
            Kind kind = part == null ? null : kind(part, suffix(name));
            if (kind == null) {
                if (WARNED.add(model + "#" + name)) {
                    CombinedAssault.LOGGER.warn("{}: clip '{}' names no switchable, flaps or linked hinge part, nor a "
                            + "weapon part's .fire / .reload / .unjam, of {}; never plays", model, name, vehicle);
                }
                continue;
            }
            byTrack.computeIfAbsent(new Key(part, kind), key -> new ArrayList<>()).add(c);
        }
        if (byTrack.isEmpty()) {
            return null;
        }
        List<Track> tracks = new ArrayList<>(byTrack.size());
        byTrack.forEach((key, indices) -> {
            float span = 0;
            for (int c : indices) {
                span = Math.max(span, clips.get(c).duration());
            }
            tracks.add(new Track(key.part(), key.kind(), indices.stream().mapToInt(Integer::intValue).toArray(), span));
        });
        for (Track track : tracks) {
            track.clearsJams = track.kind == Kind.RELOAD && !byTrack.containsKey(new Key(track.part, Kind.UNJAM));
        }
        return new PartClipTracks(rig, tracks.toArray(Track[]::new));
    }

    private static String partId(String clip) {
        int dot = clip.indexOf('.');
        return dot < 0 ? clip : clip.substring(0, dot);
    }

    private static String suffix(String clip) {
        int dot = clip.indexOf('.');
        return dot < 0 ? "" : clip.substring(dot + 1);
    }

    @Nullable
    private static Kind kind(PartUnit<?> part, String suffix) {
        return switch (part) {
            case SwitchableUnit<?> switchable -> Kind.TOGGLE;
            case FlapUnit flaps -> Kind.FLAPS;
            case LinkedHingeUnit hinge -> Kind.HINGE;
            case WeaponUnit weapon when suffix.equals("fire") -> Kind.FIRE;
            case WeaponUnit weapon when suffix.equals("reload") -> Kind.RELOAD;
            case WeaponUnit weapon when suffix.equals("unjam") -> Kind.UNJAM;
            default -> null;
        };
    }

    public ClipRig rig() {
        return rig;
    }

    /** @param name part id, or clip name: a weapon part's {@code .fire} / {@code .reload} picks that track. */
    @Nullable
    public Track track(String name) {
        String partId = partId(name);
        String suffix = suffix(name);
        for (Track track : tracks) {
            if (track.part.getId().equals(partId) && switch (track.kind) {
                case FIRE -> suffix.equals("fire");
                case RELOAD -> suffix.equals("reload");
                case UNJAM -> suffix.equals("unjam");
                default -> true;
            }) {
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
