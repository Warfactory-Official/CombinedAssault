package com.wf.combinedassault;

import com.wf.wflib.core.api.WFSystems;
import com.wf.wflib.packs.api.PackHost;
import com.wf.wflib.packs.api.PackHosts;
import com.wf.wflib.packs.api.PackSpec;
import com.wf.combinedassault.camo.CamoSystem;
import com.wf.combinedassault.cargo.CargoSystem;
import com.wf.combinedassault.energy.EnergySystem;
import com.wf.combinedassault.props.PropsSystem;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(CombinedAssault.MOD_ID)
public class CombinedAssault {

    public static final String MOD_ID = "combinedassault";
    public static final Logger LOGGER = LogManager.getLogger(CombinedAssault.class);
    public static final List<CombinedAssaultSystem> SYSTEMS = List.of(
            new CamoSystem(),
            new EnergySystem(),
            new CargoSystem(),
            new PropsSystem(),
            new com.wf.combinedassault.render.RenderSystem(),
            new com.wf.combinedassault.hud.HudSystem(),
            new com.wf.combinedassault.indirect.IndirectSystem(),
            new com.wf.combinedassault.uav.UavSystem(),
            new com.wf.combinedassault.weapon.WeaponSystem(),
            new com.wf.combinedassault.ballistics.BallisticsSystem(),
            new com.wf.combinedassault.radar.RadarSystem(),
            new com.wf.combinedassault.airdefense.AirDefenseSystem(),
            new com.wf.combinedassault.tow.TowSystem(),
            new com.wf.combinedassault.naval.NavalSystem(),
            new com.wf.combinedassault.aircraft.AircraftSystem(),
            new com.wf.combinedassault.deck.DeckSystem(),
            new com.wf.combinedassault.dynamics.DynamicsSystem(),
            new com.wf.combinedassault.vehicle.VehicleSystem(),
            new com.wf.combinedassault.pack.PackSystem());

    private static PackHost packs;

    public static PackHost packs() {
        return packs;
    }

    /** Hash set: everything under {@code combinedassault/}, model files under {@code assets/}. */
    static boolean hashed(String path) {
        return path.startsWith(MOD_ID + "/") || path.startsWith("assets/")
                && (path.endsWith(".gltf") || path.endsWith(".glb") || path.endsWith(".bin"));
    }

    public CombinedAssault(IEventBus modEventBus, ModContainer modContainer) {
        packs = PackHosts.register(PackSpec.builder(MOD_ID, "CombinedAssault").keys("license", "authors")
                .hashed(CombinedAssault::hashed).holdArchives().createGameDir().clientResources().serverData().build());
        packs.rescan();
        WFSystems.define(MOD_ID, modEventBus, modContainer, SYSTEMS);
    }

    public static ResourceLocation modLocation(String name) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, name);
    }

    public static ResourceLocation resourceLocation(String location) {
        return ResourceLocation.parse(location);
    }

}
