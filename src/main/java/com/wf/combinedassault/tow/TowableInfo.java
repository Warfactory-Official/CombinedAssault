package com.wf.combinedassault.tow;

import com.google.gson.annotations.SerializedName;
import com.wf.combinedassault.dynamics.structure.VehicleCubeOBB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Towed vehicle's contract; opt-in for hitch / sling ({@code enabled=false} default); cable is on for every ground vehicle unless {@link #cable} false.
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

    /** false => never cable-towed; ground vehicles otherwise take a cable by default. */
    @SerializedName("cable")
    public boolean cable = true;

    /** Ground vehicle: CABLE added to {@link #modes} (+ enabled), coupling eye at the hull front, axle at hull centre unless the pack set them. */
    public TowableInfo withCable(VehicleCubeOBB main) {
        if (!cable || enabled && modes.contains(TowMode.CABLE)) {
            return this;
        }
        TowableInfo out = new TowableInfo();
        out.enabled = true;
        out.tags = tags;
        out.stowedBy = stowedBy;
        out.cable = cable;
        out.modes = new ArrayList<>(modes);
        if (!out.modes.contains(TowMode.CABLE)) {
            out.modes.add(TowMode.CABLE);
        }
        Vector3f e = main.obb().extents();
        out.offset = offset.equals(Vec3.ZERO) ? main.offset().add(0, 0.5 - e.y, e.z) : offset;
        out.axle = axle.equals(Vec3.ZERO) ? main.offset() : axle;
        return out;
    }

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
