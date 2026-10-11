package com.wf.combinedassault.client.render.animation;

import com.maydaymemory.mae.basic.DummyPose;
import com.maydaymemory.mae.basic.Pose;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.jetbrains.annotations.NotNull;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.api.animation.IAnimationInstance;
import com.wf.combinedassault.client.render.animation.context.BaseAnimationContext;
import com.wf.combinedassault.client.render.animation.context.EntityContext;
import com.wf.combinedassault.client.render.animation.controller.AnimationController;

public class VehicleAnimationInstance<T extends BaseAnimationContext> implements IAnimationInstance<T> {

    private final T context;
    private final AnimationController<T> controller;
    private VehicleAnimationInstance<T> cabinAnimationInstance;
    private boolean isError = false;

    public VehicleAnimationInstance(AnimationController<T> controller, @NotNull T context) {
        this.controller = controller;
        this.context = context;
        controller.initialize(context);
    }

    public void tick() {
        context.tick();
        context.clearEvents();
    }

    @NotNull
    public Pose getCurrentPose() {
        if (isError) {
            return DummyPose.INSTANCE;
        }
        try {
            Pose pose = controller.getPoseGraph().evaluate(this);
            return pose != null ? pose : DummyPose.INSTANCE;
        } catch (Exception e) {
            isError = true;
            CombinedAssault.LOGGER.error("Error evaluating animation pose", e);
            Minecraft mc = Minecraft.getInstance();

            if (mc.player != null) {
                var msg = Component.literal("Error evaluating animation pose, check logs for detail")
                        .withStyle(ChatFormatting.RED)
                        .withStyle((s)->s.withHoverEvent(
                                new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(e.getMessage()))
                        ));
                if (this.getContext() instanceof EntityContext<?> entityContext && entityContext.getEntity() != null) {
                    msg.append(" (").append(entityContext.getEntity().toString()).append(")");
                }
                mc.player.sendSystemMessage(msg);
            }
            return DummyPose.INSTANCE;
        }
    }

    @NotNull
    public T getContext() {
        return context;
    }

    public AnimationController<T> getController() {
        return controller;
    }

    public VehicleAnimationInstance<T> getCabinAnimationInstance() {
        return cabinAnimationInstance;
    }

    public void setCabinAnimationInstance(VehicleAnimationInstance<T> cabinAnimationInstance) {
        this.cabinAnimationInstance = cabinAnimationInstance;
    }

}
