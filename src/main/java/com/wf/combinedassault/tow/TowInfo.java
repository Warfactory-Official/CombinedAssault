package com.wf.combinedassault.tow;

import com.google.gson.annotations.SerializedName;
import com.wf.combinedassault.dynamics.structure.VehicleCubeOBB;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** Everything a pack says about what a vehicle can pull and what can pull it. */
public class TowInfo {

    public static final TowInfo NONE = new TowInfo();

    @SerializedName("hitches")
    public List<HitchInfo> hitches = List.of();

    @SerializedName("towable")
    public TowableInfo towable = new TowableInfo();

    /** Aircraft: cable-towable by default, no rope of its own. */
    public TowInfo forAircraft(VehicleCubeOBB main) {
        TowInfo out = new TowInfo();
        out.towable = towable.withCable(main);
        out.hitches = hitches;
        return out;
    }

    public static final String ROPE_ID = "tow_rope";

    /** Ground vehicle: cable-towable by default; without its own cable hitch also gets a rope off the rear of the hull; no max_mass, the joint load slows it. */
    public TowInfo forGround(VehicleCubeOBB main) {
        TowInfo out = new TowInfo();
        out.towable = towable.withCable(main);
        out.hitches = hitches;
        for (HitchInfo hitch : hitches) {
            if (hitch.supports(TowMode.CABLE)) {
                return out;
            }
        }
        HitchInfo rope = new HitchInfo();
        rope.id = ROPE_ID;
        rope.name = "hitch.combinedassault.tow_rope";
        rope.modes = List.of(TowMode.CABLE);
        rope.cableLength = 8;
        Vector3f e = main.obb().extents();
        rope.offset = main.offset().add(0, 0.5 - e.y, -e.z);
        out.hitches = new ArrayList<>(hitches);
        out.hitches.add(rope);
        return out;
    }

    @Nullable
    public HitchInfo hitch(String id) {
        for (HitchInfo hitch : hitches) {
            if (hitch.id.equals(id)) {
                return hitch;
            }
        }
        return null;
    }

}
