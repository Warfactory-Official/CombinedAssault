package com.wf.combinedassault.vehicle.pojo;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * Vehicle's side of the mounting contract: mountable at all, and what a mount point can call it by.
 *
 * <p>Opt-in; default false => existing packs don't suddenly let tanks bolt onto motorcycles.
 */
public class MountableInfo {

    @SerializedName("enabled")
    public boolean enabled = false;

    /** Names a mount point's {@code #tag} can match: accept "any mortar" without enumerating every mortar id. */
    @SerializedName("tags")
    public List<String> tags = List.of();

}
