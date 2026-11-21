package com.wf.combinedassault.weapon;

import com.wf.combinedassault.dynamics.dig.BlockDigger;
import com.wf.combinedassault.dynamics.dig.DigSpec;
import com.wf.combinedassault.dynamics.structure.VehicleCubeOBB;
import com.wf.combinedassault.pack.sync.PartUnitSyncData;
import com.wf.combinedassault.pack.sync.SyncDataSerializers;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.weapon.data.VehicleToolWeaponData;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.pojo.AimContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.List;
import java.util.UUID;

/**
 * Block-breaking tool: every shot is a request to work; {@link #tick} mines the cells the tagged collider zone overlaps.
 * Never fires a round; no ammo; works only with the engine on; vehicle energy per working tick.
 */
public class VehicleTool extends AbstractVehicleWeapon<VehicleToolWeaponData> {

    /** Held key repeats every tick; a gap above this is a fresh press. */
    private static final int HELD_GAP = 2;

    private final BlockDigger digger;
    private int lastCall = Integer.MIN_VALUE;
    private boolean latched;
    private UUID operator;
    private boolean syncedWorking;

    public VehicleTool(AbstractVehicle vehicle, WeaponUnit unit, int index, VehicleToolWeaponData data, String serializeId) {
        super(vehicle, unit, index, data, serializeId);
        this.digger = new BlockDigger(vehicle);
    }

    @Override
    public boolean hasAmmo() {
        return true;
    }

    @Override
    public boolean shoot(List<AimContext> aimContexts, LivingEntity shooter) {
        int now = vehicle.tickCount;
        boolean fresh = lastCall == Integer.MIN_VALUE || now - lastCall > HELD_GAP;
        lastCall = now;
        if (shooter instanceof Player player) {
            operator = player.getUUID();
        }
        if (getData().mode.equals("toggle") && fresh) {
            latched = !latched;
        }
        // False: no fire packet, event, sound or muzzle effects; work is {@link #tick}'s.
        return false;
    }

    @Override
    public void onClientFire() {
    }

    @Override
    public void defineSyncData(PartUnitSyncData syncData) {
        super.defineSyncData(syncData);
        syncData.define(SyncDataSerializers.BOOLEAN, on -> syncedWorking = on, this::working, false);
    }

    /** Client: the server's {@link #working()}, for the {@code tool_spin} binding. */
    public boolean spinning() {
        return vehicle.level().isClientSide() ? syncedWorking : working();
    }

    public boolean working() {
        if (lastCall == Integer.MIN_VALUE || !vehicle.isEngineOn()) {
            return false;
        }
        return getData().mode.equals("toggle") ? latched : vehicle.tickCount - lastCall <= HELD_GAP;
    }

    @Override
    public void onSwitchFrom() {
        stop();
    }

    @Override
    public void tick() {
        super.tick();
        if (!(vehicle.level() instanceof ServerLevel level)) {
            return;
        }
        if (!working() || !weaponUnit.supported() || vehicle.getEnergy() < getData().energyPerTick) {
            stop();
            return;
        }
        vehicle.setEnergy(vehicle.getEnergy() - getData().energyPerTick);
        for (VehicleCubeOBB piece : vehicle.colliderPieces()) {
            DigSpec spec = piece.dig();
            if (spec != null && spec.id().equals(getData().dig)) {
                digger.dig(level, spec, piece.obb(), operator);
            }
        }
        markShot();
    }

    private void stop() {
        latched = false;
        if (vehicle.level() instanceof ServerLevel level) {
            digger.clear(level);
        }
    }
}
