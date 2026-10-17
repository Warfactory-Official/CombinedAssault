package com.wf.combinedassault.vehicle.pojo;

import com.google.gson.annotations.SerializedName;

/**
 * Vehicle cargo hold: slot count, and how heavily its contents load the chassis.
 *
 * <p>Slot count, not row count: hold described by capacity, not by a screen's layout. Range
 * {@code [0, MAX_SLOTS]}; UI scrolls whatever doesn't fit.
 */
public class StorageInfo {

    /** What a pack that says nothing about storage gets. One vanilla six-row chest. */
    public static final int DEFAULT_SLOTS = 54;

    /** Ceiling on a declared hold: every slot sent on open => guards a pack typo from oversizing the packet. */
    public static final int MAX_SLOTS = 9999;

    /** Zero: vehicle has no cargo hold. */
    @SerializedName("slots")
    public int slots = DEFAULT_SLOTS;

    /** Physics kg per cargo kg ({@link com.wf.combinedassault.storage.ItemMass}). Zero: hold without load-dependent handling. */
    @SerializedName("cargo_mass_per_kg")
    public float cargoMassPerKg = 1f;

    /** Cargo kg before the engine strains; 0 = no limit. */
    @SerializedName("max_payload")
    public float maxPayload = 0f;

    public int resolvedSlots() {
        return Math.max(0, Math.min(slots, MAX_SLOTS));
    }

}
