package com.wf.combinedassault.dynamics.part;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.UnmodifiableView;
import org.joml.Vector3f;
import com.wf.combinedassault.dynamics.part.data.LandingGearUnitData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.dynamics.structure.VehicleCubeOBB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.SwitchableUnit;

public class LandingGearUnit extends SwitchableUnit<LandingGearUnitData> {

    private boolean changing;
    private final List<GearCube> gearCubes = new ArrayList<>();

    private record GearCube(VehicleCubeOBB gearCubeOBB, Vector3f foot) {}

    public LandingGearUnit(int index, AbstractVehicle vehicle, LandingGearUnitData data) {
        super(index, vehicle, data);
    }

    @Override
    public void combineAndInit(@UnmodifiableView Map<String, PartUnit<?>> partUnitsView, AbstractVehicle vehicle) {
        super.combineAndInit(partUnitsView, vehicle);
        double maxHeight = this.getOBBs().stream()
                .mapToDouble(obb -> obb.extents().y * 2)
                .max()
                .orElse(1);
        VehicleCubeOBB mainCubeOBB = vehicle.getMainCubeOBB();
        // Hull body: its shape is the fuselage, already clear of the legs; rebuild would move the bound off the shape.
        if (!mainCubeOBB.hasHull()) {
            mainCubeOBB.height -= maxHeight;
            mainCubeOBB.y += maxHeight;
            mainCubeOBB.rebuild();
        }
        partCubeOBBs.forEach(partCubeOBB -> {
            Vec3 offset = partCubeOBB.offset().subtract(mainCubeOBB.offset());
            Vector3f obbLocalPos = new Vector3f((float) offset.x, gearDownPointY(partCubeOBB), (float) offset.z);
            gearCubes.add(new GearCube(partCubeOBB, obbLocalPos));
        });
    }

    public int footCount() {
        return gearCubes.size();
    }

    /** Main OBB frame. */
    public Vector3f foot(int index) {
        return gearCubes.get(index).foot;
    }

    @Override
    public boolean onInteract(Player player, InteractionHand hand) {
        return true;
    }

    @Override
    public void setOn(boolean on) {
        if (update(on)) {
            if (vehicle.getDriver() instanceof ServerPlayer player) {
                player.displayClientMessage(Component.translatable(on ? "tips.gear_up" : "tips.gear_down"), true);
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (!changing) {
            return;
        }
        boolean reached = true;
        for (GearCube gearCube : gearCubes) {
            float targetY = gearPointY(gearCube.gearCubeOBB);
            float step = gearCube.gearCubeOBB.obb().extents().y / 20;
            Vector3f obbLocalPos = gearCube.foot;
            if (obbLocalPos.y < targetY) {
                obbLocalPos.y = Math.min(targetY, obbLocalPos.y + step);
            } else if (obbLocalPos.y > targetY) {
                obbLocalPos.y = Math.max(targetY, obbLocalPos.y - step);
            }
            if (Math.abs(obbLocalPos.y - targetY) > 1.0E-4f) {
                reached = false;
            }
        }
        changing = !reached;
    }

    @Override
    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag nbt) {
        super.deserializeNBT(provider, nbt);
        for (GearCube gearCube : gearCubes) {
            gearCube.foot.y = gearPointY(gearCube.gearCubeOBB);
        }
        changing = false;
    }

    public boolean update(boolean newState) {
        if (changing) {
            return false;
        }
        if (newState != this.on) {
            changing = true;
        }
        this.on = newState;
        return true;
    }

    public double level() {
        if (gearCubes.isEmpty()) {
            return on ? 0 : 1;
        }
        GearCube gearCube = gearCubes.get(0);
        double upY = gearUpPointY(gearCube.gearCubeOBB);
        return Math.min(1, Math.max(0, (upY - gearCube.foot.y) / gearCube.gearCubeOBB.obb().extents().y * 2));
    }

    private float gearPointY(VehicleCubeOBB cubeOBB) {
        return on ? gearUpPointY(cubeOBB) : gearDownPointY(cubeOBB);
    }

    private float gearUpPointY(VehicleCubeOBB cubeOBB) {
        return (float) (cubeOBB.offset().y - vehicle.getMainCubeOBB().offset().y + cubeOBB.obb().extents().y);
    }

    private float gearDownPointY(VehicleCubeOBB cubeOBB) {
        return gearUpPointY(cubeOBB) - cubeOBB.obb().extents().y * 2;
    }

    public float getDragK() {
        return data.getDragK();
    }

}
