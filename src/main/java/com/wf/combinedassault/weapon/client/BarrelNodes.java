package com.wf.combinedassault.weapon.client;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.weapon.barrel.BarrelActions;
import com.wf.combinedassault.weapon.barrel.GunBarrel;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.util.VectorUtil;
import com.wf.combinedassault.ballistics.hit.VehicleHits;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.pojo.AimContext;
import com.wf.combinedassault.weapon.AbstractVehicleWeapon;
import com.wf.wflib.barrel.api.BarrelSpec;
import com.wf.wflib.barrel.api.BarrelState;
import com.wf.wflib.probe.api.ProbeAction;
import com.wf.wflib.probe.api.ProbeContext;
import com.wf.wflib.probe.api.ProbeElement;
import com.wf.wflib.probe.api.ProbeInfo;
import com.wf.wflib.probe.api.ProbeNode;
import com.wf.wflib.probe.api.ProbeProviders;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** On foot: muzzle node per barrel weapon, shown for a quick-change barrel or a hot one. */
public final class BarrelNodes {
    private static final String KEY = "barrel.combinedassault.";
    private static final int COOL = 0xFF9AB0C0;
    private static final int HOT = 0xFFFF6A20;
    private static final int BAR_W = 60;

    private BarrelNodes() {
    }

    public static void register() {
        ProbeProviders.addEntityNodes(CombinedAssault.modLocation("barrel_nodes"), entity -> entity instanceof AbstractVehicle, BarrelNodes::nodes,
                ProbeProviders.CAPABILITY_PRIORITY);
    }

    private static void nodes(List<ProbeNode> out, ProbeContext ctx, Entity entity) {
        AbstractVehicle vehicle = (AbstractVehicle) entity;
        Player player = ctx.player();
        if (vehicle.isDestroyed() || player.isPassenger()) {
            return;
        }
        Vec3 drift = vehicle.position(ctx.partialTick()).subtract(vehicle.position());
        Vec3 eye = player.getEyePosition(ctx.partialTick());
        VehicleHits hits = vehicle.hits();
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (!(part instanceof WeaponUnit unit) || unit.isDetached()) {
                continue;
            }
            for (AbstractVehicleWeapon<?> weapon : unit.indexedWeapons) {
                GunBarrel barrel = weapon.barrel();
                if (barrel == null || !unit.carries(weapon) || barrel.state().haze() <= 0 && !barrel.spec().quickChange()) {
                    continue;
                }
                AimContext aim = weapon.getWeaponUnit().aimContext();
                if (hits != null && hits.hidden(eye.subtract(drift), aim.from)) {
                    continue;
                }
                Vec3 dir = VectorUtil.rotToVec(aim.direction.x, aim.direction.y).normalize();
                int arg = BarrelActions.arg(unit, weapon);
                out.add(new ProbeNode("gun_barrel:" + arg, aim.from.add(drift), info -> fill(info, player, vehicle,
                        barrel, arg)).colour(barrel.state().haze() > 0 ? HOT : COOL).normal(dir)
                        .reach(BarrelActions.REACH));
            }
        }
    }

    private static void fill(ProbeInfo info, Player player, AbstractVehicle vehicle, GunBarrel barrel, int arg) {
        BarrelSpec spec = barrel.spec();
        BarrelState state = barrel.state();
        info.title(barrel.weapon().getDisplayName());
        int heat = state.glow() > 0 ? 0xFFFF5020 : state.haze() > 0 ? 0xFFFFA030 : 0xFF60C070;
        info.bar((state.temp() - BarrelState.AMBIENT) / (spec.max() - BarrelState.AMBIENT), BAR_W, heat,
                Component.translatable(KEY + "temp", Math.round(state.temp())));
        info.bar(1 - state.wear(), BAR_W, 0xFF90A0B0,
                Component.translatable(KEY + "wear", Math.round(state.wear() * 100)));
        if (barrel.jammed()) {
            info.text(Component.translatable(KEY + "jammed"), 0xFFFF4040);
        }
        if (!barrel.fitted()) {
            info.text(Component.translatable(KEY + "none"), 0xFFFF4040);
        }
        if (!spec.quickChange()) {
            return;
        }
        ItemStack spare = barrel.spare(player);
        int ticks = barrel.probeTicks(player);
        String verb = !barrel.fitted() ? "fit" : spare.isEmpty() ? "remove" : "change";
        ProbeAction change = ProbeAction.of(BarrelActions.CHANGE, arg, Component.translatable(KEY + verb
                + (ticks == spec.swap().selfTicks() ? "" : "_coop"))).timed(ticks);
        if (!spare.isEmpty()) {
            change = change.withIcon(new ProbeElement.Icon(spare, 10, false));
        }
        Component refusal = BarrelActions.refusal(player, vehicle, arg);
        info.action(refusal == null ? change : change.unavailable(refusal));
    }
}
