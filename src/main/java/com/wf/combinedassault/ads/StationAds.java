package com.wf.combinedassault.ads;

import com.wf.combinedassault.all.AllKeys;
import com.wf.combinedassault.custom.part.data.WeaponUnitData;
import com.wf.combinedassault.vehicle.LocalVehiclePlayer;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.wflib.client.sight.AimInput;
import com.wf.wflib.client.sight.ScopeMath;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

/**
 * Aim down the sights of a {@code handling: manual} station with a {@code sight} socket, {@code operator}-sighted or
 * with an {@code optic} ({@link StationPip}): SCOPE = ADS, eye operator view -> {@code sight} (riding the gun) over
 * {@link #ADS_TICKS}; RMB per MineAssault ({@link AimInput}: tap toggles, hold = temporary zoom).
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class StationAds {
    static final int ADS_TICKS = 5;
    /** ADS FOV reference (MineAssault {@code adsReferenceFov} default). */
    static final float REFERENCE_FOV = 70;

    private static final AimInput INPUT = new AimInput();
    @Nullable
    private static WeaponUnit station;
    private static LocalVehiclePlayer.ViewType lastView;
    private static float ads, adsO, hold, holdO;
    private static int clock;

    private StationAds() {
    }

    public static boolean capable(@Nullable WeaponUnit unit) {
        return unit != null && unit.getData().getHandling() == WeaponUnitData.Handling.MANUAL
                && unit.getOpticalSightOffset() != null
                && (unit.getOpticalSightType() == WeaponUnitData.OpticalSightType.OPERATOR
                || unit.optic() != null && unit.lensNode() != null);
    }

    @Nullable
    static WeaponUnit station() {
        return station;
    }

    /** Eased ADS progress. */
    static float progress(float partialTick) {
        return eased(adsO, ads, partialTick);
    }

    /** {@code unit} is the local ADS station and its view is SCOPE. */
    public static boolean aiming(@Nullable WeaponUnit unit) {
        return unit != null && unit == station && LocalVehiclePlayer.instance.viewType == LocalVehiclePlayer.ViewType.SCOPE;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.isPaused()) {
            return;
        }
        clock++;
        adsO = ads;
        holdO = hold;
        LocalVehiclePlayer local = LocalVehiclePlayer.instance;
        WeaponUnit unit = local.onVehicle() ? local.getWeaponUnit() : null;
        if (!capable(unit)) {
            station = null;
            INPUT.off();
            ads = hold = adsO = holdO = 0;
            return;
        }
        if (unit != station) {
            station = unit;
            INPUT.off();
            lastView = local.viewType;
            ads = adsO = 0;
        }
        if (mc.screen != null) {
            INPUT.cancel();
        } else {
            if (local.viewType != lastView) {
                INPUT.ads = local.viewType == LocalVehiclePlayer.ViewType.SCOPE;
            }
            INPUT.tick(AllKeys.MAGNIFICATION_CHANGE.isDown(), clock);
            if (INPUT.ads != (local.viewType == LocalVehiclePlayer.ViewType.SCOPE)) {
                local.switchViewType(INPUT.ads ? LocalVehiclePlayer.ViewType.SCOPE : LocalVehiclePlayer.ViewType.OPERATOR);
            }
        }
        lastView = local.viewType;
        boolean aimed = local.viewType == LocalVehiclePlayer.ViewType.SCOPE;
        ads = aimed ? Math.min(1, ads + 1F / ADS_TICKS) : 0;
        hold = INPUT.hold && aimed ? Math.min(1, hold + AimInput.HOLD_STEP) : Math.max(0, hold - AimInput.HOLD_STEP);
    }

    private static float eased(float o, float now, float pt) {
        float t = Mth.lerp(pt, o, now);
        return t * t * (3 - 2 * t);
    }

    /** SCOPE camera of the ADS station: operator view -> sight eye; null => not ADS. */
    @Nullable
    public static Vec3 eye(WeaponUnit unit, float partialTick) {
        if (!aiming(unit)) {
            return null;
        }
        return unit.worldOwnerViewPosition(partialTick).lerp(unit.worldSightEye(partialTick),
                eased(adsO, ads, partialTick));
    }

    /** Scope mouse-turn divisor of {@code unit} ({@link ScopeMath#turnZoom}); 1 => not ADS. */
    public static float turnDivisor(WeaponUnit unit, float userFovDeg) {
        float world = divisor(unit, userFovDeg, 1);
        return world <= 0 ? 1 : ScopeMath.turnZoom(world, StationPip.magnification(), progress(1), StationPip.shown());
    }

    /** World FOV divisor of the ADS station ({@link ScopeMath#worldDivisor}); 0 => not ADS. */
    public static float divisor(WeaponUnit unit, float userFovDeg, float partialTick) {
        if (!aiming(unit)) {
            return 0;
        }
        return ScopeMath.worldDivisor(unit.getZoom(), ScopeMath.refZoom(userFovDeg, REFERENCE_FOV),
                eased(adsO, ads, partialTick), AimInput.zoom(eased(holdO, hold, partialTick)));
    }
}
