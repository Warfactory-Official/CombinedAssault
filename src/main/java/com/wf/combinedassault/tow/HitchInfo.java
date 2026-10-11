package com.wf.combinedassault.tow;

import com.google.gson.annotations.SerializedName;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Tractor's hitch: shaped like a mount point but holds no pose; towed vehicle's position is solved per tick from here + ground. */
public class HitchInfo {

    /** Unique per vehicle; saved links reference it by this id. */
    @SerializedName("id")
    public String id = "";

    /** Translation key for the readout; empty => falls back to id. */
    @SerializedName("name")
    public String name = "";

    /** Joint position, blocks, tractor's own frame (cf. {@code seat_offset}). */
    @SerializedName("offset")
    public Vec3 offset = Vec3.ZERO;

    /** Couplings this hitch offers; empty list => hitch unusable. */
    @SerializedName("modes")
    public List<TowMode> modes = List.of(TowMode.HITCH);

    /** Vehicle ids or {@code #tag}; empty accepts any towable. */
    @SerializedName("accepts")
    public List<String> accepts = List.of();

    /** Max train mass behind hitch, kg; 0 = unlimited. */
    @SerializedName("max_mass")
    public float maxMass = 0;

    /** Cable paid out when used on a CABLE coupling, blocks. */
    @SerializedName("cable_length")
    public float cableLength = 8;

    public boolean supports(TowMode mode) {
        return modes.contains(mode);
    }

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
