package com.wf.combinedassault.barrel.gametest;

import com.mojang.authlib.GameProfile;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.api.custom.sync.SyncDataSerializers;
import com.wf.combinedassault.barrel.BarrelActions;
import com.wf.combinedassault.barrel.BarrelPacket;
import com.wf.combinedassault.barrel.GunBarrel;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.pojo.AimContext;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;
import com.wf.wflib.ammo.AmmoItems;
import com.wf.wflib.barrel.BarrelSpec;
import com.wf.wflib.barrel.BarrelState;
import com.wf.wflib.barrel.Barrels;
import com.wf.wflib.barrel.SpareBarrel;
import com.wf.wflib.probe.ProbeActions;
import com.wf.wflib.probe.ProbeJobs;
import com.wf.wflib.round.Rounds;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Humvee M2 (quick-change, closed bolt) and Mk 19 (fixed, open bolt) barrels. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class BarrelGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation HUMVEE = CombinedAssault.modLocation("humvee");
    private static final ResourceLocation BMG = ResourceLocation.parse("wflib:12.7x99/ball");
    private static final ResourceLocation M430 = ResourceLocation.parse("wflib:40x53/he");
    private static final ResourceLocation M2_TYPE = CombinedAssault.modLocation("m2");

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void fireHeatsTheBarrelAndWidensTheCone(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper, "m2");
        AbstractVehicleWeapon<?> m2 = weapon(humvee, BMG);
        GunBarrel barrel = m2.barrel();
        BarrelSpec spec = barrel.spec();
        m2.setRemainAmmo(200);
        m2.setLoadedRound(AmmoItems.stack(BMG, 1));
        float spread0 = barrel.state().spread(spec);
        int shots = 0;
        float atHalf = 0;
        for (; shots < 200 && fire(m2); shots++) {
            if (shots == 99) {
                atHalf = barrel.state().temp();
            }
        }
        BarrelState after = barrel.state();
        clearRounds(helper, m2);
        humvee.discard();
        helper.assertTrue(shots == 200, "fired " + shots + " / 200, jammed " + barrel.jammed());
        helper.assertTrue(Math.abs(after.temp() - (BarrelState.AMBIENT + 200 * spec.perShot())) < 1,
                "200 rounds in one tick => " + after.temp() + " degC, want ambient + 200 x " + spec.perShot());
        helper.assertTrue(atHalf > BarrelState.AMBIENT && after.temp() > atHalf, "heat " + atHalf + " -> " + after.temp());
        helper.assertTrue(spread0 == 0 && after.spread(spec) > 0, "spread " + spread0 + " -> " + after.spread(spec));
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aJamBlocksFireUntilClearedAndCostsARound(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper, "m2");
        AbstractVehicleWeapon<?> m2 = weapon(humvee, BMG);
        GunBarrel barrel = m2.barrel();
        m2.setRemainAmmo(10);
        m2.setLoadedRound(AmmoItems.stack(BMG, 1));
        aimUp(m2);
        barrel.setJammed(true);
        boolean refused = !fire(m2) && m2.getRemainAmmo() == 10;
        int span = barrel.clearTicks();
        boolean started = barrel.startClear();
        helper.assertTrue(refused, "jammed gun fired: ammo " + m2.getRemainAmmo());
        helper.assertTrue(started && span == Math.round(m2.getData().getUnjamTime() * 20), "clear started "
                + started + ", " + span + " ticks");
        helper.runAfterDelay(span / 2, () -> helper.assertTrue(barrel.jammed() && barrel.clearing() && !fire(m2),
                "half way: jammed " + barrel.jammed() + ", clearing " + barrel.clearing()));
        helper.runAfterDelay(span + 2, () -> {
            boolean cleared = !barrel.jammed() && !barrel.clearing() && m2.getRemainAmmo() == 9;
            boolean fires = fire(m2);
            clearRounds(helper, m2);
            humvee.discard();
            helper.assertTrue(cleared, "after " + span + " ticks: jammed " + barrel.jammed() + ", ammo "
                    + m2.getRemainAmmo() + " (want 9)");
            helper.assertTrue(fires, "cleared gun does not fire");
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aWornRedHotBarrelJams(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper, "m2");
        AbstractVehicleWeapon<?> m2 = weapon(humvee, BMG);
        GunBarrel barrel = m2.barrel();
        long now = helper.getLevel().getGameTime();
        barrel.setState(new BarrelState(barrel.spec().max(), now, 1));
        m2.setRemainAmmo(400);
        m2.setLoadedRound(AmmoItems.stack(BMG, 1));
        int shots = 0;
        while (shots < 400 && fire(m2)) {
            shots++;
        }
        clearRounds(helper, m2);
        humvee.discard();
        float chance = barrel.state().jamChance(barrel.spec());
        helper.assertTrue(barrel.jammed() && shots < 400, "worn red-hot barrel, chance " + chance + "/round: "
                + shots + " rounds without a jam");
        helper.succeed();
    }

    /** 600 degC, 12 s, tau 160: P(no cook-off) ~ 0.2%. */
    @GameTest(template = TEMPLATE, timeoutTicks = 260)
    public static void aRedHotClosedBoltCooksOff(GameTestHelper helper) {
        cookOff(helper, "m2", BMG, true);
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 260)
    public static void anOpenBoltNeverCooksOff(GameTestHelper helper) {
        cookOff(helper, "mk19", M430, false);
    }

    private static void cookOff(GameTestHelper helper, String kit, ResourceLocation round, boolean closedBolt) {
        AbstractVehicle humvee = spawn(helper, kit);
        AbstractVehicleWeapon<?> gun = weapon(humvee, round);
        gun.barrel().setState(new BarrelState(600, helper.getLevel().getGameTime(), 0));
        gun.setRemainAmmo(10);
        gun.setLoadedRound(AmmoItems.stack(round, 1));
        aimUp(gun);
        helper.runAfterDelay(240, () -> {
            int left = gun.getRemainAmmo();
            humvee.discard();
            helper.assertTrue(closedBolt ? left < 10 : left == 10, kit + " at 600 degC for 12 s: " + (10 - left)
                    + " cook-offs");
            helper.succeed();
        });
    }

    /** Seated gunner: {@code self} time, spare from the inventory; heat goes with the barrel. */
    @GameTest(template = TEMPLATE, timeoutTicks = 260)
    public static void aSeatedGunnerSwapsInTheSpareAndTheHotBarrelGoesBack(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper, "m2");
        AbstractVehicleWeapon<?> m2 = weapon(humvee, BMG);
        GunBarrel barrel = m2.barrel();
        long now = helper.getLevel().getGameTime();
        barrel.setState(new BarrelState(500, now, 0.4f));
        ServerPlayer gunner = player(helper, "barrel_gunner");
        ItemStack spare = Barrels.spare(M2_TYPE, new BarrelState(BarrelState.AMBIENT, now, 0.05f));
        gunner.getInventory().setItem(9, spare);
        seat(humvee, gunner);
        BarrelActions.seated(gunner, m2, BarrelPacket.Action.CHANGE);
        int self = barrel.spec().swap().selfTicks();
        m2.setRemainAmmo(10);
        m2.setLoadedRound(AmmoItems.stack(BMG, 1));
        boolean blocked = barrel.swapping() && !fire(m2);
        helper.runAfterDelay(self - 10, () -> helper.assertTrue(barrel.swapping(), "done before self " + self));
        helper.runAfterDelay(self + 3, () -> {
            SpareBarrel off = Barrels.spareOf(gunner.getInventory().getItem(9));
            BarrelState on = barrel.state();
            humvee.discard();
            helper.assertTrue(blocked, "gun fired mid-swap");
            helper.assertTrue(!barrel.swapping() && on.temp() < 30 && Math.abs(on.wear() - 0.05f) < 1e-3,
                    "fitted barrel " + on + ", swapping " + barrel.swapping());
            helper.assertTrue(off != null && off.state().temp() > 400 && off.state().wear() >= 0.4f,
                    "removed barrel in the slot " + (off == null ? null : off.state()));
            helper.succeed();
        });
    }

    /** On foot at the muzzle: gunner manning the M2 => {@code coop}; alone => {@code self}. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void anAssistantSwapsFasterThanALoneCrewman(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper, "m2");
        AbstractVehicleWeapon<?> m2 = weapon(humvee, BMG);
        GunBarrel barrel = m2.barrel();
        humvee.setNoGravity(true);
        clearBarriers(helper, humvee);
        BarrelSpec spec = barrel.spec();
        int coop = spec.swap().coopTicks();
        int self = spec.swap().selfTicks();
        ServerPlayer gunner = player(helper, "barrel_coop_gunner");
        ServerPlayer mate = player(helper, "barrel_coop_mate");
        WeaponUnit holder = barrel.holder();
        int arg = BarrelActions.arg(holder, m2);
        long now = helper.getLevel().getGameTime();
        mate.getInventory().setItem(9, Barrels.spare(M2_TYPE, new BarrelState(BarrelState.AMBIENT, now, 0)));
        barrel.setState(new BarrelState(450, now, 0));
        atMuzzle(mate, m2);
        seat(humvee, gunner);
        helper.assertTrue(barrel.probeTicks(mate) == coop, "manned => " + barrel.probeTicks(mate) + " != " + coop);
        ProbeActions.performEntity(mate, humvee, BarrelActions.CHANGE, arg);
        helper.assertTrue(ProbeJobs.busy(mate), "co-op change refused: " + BarrelActions.refusal(mate, humvee, arg));
        helper.runAfterDelay(coop + 3, () -> {
            boolean coopDone = !barrel.swapping() && barrel.state().temp() < 30;
            unseat(humvee, gunner);
            helper.assertTrue(coopDone, "co-op not done after " + coop + " ticks: " + barrel.state());
            barrel.setState(new BarrelState(450, helper.getLevel().getGameTime(), 0));
            helper.assertTrue(barrel.probeTicks(mate) == self, "unmanned => " + barrel.probeTicks(mate));
            ProbeActions.performEntity(mate, humvee, BarrelActions.CHANGE, arg);
            helper.assertTrue(ProbeJobs.busy(mate), "solo change refused: " + BarrelActions.refusal(mate, humvee, arg));
            helper.runAfterDelay(coop + 3, () -> helper.assertTrue(barrel.swapping() && barrel.state().temp() > 400,
                    "solo done at co-op time"));
            helper.runAfterDelay(self + 3, () -> {
                BarrelState on = barrel.state();
                humvee.discard();
                helper.assertTrue(!barrel.swapping() && on.temp() < 450, "solo not done after " + self + ": " + on);
                helper.succeed();
            });
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void barrelStateSurvivesSaveAndSync(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper, "m2");
        AbstractVehicleWeapon<?> m2 = weapon(humvee, BMG);
        long now = helper.getLevel().getGameTime();
        BarrelState hot = new BarrelState(333, now, 0.25f);
        m2.barrel().setState(hot);
        m2.barrel().setJammed(true);
        CompoundTag saved = humvee.saveWithoutId(new CompoundTag());
        humvee.discard();
        AbstractVehicle reloaded = spawn(helper, "m2");
        reloaded.load(saved);
        GunBarrel back = weapon(reloaded, BMG).barrel();
        BarrelState loaded = back.state();
        boolean jammed = back.jammed();
        reloaded.discard();
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        SyncDataSerializers.BARREL.write(buf, hot);
        BarrelState wire = SyncDataSerializers.BARREL.read(buf);
        helper.assertTrue(loaded.equals(hot.at(back.spec(), now)) && jammed, "saved " + hot + " jammed, loaded "
                + loaded + " jammed " + jammed);
        helper.assertTrue(wire.equals(hot), "sync " + hot + " -> " + wire);
        helper.succeed();
    }

    private static boolean fire(AbstractVehicleWeapon<?> weapon) {
        weapon.endCooldown();
        return weapon.shoot(new ArrayList<>(List.of(weapon.getWeaponUnit().aimContext())), null);
    }

    /** Same tick as the shots: rounds still at the muzzle, other arenas' rounds out of the box. */
    private static void clearRounds(GameTestHelper helper, AbstractVehicleWeapon<?> weapon) {
        Vec3 muzzle = BarrelActions.muzzle(weapon);
        for (long round : Rounds.within(helper.getLevel(), new AABB(muzzle, muzzle).inflate(8))) {
            Rounds.destroy(helper.getLevel(), round);
        }
    }

    /** Delayed shots fly off: skyward, clear of the other arenas. */
    private static void aimUp(AbstractVehicleWeapon<?> weapon) {
        WeaponUnit unit = (WeaponUnit) weapon.getVehicle().getPartUnit("roof_mount").orElseThrow();
        unit.setXRot(-60);
        unit.updateRot();
    }

    private static AbstractVehicle spawn(GameTestHelper helper, String kit) {
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle vehicle = CommonAssetsManager.vehicleDataManager().getVehicleData(HUMVEE).orElseThrow()
                .construct(helper.getLevel(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(HUMVEE);
        helper.getLevel().addFreshEntity(vehicle);
        WeaponUnit unit = (WeaponUnit) vehicle.getPartUnit("roof_mount").orElseThrow();
        unit.fitKit(unit.hardpoint().indexOf(CombinedAssault.modLocation(kit)));
        return vehicle;
    }

    private static AbstractVehicleWeapon<?> weapon(AbstractVehicle vehicle, ResourceLocation round) {
        return ((WeaponUnit) vehicle.getPartUnit("roof_mount").orElseThrow()).getIndexedWeapons().stream()
                .filter(w -> w.getData().getReload() != null && w.getData().getReload().isAmmo(AmmoItems.stack(round, 1)))
                .findFirst().orElseThrow();
    }

    private static ServerPlayer player(GameTestHelper helper, String name) {
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        return player;
    }

    /** FakePlayer refuses {@code startRiding}: seat assigned directly. */
    private static void seat(AbstractVehicle vehicle, ServerPlayer player) {
        WeaponUnit unit = (WeaponUnit) vehicle.getPartUnit("roof_mount").orElseThrow();
        AbstractVehicle.Seat seat = vehicle.seats.stream().filter(s -> s.partUnit == unit).findFirst().orElseThrow();
        if (!vehicle.changeSeat(player, seat.seatIndex)) {
            throw new IllegalStateException("roof seat taken");
        }
    }

    private static void unseat(AbstractVehicle vehicle, ServerPlayer player) {
        for (AbstractVehicle.Seat seat : vehicle.seats) {
            if (seat.passengerId == player.getId()) {
                seat.partUnit.setOwner(null);
                seat.passengerId = -1;
            }
        }
    }

    private static void atMuzzle(ServerPlayer player, AbstractVehicleWeapon<?> weapon) {
        Vec3 muzzle = BarrelActions.muzzle(weapon);
        Vec3 eye = muzzle.add(0, 0.2, 1.2);
        player.moveTo(eye.x, eye.y - player.getEyeHeight(), eye.z, 180, 0);
    }

    private static void clearBarriers(GameTestHelper helper, AbstractVehicle vehicle) {
        BlockPos.betweenClosed(BlockPos.containing(vehicle.position().add(-6, -1, -6)),
                BlockPos.containing(vehicle.position().add(6, 5, 6))).forEach(pos -> {
            if (helper.getLevel().getBlockState(pos).is(Blocks.BARRIER)) {
                helper.getLevel().setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
            }
        });
    }
}
