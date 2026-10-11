package com.wf.combinedassault.mixin.common;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.wf.combinedassault.vehicle.OversizedVehicles;

import java.util.List;
import java.util.function.Predicate;

/** Section walk misses long hulls; appended via {@link OversizedVehicles}. */
@Mixin(Level.class)
public abstract class LevelMixin {

    @Inject(method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
            at = @At("RETURN"))
    private void combinedassault$appendOversized(Entity entity, AABB box, Predicate<? super Entity> predicate,
                                              CallbackInfoReturnable<List<Entity>> cir) {
        OversizedVehicles.append((Level) (Object) this, entity, box, predicate, cir.getReturnValue());
    }

    @Inject(method = "getEntities(Lnet/minecraft/world/level/entity/EntityTypeTest;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;Ljava/util/List;I)V",
            at = @At("RETURN"))
    private <T extends Entity> void combinedassault$appendOversizedTyped(EntityTypeTest<Entity, T> test, AABB box,
                                                                     Predicate<? super T> predicate,
                                                                     List<? super T> out, int max, CallbackInfo ci) {
        OversizedVehicles.append((Level) (Object) this, test, box, predicate, out, max);
    }

}
