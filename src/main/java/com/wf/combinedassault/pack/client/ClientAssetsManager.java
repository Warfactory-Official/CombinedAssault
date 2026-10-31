package com.wf.combinedassault.pack.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;
import org.mozillaa.javascript.Script;
import com.wf.combinedassault.pack.script.ScriptContextFactory;
import com.wf.combinedassault.pack.client.animation.AnimationControllerDefinition;
import com.wf.combinedassault.pack.client.vehicle.BaseDisplay;
import com.wf.combinedassault.pack.client.vehicle.VehicleDisplay;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public enum ClientAssetsManager {

    INSTANCE;
    private AnimationControllerDefinitionManager animationControllerDefinitionManager;
    private DisplayManager vehicleDisplayManager;
    private DisplayManager weaponDisplayManager;
    private DisplayManager decorationDisplayManager;
    private ScriptManager scriptManager;

    public void registerListeners(Consumer<PreparableReloadListener> consumer) {
        animationControllerDefinitionManager = new AnimationControllerDefinitionManager();
        scriptManager = new ScriptManager();

        vehicleDisplayManager = new DisplayManager("vehicle", scriptManager, ScriptContextFactory.get());
        weaponDisplayManager = new DisplayManager("weapon", scriptManager, ScriptContextFactory.get());
        decorationDisplayManager = new DisplayManager("decoration", scriptManager, ScriptContextFactory.get());

        consumer.accept(animationControllerDefinitionManager);
        consumer.accept(scriptManager);
        consumer.accept(vehicleDisplayManager);
        consumer.accept(weaponDisplayManager);
        consumer.accept(decorationDisplayManager);
    }

    @OnlyIn(Dist.CLIENT)
    public void reload(ResourceManager resourceManager) {
        animationControllerDefinitionManager.apply(animationControllerDefinitionManager.prepare(resourceManager, null), null, null);
        scriptManager.apply(scriptManager.prepare(resourceManager, null), null, null);
        vehicleDisplayManager.apply(vehicleDisplayManager.prepare(resourceManager, null), null, null);
        weaponDisplayManager.apply(weaponDisplayManager.prepare(resourceManager, null), null, null);
        decorationDisplayManager.apply(decorationDisplayManager.prepare(resourceManager, null), null, null);
    }

    public Map<ResourceLocation, BaseDisplay> getVehicleDisplays() {
        return vehicleDisplayManager != null ? vehicleDisplayManager.getDisplayMap() : null;
    }

    @NotNull
    public List<BaseDisplay> getVariableDisplay(ResourceLocation model) {
        return vehicleDisplayManager.getModelWithVariableDisplay().get(model);
    }

    @NotNull
    public Optional<VehicleDisplay<?, ?>> getVehicleDisplay(ResourceLocation id) {
        if (vehicleDisplayManager == null) {
            return Optional.empty();
        }
        BaseDisplay display = vehicleDisplayManager.getDisplayMap().get(id);
        return display instanceof VehicleDisplay<?, ?> vehicleDisplay
                ? Optional.of(vehicleDisplay)
                : Optional.empty();
    }

    @NotNull
    public Optional<BaseDisplay> getWeaponDisplay(ResourceLocation id) {
        if (weaponDisplayManager == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(weaponDisplayManager.getDisplayMap().get(id));
    }

    @NotNull
    public Optional<BaseDisplay> getDecorationDisplay(ResourceLocation id) {
        if (decorationDisplayManager == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(decorationDisplayManager.getDisplayMap().get(id));
    }

    @NotNull
    public List<BaseDisplay> getDecorationDisplays() {
        if (decorationDisplayManager == null) {
            return List.of();
        }
        return new ArrayList<>(decorationDisplayManager.getDisplayMap().values());
    }

    public Optional<AnimationControllerDefinition> getAnimationControllerDefinition(ResourceLocation id) {
        if (animationControllerDefinitionManager == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(animationControllerDefinitionManager.getData(id));
    }

    public ScriptManager getScriptManager() {
        return scriptManager;
    }

    public Optional<Script> getScript(ResourceLocation script) {
        return scriptManager.getScript(script);
    }

}
