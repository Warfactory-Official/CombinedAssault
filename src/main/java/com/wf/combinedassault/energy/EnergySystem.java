package com.wf.combinedassault.energy;

import com.wf.combinedassault.energy.gametest.VehicleBatteryGameTest;
import com.wf.combinedassault.energy.gametest.VehicleEnergyGameTest;
import com.wf.combinedassault.vehicle.gametest.GameTestSetup;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import com.wf.combinedassault.vehicle.VehicleTabs;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.CombinedAssaultSystem;
import com.wf.combinedassault.vehicle.VehicleContent;

public final class EnergySystem implements CombinedAssaultSystem {

    private static boolean active;

    /** Off => vehicles burn no fuel and keep no tank contents. */
    public static boolean active() {
        return active;
    }

    @Override
    public String id() {
        return "energy";
    }

    @Override
    public void register(IEventBus modBus, ModContainer container) {
        active = true;
        GameTestSetup.tests(modBus, VehicleBatteryGameTest.class, VehicleEnergyGameTest.class);
        EnergyContent.ITEMS.register(modBus);
        EnergyContent.DATA_COMPONENTS.register(modBus);
        EnergyFluids.register(modBus);
        VehicleTabs.addMisc(EnergyContent.FUEL_TANK);
        container.registerConfig(ModConfig.Type.COMMON, EnergyConfig.SPEC, "combinedassault-energy.toml");
        modBus.addListener(ModConfigEvent.Loading.class, EnergyConfig::onLoad);
        modBus.addListener(ModConfigEvent.Reloading.class, EnergyConfig::onLoad);
        modBus.addListener(RegisterCapabilitiesEvent.class, EnergySystem::registerCapabilities);
        GregTechCompat.init();
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerItem(Capabilities.FluidHandler.ITEM, (stack, context) -> {
                    if (stack.getItem() instanceof FuelTankItem) {
                        return new FuelTankItem.FuelTankFluidHandler(stack, stack.getMaxDamage());
                    }
                    return null;
                },
                EnergyContent.FUEL_TANK.get()
        );
        for (EntityType<? extends AbstractVehicle> type : VehicleContent.vehicleTypes()) {
            registerVehicleEnergy(event, type);
        }
    }

    /** Tank/battery as capabilities: pump/charger sees a vehicle as a machine; only the one it runs on answers. */
    private static <T extends AbstractVehicle> void registerVehicleEnergy(RegisterCapabilitiesEvent event, EntityType<T> type) {
        event.registerEntity(Capabilities.FluidHandler.ENTITY, type,
                (vehicle, context) -> vehicle.isElectric() ? null : vehicle.fuelTank());
        event.registerEntity(Capabilities.EnergyStorage.ENTITY, type,
                (vehicle, context) -> vehicle.isElectric() ? vehicle.energyBuffer() : null);
    }
}
