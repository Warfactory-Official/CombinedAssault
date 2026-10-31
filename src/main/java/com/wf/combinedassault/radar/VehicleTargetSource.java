package com.wf.combinedassault.radar;

import com.wf.wflib.recon.api.CountermeasureProviders;
import com.wf.wflib.recon.api.EmconState;
import com.wf.wflib.recon.api.Signature;
import com.wf.wflib.recon.api.SignatureProvider;
import com.wf.wflib.recon.api.SignatureProviders;
import com.wf.wflib.recon.api.SourceIds;
import com.wf.wflib.recon.api.TargetSink;
import com.wf.wflib.recon.api.TargetSource;
import com.wf.wflib.recon.api.TargetSnapshot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;

import java.util.List;

/**
 * TargetSource, not just a SignatureProvider: WFLib' own entity source queries by class and never sees
 * {@link AbstractVehicle}, so a provider alone would never be consulted. World-thread; snapshot carries no
 * entity reference (consumer must acquire, can't act on a raw detection).
 */
final class VehicleTargetSource implements TargetSource {

    @Override
    public void collect(ServerLevel level, AABB volume, TargetSink sink) {
        if (!RadarConfig.get().recon()) {
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
                SignatureProviders.of(vehicle), CountermeasureProviders.of(vehicle), emcon,
                // No transponder: scoreboard team name != faction UUID identity code; faking one matches nothing.
                0L, false,
                // Surface contact with water => sonar target even when not submerged (surface-ship case).
                vehicle.level().getFluidState(vehicle.blockPosition()).is(FluidTags.WATER));
    }

    /** Registered at priority 0: bottom of SignatureProvider chain, overridable by pack/addon. */
    static final class BuiltIn implements SignatureProvider {

        @Override
        @Nullable
        public Signature of(Entity entity) {
            return entity instanceof AbstractVehicle vehicle ? VehicleSignature.of(vehicle) : null;
        }

    }

}
