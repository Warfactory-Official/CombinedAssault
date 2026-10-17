package com.wf.combinedassault.vehicle.pojo;

import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class WeaponInfo {

    @Nullable
    @SerializedName("id")
    public ResourceLocation id;

    @Nullable
    @SerializedName("part_unit_id")
    public String partUnitId;

    @Nullable
    @SerializedName("weapon_bay_unit_id")
    public String weaponBayUnitId;

    @SerializedName("secondary")
    public boolean secondary;

    @SerializedName("full_salvo")
    public boolean fullSalvo;

    @SerializedName("save_id")
    public String saveId;

    /** Model bones showing this weapon's loaded rounds, firing order; drawn as the loaded round's mesh. */
    @SerializedName("round_bones")
    public List<String> roundBones = List.of();

    public WeaponInfo(@Nullable ResourceLocation id, @Nullable String partUnitId, @Nullable String weaponBayUnitId,
                      boolean secondary, boolean fullSalvo, String saveId) {
        this.id = id;
        this.partUnitId = partUnitId;
        this.weaponBayUnitId = weaponBayUnitId;
        this.secondary = secondary;
        this.fullSalvo = fullSalvo;
        this.saveId = saveId;
    }

}
