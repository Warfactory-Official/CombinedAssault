package com.wf.combinedassault.labs;

import com.wf.combinedassault.airdefense.AirDefense;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.weapon.part.MissileHolderUnit;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.wflib.labs.api.LabDefender;
import com.wf.wflib.missile.api.InterceptBattery;
import com.wf.wflib.missile.api.MissileItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Every vehicle with a pack {@code air_defense} unit: spawned on the ground, energy full, racks filled with the first
 * interceptor item (id order) each holder takes, every air defence AUTO on its own radar.
 */
final class VehicleDefender implements LabDefender {
    private static volatile List<LabDefender> roster;

    private final ResourceLocation id;
    private final BaseVehicleData data;

    private VehicleDefender(ResourceLocation id, BaseVehicleData data) {
        this.id = id;
        this.data = data;
    }

    static List<LabDefender> all(ServerLevel level) {
        List<LabDefender> r = roster;
        if (r == null) {
            List<LabDefender> out = new ArrayList<>();
            for (Map.Entry<ResourceLocation, BaseVehicleData> e : VehicleRig.roster(null).entrySet()) {
                AbstractVehicle v = e.getValue().construct(level, Vec3.ZERO, 0, 0);
                v.initData();
                if (!units(v).isEmpty()) {
                    out.add(new VehicleDefender(e.getKey(), e.getValue()));
                }
                v.discard();
            }
            roster = r = List.copyOf(out);
        }
        return r;
    }

    private static List<WeaponUnit> units(AbstractVehicle v) {
        List<WeaponUnit> out = new ArrayList<>();
        for (PartUnit<?> p : v.getPartUnits()) {
            if (p instanceof WeaponUnit w && w.airDefense() != null) {
                out.add(w);
            }
        }
        return out;
    }

    @Override
    public String id() {
        return id.toString();
    }

    @Override
    public Site place(ServerLevel level, BlockPos at, UUID team) {
        AbstractVehicle v = data.construct(level, Vec3.atBottomCenterOf(at), 0, 0);
        v.setDisplayId(id);
        v.initData();
        v.setUUID(UUID.nameUUIDFromBytes((id + "@" + at.asLong()).getBytes(StandardCharsets.UTF_8)));
        List<Item> interceptors = BuiltInRegistries.ITEM.stream()
                .filter(i -> i instanceof MissileItem m && m.preset().isInterceptor())
                .sorted(Comparator.comparing(i -> BuiltInRegistries.ITEM.getKey(i).toString()))
                .toList();
        for (Item item : interceptors) {
            while (MissileHolderUnit.tryLoad(v, new ItemStack(item))) {
            }
        }
        v.setEnergy(v.energyInfo.capacityUnits());
        List<WeaponUnit> units = units(v);
        for (WeaponUnit w : units) {
            w.airDefense().setMode(AirDefense.Mode.AUTO);
            w.airDefense().setSource(AirDefense.Source.ORGANIC);
        }
        if (!level.addFreshEntity(v)) {
            throw new IllegalStateException(id + " could not be added");
        }
        int[] fired = {0};
        return new Site() {
            @Override
            public void tick() {
                for (WeaponUnit w : units) {
                    if (w.airDefense().state() == InterceptBattery.State.FIRED) {
                        fired[0]++;
                    }
                }
            }

            @Override
            public int spent() {
                return fired[0];
            }

            @Override
            public void remove() {
                v.discard();
            }
        };
    }
}
