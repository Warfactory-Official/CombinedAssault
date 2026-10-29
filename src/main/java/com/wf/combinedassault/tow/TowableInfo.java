package com.wf.combinedassault.tow;

import com.google.gson.annotations.SerializedName;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Towed vehicle's contract; opt-in like {@code mountable} (enabled=false default, no pack towed by surprise).
 * {@link #axle}: pivot while towed. Heading = wheels->hitch line, not steered; a centre-based pivot would slide sideways instead of tracking.
 */
public class TowableInfo {

    @SerializedName("enabled")
    public boolean enabled = false;

    /** Names a hitch can match with {@code #tag}. */
    @SerializedName("tags")
    public List<String> tags = List.of();

    /** Which couplings this vehicle can be towed by. */
    @SerializedName("modes")
    public List<TowMode> modes = List.of(TowMode.CABLE);

    /** Id of a switchable part that must be off to couple (deployed trails => not towable); null => no gate. */
    @SerializedName("stowed_by")
    public String stowedBy = null;

    /** Kingpin, lunette or towing eye, blocks, own frame. */
    @SerializedName("offset")
    public Vec3 offset = Vec3.ZERO;

    /** Pivot wheels, own frame; height ignored, pivot is the ground contact patch, not the hub. */
    @SerializedName("axle")
    public Vec3 axle = Vec3.ZERO;

    public boolean supports(TowMode mode) {
        return enabled && modes.contains(mode);
    }

    /** Coupling-to-axle arm length, blocks; the tow model's turning radius. */
    public double drawbar() {
        double dx = offset.x - axle.x;
        double dz = offset.z - axle.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

}
