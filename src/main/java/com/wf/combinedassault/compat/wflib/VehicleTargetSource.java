package com.wf.combinedassault.compat.wflib;

import com.wf.wflib.recon.CountermeasureRegistry;
import com.wf.wflib.recon.EmconState;
import com.wf.wflib.recon.Signature;
import com.wf.wflib.recon.SignatureProvider;
import com.wf.wflib.recon.SignatureRegistry;
import com.wf.wflib.recon.SourceIds;
import com.wf.wflib.recon.TargetSink;
import com.wf.wflib.recon.TargetSource;
import com.wf.wflib.recon.snapshot.TargetSnapshot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.all.AllConfigs;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

import java.util.List;

/**
 * TargetSource, not just a SignatureProvider: WFLib' own entity source queries by class and never sees
 * {@link AbstractVehicle}, so a provider alone would never be consulted. World-thread; snapshot carries no
 * entity reference (consumer must acquire, can't act on a raw detection).
 */
final class VehicleTargetSource implements TargetSource {

    @Override
    public void collect(ServerLevel level, AABB volume, TargetSink sink) {
        if (!AllConfigs.Cached.wflibRecon) {
            return;
        }
        List<AbstractVehicle> vehicles = level.getEntitiesOfClass(AbstractVehicle.class, volume,
                v -> !v.isRemoved() && !v.isDestroyed());
        for (int i = 0; i < vehicles.size(); i++) {
            sink.accept(snapshot(vehicles.get(i)));
        }
    }

    private static TargetSnapshot snapshot(AbstractVehicle vehicle) {
        // EMCON = driver decision, not hull property: radar off => reflects same, emits nothing.
        EmconState emcon = VehicleSignature.radiating(vehicle) ? EmconState.ACTIVE : EmconState.SILENT;
        // Last-tick displacement, not getDeltaMovement (physics-solver impulse != distance traveled).
        return new TargetSnapshot(SourceIds.of(vehicle), vehicle.interceptClass(),
                vehicle.getX(), vehicle.getY() + vehicle.getBbHeight() * 0.5, vehicle.getZ(),
                vehicle.getX() - vehicle.xo, vehicle.getY() - vehicle.yo, vehicle.getZ() - vehicle.zo,
                SignatureRegistry.of(vehicle), CountermeasureRegistry.of(vehicle), emcon,
                // No transponder: scoreboard team name != faction UUID identity code; faking one matches nothing.
                0L, false,
                // Surface contact with water => sonar target even when not submerged (surface-ship case).
                vehicle.level().getFluidState(vehicle.blockPosition()).is(FluidTags.WATER));
    }

    /** priority()=0: bottom of SignatureProvider chain, overridable by pack/addon. */
    static final class BuiltIn implements SignatureProvider {

        @Override
        public int priority() {
            return 0;
        }

        @Override
        @Nullable
        public Signature of(Entity entity) {
            return entity instanceof AbstractVehicle vehicle ? VehicleSignature.of(vehicle) : null;
        }

    }

}
