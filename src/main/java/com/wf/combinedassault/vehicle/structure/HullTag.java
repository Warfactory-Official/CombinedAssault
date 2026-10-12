package com.wf.combinedassault.vehicle.structure;

/**
 * What a collision piece is beyond its shape. Systems, plates and pools live in the display glTF now
 * ({@code hit_*} nodes, {@code armor_<mm>} materials; {@code vehicle.hit.ModelDataManager}).
 *
 * @param name  debug label only
 * @param solid false => shot at and bounds the hull, but not stood on or collided with (mudguard, aerial)
 * @param world false => collides with vehicles only
 */
public record HullTag(String name, boolean solid, boolean world) {

    public static final HullTag DEFAULT = new HullTag("", true, true);

    public HullTag {
        name = name == null ? "" : name;
    }
}
