package com.wf.combinedassault.render.animation.graph.node;

import com.maydaymemory.mae.basic.Pose;
import com.wf.combinedassault.pack.IAnimationInstance;
import com.wf.combinedassault.render.animation.graph.WeightSource;
import com.wf.combinedassault.render.animation.util.PoseBlenders;

import java.util.List;

public class LayeredBlendNode implements PoseNode {

    private final PoseNode baseNode;
    private final List<Layer> layers;

    public LayeredBlendNode(PoseNode baseNode, List<Layer> layers) {
        this.baseNode = baseNode;
        this.layers = List.copyOf(layers);
    }

    /**
     * Layers compose base->top, in list order (lerp not associative: order matters).
     * Null base => null result, layers skipped; null layer pose => that layer skipped.
     */
    @Override
    public Pose evaluate(IAnimationInstance<?> context) {
        Pose result = baseNode.evaluate(context);
        if (result == null) {
            return null;
        }

        for (Layer layer : layers) {
            Pose layerPose = layer.poseNode.evaluate(context);
            if (layerPose == null) {
                continue;
            }

            float weight = layer.weightSource.getWeight(context);
            weight = Math.max(0.0f, Math.min(1.0f, weight));

            result = PoseBlenders.INTERPOLATOR_BLENDER.blend(result, layerPose, weight);
        }

        return result;
    }

    public record Layer(PoseNode poseNode, WeightSource weightSource) {}

}
