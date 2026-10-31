package com.wf.combinedassault.mixin.render;

import com.wf.combinedassault.CombinedAssault;
import com.wf.wflib.core.api.WFSystems;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.entity.EquipmentSlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.wf.combinedassault.cargo.CargoContent;

@Mixin(PlayerRenderer.class)
public class PlayerRendererMixin {

    @WrapOperation(
            method = "setupRotations",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/AbstractClientPlayer;isFallFlying()Z")
    )
    private boolean combinedassault$keepParachutingPlayerUpright(AbstractClientPlayer player, Operation<Boolean> original) {
        boolean fallFlying = original.call(player);
        return fallFlying && !(WFSystems.enabled(CombinedAssault.MOD_ID + ":cargo")
                && player.getItemBySlot(EquipmentSlot.CHEST).is(CargoContent.PARACHUTE_PACK.get()));
    }

}
