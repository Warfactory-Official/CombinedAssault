package com.wf.combinedassault.client.resource.vehicle;

import com.wf.combinedassault.client.model.BoneIndexProvider;
import org.mozillaa.javascript.*;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.api.scripts.ScriptContextFactory;
import com.wf.combinedassault.client.render.animation.VehicleAnimationInstance;
import com.wf.combinedassault.client.render.animation.compiler.*;
import com.wf.combinedassault.client.render.animation.context.AnimationContextFactory;
import com.wf.combinedassault.client.render.animation.context.VehicleContext;
import com.wf.combinedassault.client.render.animation.controller.AnimationController;
import com.wf.combinedassault.client.render.animation.graph.node.ScriptPoseNode;
import com.wf.combinedassault.client.render.animation.util.PoseHelper;
import com.wf.combinedassault.client.resource.ClientAssetsManager;
import com.wf.combinedassault.client.resource.ScriptManager;
import com.wf.combinedassault.client.resource.animation.AnimationControllerDefinition;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

import java.util.*;

public class VehicleDisplay<E extends AbstractVehicle, CTX extends VehicleContext<E>> extends BaseDisplay {

    protected AnimationController<CTX> animationController;
    protected AnimationContextFactory<E, CTX> contextFactory;
    protected Scriptable scriptScope;
    protected Map<String, Function> scriptFunctions = new HashMap<>();
    protected VehicleDisplay<E, CTX> cabinDisplay;

    public VehicleDisplay(VehicleDisplayPojo pojo) {
        super(pojo);
        if (pojo.cabinModel != null) {
            pojo.model = pojo.cabinModel;
            pojo.cabinModel = null;
            cabinDisplay = new VehicleDisplay<>(pojo);
        }
    }

    public void initializeAnimationController(ScriptManager scriptManager, ScriptContextFactory scriptContextFactory) {
        if (animationControllerPath == null) {
            return;
        }

        Optional<AnimationControllerDefinition> definitionOpt = ClientAssetsManager.INSTANCE.getAnimationControllerDefinition(animationControllerPath);
        if (definitionOpt.isEmpty()) {
            CombinedAssault.LOGGER.warn("Animation controller definition not found: {}", animationControllerPath);
            return;
        }

        AnimationController<CTX> controller = compileAnimationController(
            definitionOpt.get(), scriptManager, scriptContextFactory
        );
        
        if (controller != null) {
            this.animationController = controller;
        }
    }

    protected AnimationController<CTX> compileAnimationController(AnimationControllerDefinition definition,
                                                                  ScriptManager scriptManager,
                                                                  ScriptContextFactory scriptContextFactory) {
        BoneIndexProvider animationIndexProvider = getAnimationIndexProvider();
        if (animationIndexProvider == null) {
            CombinedAssault.LOGGER.error("Unable to compile animation controller without a model index provider: {}", animationControllerPath);
            return null;
        }

        try {
            Script compiledScript = null;
            if (definition.getScript() != null) {
                Optional<Script> scriptOpt = scriptManager.getScript(definition.getScript());
                if (scriptOpt.isPresent()) {
                    compiledScript = scriptOpt.get();
                } else {
                    CombinedAssault.LOGGER.warn("Script not found: {}", definition.getScript());
                }
            }

            if (compiledScript != null) {
                initializeScriptScope(compiledScript, scriptContextFactory);
            }

            PoseGraphCompiler.ScriptNodeResolver scriptNodeResolver = functionName -> {
                Function function = scriptFunctions.get(functionName);
                if (function != null) {
                    return new ScriptPoseNode(function, scriptScope);
                }
                if (scriptScope != null) {
                    Object obj = scriptScope.get(functionName, scriptScope);
                    if (obj instanceof Function func) {
                        scriptFunctions.put(functionName, func);
                        return new ScriptPoseNode(func, scriptScope);
                    }
                }
                return null;

            };

            AnimationControllerCompiler controllerCompiler = new AnimationControllerCompiler(scriptNodeResolver,
                    animationIndexProvider);

            return controllerCompiler.compile(definition);
        } catch (Exception e) {
            CombinedAssault.LOGGER.warn("Failed to compile animation controller", e);
            return null;
        }
    }

    protected void initializeScriptScope(Script compiledScript, ScriptContextFactory scriptContextFactory) {
        try (Context cx = scriptContextFactory.enterContext()) {
            this.scriptScope = scriptContextFactory.createScope(cx);

            BaseFunction createPoseHelperFunc = new BaseFunction() {
                @Override
                public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
                    BoneIndexProvider animationIndexProvider = VehicleDisplay.this.getAnimationIndexProvider();
                    PoseHelper helper = animationIndexProvider == null
                            ? PoseHelper.DUMMY
                            : new PoseHelper(animationIndexProvider);
                    return Context.javaToJS(helper, scope);
                }
            };
            scriptScope.put("createPoseBuilder", scriptScope, createPoseHelperFunc);

            compiledScript.exec(cx, scriptScope);
        } catch (Exception e) {
            CombinedAssault.LOGGER.warn("Failed to initialize script scope", e);
        }
    }

    public VehicleAnimationInstance<CTX> createAnimationInstance(E entity) {
        if (animationController == null || contextFactory == null) {
            return null;
        }

        CTX context = contextFactory.create(entity);
        VehicleAnimationInstance<CTX> vehicleAnimationInstance = new VehicleAnimationInstance<>(animationController, context);
        if (cabinDisplay != null) {
            vehicleAnimationInstance.setCabinAnimationInstance(cabinDisplay.createAnimationInstance(entity));
        }
        return vehicleAnimationInstance;
    }

    public void setContextFactory(AnimationContextFactory<E, CTX> factory) {
        this.contextFactory = factory;
    }

    public VehicleDisplay<E, CTX> getCabinDisplay() {
        return cabinDisplay;
    }

}
