package com.wf.combinedassault.weapon;

import com.wf.combinedassault.weapon.gametest.LoadoutGameTest;
import com.wf.combinedassault.weapon.gametest.PylonGameTest;
import com.wf.combinedassault.weapon.gametest.RadioGameTest;
import com.wf.combinedassault.weapon.gametest.BombRoundsGameTest;
import com.wf.combinedassault.weapon.gametest.MissileRoundsGameTest;
import com.wf.combinedassault.vehicle.gametest.GameTestSetup;
import java.util.Set;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.VehicleTabs;
import com.wf.combinedassault.vehicle.event.VehicleFireEvent;
import com.wf.combinedassault.vehicle.command.RootCommand;
import com.wf.combinedassault.weapon.barrel.BarrelActions;
import com.wf.combinedassault.weapon.barrel.BarrelCommand;
import com.wf.combinedassault.weapon.barrel.BarrelPacket;
import com.wf.combinedassault.weapon.kit.Hardpoints;
import com.wf.combinedassault.weapon.kit.Loadouts;
import com.wf.combinedassault.weapon.message.ClientSelectRound;
import com.wf.combinedassault.weapon.message.ClientVehicleSelectLoadout;
import com.wf.combinedassault.weapon.message.ClientVehicleSelectPartWeapon;
import com.wf.combinedassault.weapon.message.ClientVehicleSwitchWeapon;
import com.wf.combinedassault.weapon.message.ServerVehicleFire;
import com.wf.combinedassault.weapon.ordnance.GuidedRounds;
import com.wf.combinedassault.weapon.ordnance.Ordnance;
import com.wf.combinedassault.weapon.ordnance.RoundMarks;
import com.wf.combinedassault.weapon.part.BarrelCharge;
import com.wf.combinedassault.weapon.part.BatteryActions;
import com.wf.combinedassault.weapon.part.StationActions;
import com.wf.combinedassault.weapon.part.WeaponActions;
import com.wf.combinedassault.CombinedAssaultSystem;

public final class WeaponSystem implements CombinedAssaultSystem {

    @Override
    public String id() {
        return "weapon";
    }

    @Override
    public boolean mandatory() {
        return true;
    }

    @Override
    public Set<String> requires() {
        return Set.of("wflib:magazine", "wflib:laser", "wflib:barrel", "wflib:probe", "wflib:packs", "wflib:drone", "wflib:countermeasure", "wflib:sight");
    }

    @Override
    public void register(IEventBus modBus, ModContainer container) {
        GameTestSetup.testsNeeding(modBus, java.util.Set.of("aircraft", "uav"), LoadoutGameTest.class, PylonGameTest.class, RadioGameTest.class);
        GameTestSetup.testsNeeding(modBus, java.util.Set.of("aircraft"), BombRoundsGameTest.class, MissileRoundsGameTest.class);
        WeaponContent.ITEMS.register(modBus);
        WeaponContent.DATA_COMPONENTS.register(modBus);
        VehicleTabs.addMisc(WeaponContent.AMMO_CREATIVE);
        Ordnance.register();
        Loadouts.register();
        Hardpoints.register();
        WeaponActions.register();
        StationActions.register();
        BatteryActions.register();
        BarrelCharge.register();
        BarrelActions.register();
        RootCommand.SUBCOMMANDS.register(CombinedAssault.modLocation("barrel"), 0, BarrelCommand::get);
        modBus.addListener(RegisterPayloadHandlersEvent.class, event -> {
            PayloadRegistrar registrar = event.registrar("weapon-1");
            registrar.playToServer(ClientVehicleSwitchWeapon.TYPE, ClientVehicleSwitchWeapon.STREAM_CODEC, ClientVehicleSwitchWeapon::handle);
            registrar.playToServer(ClientSelectRound.TYPE, ClientSelectRound.STREAM_CODEC, ClientSelectRound::handle);
            registrar.playToClient(ServerVehicleFire.TYPE, ServerVehicleFire.STREAM_CODEC, ServerVehicleFire::handle);
            registrar.playToServer(ClientVehicleSelectPartWeapon.TYPE, ClientVehicleSelectPartWeapon.STREAM_CODEC, ClientVehicleSelectPartWeapon::handle);
            registrar.playToServer(ClientVehicleSelectLoadout.TYPE, ClientVehicleSelectLoadout.STREAM_CODEC, ClientVehicleSelectLoadout::handle);
            registrar.playToServer(BarrelPacket.TYPE, BarrelPacket.STREAM_CODEC, BarrelPacket::handle);
        });
        NeoForge.EVENT_BUS.addListener(VehicleFireEvent.Post.class, WeaponSystem::onFire);
        NeoForge.EVENT_BUS.register(BarrelCharge.class);
        NeoForge.EVENT_BUS.register(RoundMarks.class);
        NeoForge.EVENT_BUS.register(GuidedRounds.class);
    }

    @Override
    public void registerClient(IEventBus modBus) {
        WeaponClient.start(modBus);
    }

    private static void onFire(VehicleFireEvent.Post event) {
        if (event.isClientSide()) {
            event.getWeapon().onClientFire();
            event.getWeapon().getWeaponUnit().onClientFire();
        }
    }
}
