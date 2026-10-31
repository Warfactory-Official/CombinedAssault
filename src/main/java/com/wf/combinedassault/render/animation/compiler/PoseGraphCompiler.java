package com.wf.combinedassault.render.animation.compiler;

import com.wf.combinedassault.render.model.BoneIndexProvider;
import com.wf.combinedassault.render.animation.graph.PoseGraph;
import com.wf.combinedassault.render.animation.graph.WeightSource;
import com.wf.combinedassault.render.animation.graph.node.*;
import com.wf.combinedassault.pack.client.animation.PoseNodeDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.wf.combinedassault.render.animation.graph.node.AdditiveNode;
import com.wf.combinedassault.render.animation.graph.node.BlendNode;
import com.wf.combinedassault.render.animation.graph.node.BoneBindingNode;
import com.wf.combinedassault.render.animation.graph.node.LayeredBlendNode;
import com.wf.combinedassault.render.animation.graph.node.MergeNode;
import com.wf.combinedassault.render.animation.graph.node.PoseNode;
import com.wf.combinedassault.render.animation.graph.node.ScriptPoseNode;

public class PoseGraphCompiler {

    /** Null => function not found. */
    @FunctionalInterface
    public interface ScriptNodeResolver {
        ScriptPoseNode resolve(String functionName);
    }

    private final ScriptNodeResolver scriptNodeResolver;

    private final BoneIndexProvider boneIndexProvider;

    public PoseGraphCompiler(ScriptNodeResolver scriptNodeResolver, BoneIndexProvider boneIndexProvider) {
        this.scriptNodeResolver = scriptNodeResolver;
        this.boneIndexProvider = boneIndexProvider;
    }

    public PoseGraph compile(PoseNodeDefinition rootDefinition) {
        if (rootDefinition == null) {
            throw new IllegalArgumentException("Root node definition cannot be null");
        }

        PoseNode rootNode = compileNode(rootDefinition);
        return new PoseGraph(rootNode);
    }

    private PoseNode compileNode(PoseNodeDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("Node definition cannot be null");
        }

        String type = definition.getType();
        if (type == null) {
            throw new IllegalArgumentException("Node type cannot be null");
        }

