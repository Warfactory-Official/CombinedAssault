package com.wf.combinedassault.pack.client.vehicle;

import com.wf.wflib.audio.api.EngineSound;
import com.wf.combinedassault.render.model.BoneIndexProvider;
import com.wf.combinedassault.render.model.VehicleModel;
import com.wf.combinedassault.render.gem.GemRenderModelLoader;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;


/** Client half of a pack def: model, icon, controller, engine audio. Built during resource reload. */
public class BaseDisplay {

    protected ResourceLocation displayId;
    protected ResourceLocation modelPath;
    @Nullable
    protected VehicleModel vehicleModel;
    protected ResourceLocation slotTexture;
    @Nullable
    protected ResourceLocation paint;
    protected ResourceLocation animationControllerPath;
    @Nullable
    protected EngineSound engine;
    protected float cabinMuffle;
    protected String description;
    protected int tabIndex;

    public BaseDisplay(BaseDisplayPojo pojo) {
        this.modelPath = pojo.model;
        this.vehicleModel = pojo.model == null ? null : GemRenderModelLoader.load(pojo.model);
        this.slotTexture = pojo.slotTexture;
        this.paint = pojo.paint;
        this.animationControllerPath = pojo.animationController;
        this.engine = pojo.engine;
        this.cabinMuffle = pojo.cabinMuffle;
        this.description = pojo.description;
        this.tabIndex = pojo.tabIndex;
    }

    @Nullable
    protected BoneIndexProvider getAnimationIndexProvider() {
        return vehicleModel == null ? null : vehicleModel.boneIndexProvider();
    }

    public ResourceLocation getDisplayId() {
        return displayId;
    }

    public void setDisplayId(ResourceLocation displayId) {
        this.displayId = displayId;
    }

    public ResourceLocation getModelPath() {
        return modelPath;
    }

    public ResourceLocation getSlotTexture() {
        return slotTexture;
    }

    @Nullable
    public ResourceLocation getPaint() {
        return paint;
    }

    @Nullable
    public VehicleModel getVehicleModel() {
        return vehicleModel;
    }

    public ResourceLocation getAnimationControllerPath() {
        return animationControllerPath;
    }

    @Nullable
    public EngineSound getEngine() {
        return engine;
    }

    public float getCabinMuffle() {
        return cabinMuffle;
    }

    public String getDescription() {
        return description;
    }

    public int getTabIndex() {
        return tabIndex;
    }
}
