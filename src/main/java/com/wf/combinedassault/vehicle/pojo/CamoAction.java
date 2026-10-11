package com.wf.combinedassault.vehicle.pojo;

import com.wf.combinedassault.vehicle.camo.CamoPiece;

import java.util.ArrayList;
import java.util.List;

/**
 * One camo-layer edit; wire shape both directions.
 *
 * <p>{@link Action#PAINT}: whole stroke, not one piece => one packet per drag, not sixty.
 * {@code bones}/{@code palette} packet-local; receiver ({@link com.wf.combinedassault.vehicle.camo.CamoLayer})
 * re-interns => no shared index space needed.
 */
public class CamoAction {

    public Action action;
    public int vehicleId;

    /** Bone names this stroke touched. {@link CamoPiece#bone()} indexes into this. */
    public List<String> bones = new ArrayList<>();
    /** Serialized block states this stroke used. {@link CamoPiece#material()} indexes into this. */
    public List<String> palette = new ArrayList<>();
    public List<CamoPiece> pieces = new ArrayList<>();

    /** {@link Action#ERASE} only: the point to erase around, in the named bone's own space. */
    public String eraseBone = "";
    public float eraseX;
    public float eraseY;
    public float eraseZ;
    public float eraseRadius;

    public enum Action {
        PAINT, ERASE, CLEAR
    }
}
