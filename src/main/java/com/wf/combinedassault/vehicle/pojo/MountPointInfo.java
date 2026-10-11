package com.wf.combinedassault.vehicle.pojo;

import com.google.gson.annotations.SerializedName;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Declared place on a vehicle where another vehicle bolts down.
 *
 * <p>!= deck: deck geometry is driven onto, physics decides what happens; a mount point holds its payload
 * fixed regardless of balance (jeep-in-transport vs mortar-welded-to-pickup; only the welded one can be
 * crewed/fired).
 */
public class MountPointInfo {

    /** Name of the point, unique within the vehicle. Also what a mounted vehicle records to find it again. */
    @SerializedName("id")
    public String id = "";

    /** Mounted vehicle's origin, in blocks, in the carrier's own frame; same convention as {@code seat_offset}. */
    @SerializedName("offset")
    public Vec3 offset = Vec3.ZERO;

    /** Facing of the mounted vehicle relative to the carrier, in degrees. */
    @SerializedName("yaw")
    public float yaw = 0;

    @SerializedName("pitch")
    public float pitch = 0;

    @SerializedName("roll")
    public float roll = 0;

    /** What may mount here: vehicle ids, or {@code #tag} matching a mountable's tag. Empty: anything mountable. */
    @SerializedName("accepts")
    public List<String> accepts = List.of();

    /** Heaviest vehicle this point takes, kg ({@code physics_info.mass}). Zero: no limit. */
    @SerializedName("max_mass")
    public float maxMass = 0;

    public boolean accepts(String vehicleId, List<String> tags) {
        if (accepts.isEmpty()) {
            return true;
        }
        for (String accepted : accepts) {
            if (accepted.startsWith("#")) {
                if (tags.contains(accepted.substring(1))) {
                    return true;
                }
            } else if (accepted.equals(vehicleId)) {
                return true;
            }
        }
        return false;
    }

}