        return switch (type.toLowerCase()) {
            case "merge" -> compileMergeNode(definition);
            case "blend" -> compileBlendNode(definition);
            case "layered_blend" -> compileLayeredBlendNode(definition);
            case "additive" -> compileAdditiveNode(definition);
            case "script" -> compileScriptNode(definition);
            case "bone_binding" -> compileBoneBindingNode(definition, boneIndexProvider);
            default -> throw new IllegalArgumentException("Unknown node type: " + type);
        };
    }

    private PoseNode compileMergeNode(PoseNodeDefinition definition) {
        List<PoseNode> inputNodes = new ArrayList<>();
        if (definition.getInputs() != null) {
            for (PoseNodeDefinition inputDef : definition.getInputs()) {
                inputNodes.add(compileNode(inputDef));
            }
        }
        return new MergeNode(inputNodes);
    }

    private PoseNode compileBlendNode(PoseNodeDefinition definition) {
        PoseNodeDefinition aDef = definition.getA();
        PoseNodeDefinition bDef = definition.getB();

        if (aDef == null || bDef == null) {
            throw new IllegalArgumentException("blend node requires 'a' and 'b' fields");
        }

        PoseNode nodeA = compileNode(aDef);
        PoseNode nodeB = compileNode(bDef);
        WeightSource weightSource = compileWeightSource(definition.getWeight());

        return new BlendNode(nodeA, nodeB, weightSource);
    }

    private PoseNode compileLayeredBlendNode(PoseNodeDefinition definition) {
        PoseNodeDefinition baseDef = definition.getBase();
        if (baseDef == null) {
            throw new IllegalArgumentException("layered_blend node requires 'base' field");
        }

        PoseNode baseNode = compileNode(baseDef);
        List<LayeredBlendNode.Layer> layers = new ArrayList<>();

        if (definition.getLayers() != null) {
            for (PoseNodeDefinition.LayerDefinition layerDef : definition.getLayers()) {
                PoseNode layerPose = compileNode(layerDef.getPose());
                WeightSource weightSource = compileWeightSource(layerDef.getWeight());
                layers.add(new LayeredBlendNode.Layer(layerPose, weightSource));
            }
        }

        return new LayeredBlendNode(baseNode, layers);
    }

    private PoseNode compileAdditiveNode(PoseNodeDefinition definition) {
        PoseNodeDefinition baseDef = definition.getBase();
        PoseNodeDefinition addDef = definition.getAdd();

        if (baseDef == null || addDef == null) {
            throw new IllegalArgumentException("additive node requires 'base' and 'add' fields");
        }

        PoseNode baseNode = compileNode(baseDef);
        PoseNode addNode = compileNode(addDef);

        return new AdditiveNode(baseNode, addNode);
    }

    private PoseNode compileScriptNode(PoseNodeDefinition definition) {
        String functionName = definition.getFunction();
        if (functionName == null || functionName.isEmpty()) {
            throw new IllegalArgumentException("script node requires 'function' field");
        }

        if (scriptNodeResolver == null) {
            throw new IllegalStateException("Script node resolver not available");
        }

        ScriptPoseNode node = scriptNodeResolver.resolve(functionName);
        if (node == null) {
            throw new IllegalArgumentException("Script function not found: " + functionName);
        }

        return node;
    }

    private PoseNode compileBoneBindingNode(PoseNodeDefinition definition, BoneIndexProvider boneIndexProvider) {
        List<BoneBindingNode.CachedSpecialBinding> cachedSpecialBindings = new ArrayList<>();
        List<BoneBindingNode.CachedPartBinding> cachedPartBindings = new ArrayList<>();

        if (definition.getSpecialBindings() != null) {
            for (PoseNodeDefinition.SpecialBindingDefinition def : definition.getSpecialBindings()) {
                int[] boneIndices = def.getBones().stream()
                    .mapToInt(boneIndexProvider::getIndex)
                    .filter(idx -> idx >= 0)
                    .toArray();
                
                cachedSpecialBindings.add(new BoneBindingNode.CachedSpecialBinding(
                    boneIndices,
                    def.getSource(),
                    parseAxis(def.getAxis()),
                    def.getMultiplier(),
                    def.getMin(),
                    def.getMax(),
                    def.getParam()
                ));
            }
        }

        if (definition.getPartBindings() != null) {
            for (PoseNodeDefinition.PartBindingDefinition def : definition.getPartBindings()) {
                int boneIndex = boneIndexProvider.getIndex(def.getBone());
                if (boneIndex >= 0) {
                    cachedPartBindings.add(new BoneBindingNode.CachedPartBinding(
                        boneIndex,
                        def.getPart(),
                        def.getRotationType(),
                        parseAxis(def.getAxis()),
                        def.isInvert()
                    ));
                }
            }
        }

        return new BoneBindingNode(cachedSpecialBindings, cachedPartBindings);
    }

    private static int parseAxis(String axis) {
        if (axis == null) {
            return -1;
        }
        return switch (axis) {
            case "x" -> 0;
            case "y" -> 1;
            case "z" -> 2;
            default -> -1;
        };
    }

    private WeightSource compileWeightSource(Object weightValue) {
        if (weightValue == null) {
            return new WeightSource.Static(1.0f);
        }

        if (weightValue instanceof Number) {
            return new WeightSource.Static(((Number) weightValue).floatValue());
        }

        if (weightValue instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> weightMap = (Map<String, Object>) weightValue;
            String param = (String) weightMap.get("param");
            if (param != null) {
                return new WeightSource.Parameter(param, 0.0f);
            }
        }

        throw new IllegalArgumentException("Invalid weight value: " + weightValue);
    }
}
