package com.wf.combinedassault.weapon.part;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.vehicle.VehicleDamageTypes;
import com.wf.combinedassault.weapon.ordnance.Munitions;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.util.VectorUtil;
import com.wf.combinedassault.vehicle.crew.CrewRole;
import com.wf.combinedassault.vehicle.crew.CrewRules;
import com.wf.combinedassault.weapon.pojo.AimContext;
import com.wf.combinedassault.weapon.pojo.Bolt;
import com.wf.combinedassault.weapon.AbstractVehicleWeapon;
import com.wf.wflib.grenade.api.ChargeItem;
import com.wf.wflib.grenade.api.ChargeSpec;
import com.wf.wflib.fire.api.FireType;
import com.wf.wflib.fire.api.WFFire;
import com.wf.wflib.grenade.api.GrenadeWarheads;
import com.wf.wflib.grenade.api.Grenades;
import com.wf.wflib.grenade.api.ThrownCharge;
import com.wf.wflib.probe.api.ProbeActions;
import com.wf.wflib.probe.api.ProbeInventory;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Thermite charge dropped down a large-bore barrel (probe at the muzzle). After the charge's fuse: cosmetic thermite
 * reaction pinned at the muzzle venting along the bore, the weapon part broken, loader seat occupant(s) burned through
 * {@code protect_passenger}. {@code arg = unit index x 64 + bolt}.
 */
public final class BarrelCharge {

    public static final ResourceLocation ACTION = CombinedAssault.modLocation("barrel_charge");
    public static final float LARGE_BORE_MM = 75;
    public static final int INSERT_TICKS = 30;
    /** Muzzle within this of the player's eye. */
    public static final double REACH = 3.0;
    /** Charge sits this far down the bore. */
    static final double DEPTH = 0.4;
    /** Loader damage per 10 ticks of burn: confined, no armour between. */
    static final float LOADER_BURN = 6.0f;

    private static final List<Pending> PENDING = new ArrayList<>();

    private static final class Pending {
        final ServerLevel level;
        final UUID vehicle;
        final int unit;
        final int bolt;
        final ItemStack look;
        final ChargeSpec spec;
        @Nullable
        final UUID owner;
        int fuse;
        @Nullable
        ThrownCharge charge;
        int burn;

        Pending(ServerLevel level, UUID vehicle, int unit, int bolt, ItemStack look, ChargeSpec spec,
                @Nullable UUID owner) {
            this.level = level;
            this.vehicle = vehicle;
            this.unit = unit;
            this.bolt = bolt;
            this.look = look;
            this.spec = spec;
            this.owner = owner;
            this.fuse = spec.fuseTicks();
        }
    }

    private BarrelCharge() {
    }

    public static int arg(int unit, int bolt) {
        return unit * 64 + bolt;
    }

    public static boolean thermite(ItemStack stack) {
        return stack.getItem() instanceof ChargeItem c && c.charge(stack).warhead().equals(GrenadeWarheads.THERMITE);
    }

    /** Largest bore over the unit's weapons, mm; rounds in them, else defaults. */
    public static float bore(WeaponUnit unit) {
        float bore = 0;
        for (AbstractVehicleWeapon<?> weapon : unit.weapons) {
            bore = Math.max(bore, Munitions.caliber(weapon.solutionRound()));
        }
        return bore;
    }

    public static boolean accepts(WeaponUnit unit) {
        return !unit.isDetached() && unit.getHealth() > 0 && bore(unit) >= LARGE_BORE_MM;
    }

    /** {@code [muzzle, bore direction]}. */
    public static Vec3[] muzzle(WeaponUnit unit, Bolt bolt) {
        AimContext aim = unit.aimContext(bolt);
        Vec3 dir = VectorUtil.rotToVec(aim.direction.x, aim.direction.y).normalize();
        return new Vec3[]{aim.from, dir};
    }

