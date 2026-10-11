package com.wf.combinedassault.tow;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Everything a pack says about what a vehicle can pull and what can pull it. */
public class TowInfo {

    public static final TowInfo NONE = new TowInfo();

    @SerializedName("hitches")
    public List<HitchInfo> hitches = List.of();

    @SerializedName("towable")
    public TowableInfo towable = new TowableInfo();

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
