package com.wf.combinedassault.vehicle.audio;

import com.wf.combinedassault.pack.client.ClientAssetsManager;
import com.wf.combinedassault.pack.client.vehicle.VehicleDisplay;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.wflib.audio.api.AudioCategory;
import com.wf.wflib.audio.api.EngineSound;
import com.wf.wflib.audio.api.client.EnginePlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

/** Engine audio of one vehicle from its display {@code engine}; rebuilt when a reload swaps the def. */
@OnlyIn(Dist.CLIENT)
public final class VehicleAudio implements EnginePlayer.Source {
    private final AbstractVehicle vehicle;
    @Nullable
    private EngineSound def;
    @Nullable
    private EnginePlayer player;

    public VehicleAudio(AbstractVehicle vehicle) {
        this.vehicle = vehicle;
    }

    public void tick() {
        EngineSound now = ClientAssetsManager.INSTANCE.getVehicleDisplay(vehicle.getDisplayId())
                .map(VehicleDisplay::getEngine).orElse(null);
        if (now != def) {
            if (player != null) {
                player.stop();
            }
            def = now;
            player = now == null ? null : new EnginePlayer(now, AudioCategory.VEHICLES, this);
        }
        if (player != null) {
            player.tick();
        }
    }

    @Override
    @Nullable
    public Vec3 position() {
        return vehicle.isRemoved() ? null : vehicle.position();
    }

    @Override
    public Vec3 forward() {
        return new Vec3(vehicle.axes()[2]);
    }

    @Override
    public boolean running() {
        return vehicle.isEngineOn();
    }

    @Override
    public float input(EngineSound.Input input) {
        return vehicle.audioInput(input);
    }
}