    public static void register() {
        ProbeActions.registerTimedEntity(ACTION, new ProbeActions.TimedEntity() {
            @Override
            public int ticks(ServerPlayer player, Entity entity, int arg) {
                return target(player, entity, arg) != null ? INSERT_TICKS : 0;
            }

            @Override
            public boolean holds(ServerPlayer player, Entity entity, int arg) {
                return target(player, entity, arg) != null;
            }

            @Override
            public boolean perform(ServerPlayer player, Entity entity, int arg) {
                WeaponUnit unit = target(player, entity, arg);
                if (unit == null) {
                    return false;
                }
                ItemStack stack = ProbeInventory.find(player, BarrelCharge::thermite);
                if (stack.isEmpty()) {
                    return false;
                }
                ItemStack look = stack.copyWithCount(1);
                ChargeSpec spec = ((ChargeItem) stack.getItem()).charge(stack);
                if (!player.getAbilities().instabuild && !ProbeInventory.take(player, BarrelCharge::thermite, 1)) {
                    return false;
                }
                insert(player.serverLevel(), (AbstractVehicle) entity, unit, arg % 64, look, spec, player);
                return true;
            }
        });
    }

    /** Charge in the bore; fuse from now. */
    public static void insert(ServerLevel level, AbstractVehicle vehicle, WeaponUnit unit, int bolt, ItemStack look,
                              ChargeSpec spec, @Nullable Entity owner) {
        PENDING.add(new Pending(level, vehicle.getUUID(), unit.getIndex(), bolt, look, spec,
                owner == null ? null : owner.getUUID()));
        Vec3 at = muzzle(unit, unit.getBolts().get(bolt))[0];
        level.playSound(null, at.x, at.y, at.z, Grenades.SPOON.get(), SoundSource.PLAYERS, 1, 1);
    }

    @Nullable
    static WeaponUnit target(Player player, Entity entity, int arg) {
        if (!(entity instanceof AbstractVehicle vehicle) || vehicle.isDestroyed()) {
            return null;
        }
        int index = arg / 64;
        int bolt = arg % 64;
        if (index < 0 || index >= vehicle.getPartUnits().size()
                || !(vehicle.getPartUnits().get(index) instanceof WeaponUnit unit) || !accepts(unit)
                || bolt >= unit.getBolts().size()) {
            return null;
        }
        if (ProbeInventory.find(player, BarrelCharge::thermite).isEmpty()) {
            return null;
        }
        Vec3 at = muzzle(unit, unit.getBolts().get(bolt))[0];
        return player.getEyePosition().distanceTo(at) <= REACH ? unit : null;
    }

    @SubscribeEvent
    public static void onTick(ServerTickEvent.Post event) {
        Iterator<Pending> it = PENDING.iterator();
        while (it.hasNext()) {
            Pending p = it.next();
            if (!(p.level.getEntity(p.vehicle) instanceof AbstractVehicle vehicle) || vehicle.isDestroyed()
                    || p.unit >= vehicle.getPartUnits().size()
                    || !(vehicle.getPartUnits().get(p.unit) instanceof WeaponUnit unit)
                    || p.bolt >= unit.getBolts().size()) {
                if (p.charge != null) {
                    p.charge.discard();
                }
                it.remove();
                continue;
            }
            Vec3[] muzzle = muzzle(unit, unit.getBolts().get(p.bolt));
            Vec3 inBore = muzzle[0].subtract(muzzle[1].scale(DEPTH));
            if (p.charge == null) {
                if (--p.fuse > 0) {
                    continue;
                }
                Entity owner = p.owner == null ? null : p.level.getEntity(p.owner);
                p.charge = ThrownCharge.react(p.level, inBore, p.look, p.spec, muzzle[1], owner, null, true);
                unit.setHealth(0);
            }
            if (p.charge.isRemoved() || p.charge.phase() != ThrownCharge.Phase.REACTING) {
                it.remove();
                continue;
            }
            p.charge.holdAt(inBore, muzzle[1]);
            if (p.burn++ % 10 == 0) {
                burnLoader(vehicle, p.charge);
            }
        }
    }

    private static void burnLoader(AbstractVehicle vehicle, ThrownCharge charge) {
        for (AbstractVehicle.Seat seat : vehicle.seats) {
            if (seat.passengerId == -1 || !CrewRules.roles(seat).contains(CrewRole.LOADER)) {
                continue;
            }
            if (vehicle.level().getEntity(seat.passengerId) instanceof LivingEntity crew) {
                crew.hurt(VehicleDamageTypes.Sources.breechFire(vehicle.level().registryAccess(), charge,
                        charge.getOwner()), LOADER_BURN);
                WFFire.ignite(crew, FireType.THERMITE, FireType.THERMITE.burnTicks);
            }
        }
    }
}
