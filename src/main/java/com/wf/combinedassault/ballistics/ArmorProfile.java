package com.wf.combinedassault.ballistics;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Hull faces, slope included. All-zero => unarmoured, stock damage. {@code parts}: a part's own faces in its frame
 * (turret). {@code zones}: boxes overriding both (weak spots, real plates).
 */
public class ArmorProfile extends ArmorFaces {

    /** Degrees from surface normal past which a shot ricochets; overmatch overrides. See {@link ArmorResolver}. */
    @SerializedName("ricochet_angle")
    public float ricochetAngle = 70;

    /** Thickness fraction a fully worn {@link ArmorRegions} region keeps. */
    @SerializedName("wear_floor")
    public float wearFloor = 0.6f;

    /** Part id -> faces in the part's rest frame. */
    @SerializedName("parts")
    public Map<String, ArmorFaces> parts = Map.of();

    /** First match wins. */
    @SerializedName("zones")
    public List<Zone> zones = List.of();

    /**
     * Box in model coordinates at rest ({@code part}: that part's rest pose, moves with it).
     *
     * @param plate true: real plate, incidence vs the struck surface ({@code armor_<mm>} rule); false: RHAe, slope
     *              included, incidence vs the face
     * @param ce    0 = {@code ke}
     * @param face  null = any
     */
    public record Zone(@SerializedName("name") String name, @SerializedName("part") @Nullable String part,
                       @SerializedName("face") @Nullable ArmorFace face,
                       @SerializedName("min") float[] min, @SerializedName("max") float[] max,
                       @SerializedName("ke") float ke, @SerializedName("ce") float ce,
                       @SerializedName("plate") boolean plate) {

        public boolean matches(@Nullable String part, ArmorFace face, float x, float y, float z) {
            return Objects.equals(this.part, part) && (this.face == null || this.face == face)
                    && x >= min[0] && x <= max[0] && y >= min[1] && y <= max[1] && z >= min[2] && z <= max[2];
        }

        public float nominal(boolean shapedCharge) {
            return shapedCharge && ce > 0 ? ce : ke;
        }
    }

}
