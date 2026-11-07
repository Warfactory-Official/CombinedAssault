package com.wf.combinedassault.weapon.gametest;

import com.mojang.authlib.GameProfile;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.weapon.AbstractVehicleWeapon;
import com.wf.combinedassault.weapon.RoundCount;
import com.wf.combinedassault.weapon.WeaponContent;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.wflib.ammo.api.AmmoItems;
import com.wf.wflib.magazine.api.MagContents;
import com.wf.wflib.magazine.api.MagFill;
import com.wf.wflib.magazine.api.MagazineItems;
import com.wf.wflib.magazine.api.Run;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Humvee M2: {@code magazines} + {@code loose}. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class MagazineFeedGameTest {

    private static final ResourceLocation HUMVEE = CombinedAssault.modLocation("humvee");
    private static final ResourceLocation BALL = ResourceLocation.parse("wflib:12.7x99/ball");
    private static final ResourceLocation TRACER = ResourceLocation.parse("wflib:12.7x99/tracer");
    private static final ResourceLocation AP = ResourceLocation.parse("wflib:12.7x99/ap");

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void aCanFeedsTopFirstGoesBackEmptyAndALoosePickUnloadsIt(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper);
        AbstractVehicleWeapon<?> m2 = m2(humvee);
        ItemStack mixed = new ItemStack(MagazineItems.CAN_12_7X99.get());
        mixed.set(MagazineItems.CONTENTS.get(), MagContents.of(List.of(new Run(BALL, 2), new Run(TRACER, 1)), 1));
        humvee.getItemStacks().set(0, mixed);
        humvee.getItemStacks().set(1, apCan());
        humvee.getItemStacks().set(2, AmmoItems.stack(BALL, 20));

        m2.selectRound(mixed.copy());
        m2.reload();
        helper.assertTrue(m2.getRemainAmmo() == 3 && m2.getMaxCapacity() == 100 && is(m2.getLoadedRound(), TRACER),
                "picked can seated, tracer on top: " + m2.getRemainAmmo() + "/" + m2.getMaxCapacity() + " "
                        + m2.getLoadedRound());
        helper.assertTrue(humvee.getItemStacks().get(0).isEmpty(), "can left the stores");
        for (int shot = 0; shot < 3; shot++) {
            m2.endCooldown();
            helper.assertTrue(m2.shoot(new ArrayList<>(List.of(m2.getWeaponUnit().aimContext())), null),
                    "shot " + shot);
            helper.assertTrue(shot == 2 ? m2.getLoadedRound().isEmpty() : is(m2.getLoadedRound(), BALL),
                    "after shot " + shot + ": " + m2.getLoadedRound());
        }
        m2.reload();
        helper.assertTrue(m2.getRemainAmmo() == 100 && is(m2.getLoadedRound(), AP), "AP can next: "
                + m2.getRemainAmmo() + " " + m2.getLoadedRound());
        helper.assertTrue(MagFill.isMagazine(humvee.getItemStacks().get(0))
                        && MagFill.contents(humvee.getItemStacks().get(0)).isEmpty(),
                "empty can back in the stores: " + humvee.getItemStacks().get(0));
        helper.assertTrue(RoundCount.sameKind(m2.getSelectedRound(), apCan()), "the AP can became the pick");

        m2.selectRound(AmmoItems.stack(BALL, 1));
        helper.assertTrue(m2.getRemainAmmo() == 0, "loose pick unloads the can");
        helper.assertTrue(humvee.getItemStacks().stream().anyMatch(s -> MagFill.isMagazine(s)
                && MagFill.contents(s).total() == 100), "AP can back, full");
        m2.reload();
        helper.assertTrue(m2.getRemainAmmo() == 20 && m2.getMaxCapacity() != 100 && is(m2.getLoadedRound(), BALL),
                "loose ball, no can: " + m2.getRemainAmmo() + "/" + m2.getMaxCapacity());
        humvee.discard();
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void aLoosePickWithNoLooseRoundsLeftSeatsACan(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper);
        AbstractVehicleWeapon<?> m2 = m2(humvee);
        humvee.getItemStacks().set(0, apCan());
        m2.selectRound(AmmoItems.stack(BALL, 1));
        m2.reload();
        helper.assertTrue(m2.getRemainAmmo() == 100 && is(m2.getLoadedRound(), AP), "AP can seated: "
                + m2.getRemainAmmo() + " " + m2.getLoadedRound());
        humvee.discard();
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void creativeSupplySeatsAFullCanOfThePick(GameTestHelper helper) {
        AbstractVehicle humvee = spawn(helper);
        AbstractVehicleWeapon<?> m2 = m2(humvee);
        humvee.getItemStacks().set(0, new ItemStack(WeaponContent.AMMO_CREATIVE.get()));
        m2.selectRound(apCan());
        m2.reload();
        helper.assertTrue(m2.getRemainAmmo() == 100 && is(m2.getLoadedRound(), AP), "full AP can: "
                + m2.getRemainAmmo() + " " + m2.getLoadedRound());
        helper.runAfterDelay(12, () -> {
            List<RoundCount> stock = m2.roundOptions();
            helper.assertTrue(stock.stream().allMatch(r -> r.count() == RoundCount.UNLIMITED)
                    && stock.stream().anyMatch(r -> MagFill.isMagazine(r.round()))
                    && stock.stream().anyMatch(r -> is(r.round(), BALL)), "creative stock " + stock);
            humvee.discard();
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void anUnfittedM2LeavesItsCansInTheStores(GameTestHelper helper) {
        unfittedGunLeavesItsCans(helper, null);
    }

    @GameTest(template = "empty", timeoutTicks = 500)
    public static void anM2HiddenByTheMk19LeavesItsCansInTheStores(GameTestHelper helper) {
        unfittedGunLeavesItsCans(helper, "mk19");
    }

    /** Refit half: same gunner, same stores => proves the first half could have reloaded. */
    private static void unfittedGunLeavesItsCans(GameTestHelper helper, @Nullable String kit) {
        AbstractVehicle humvee = spawn(helper);
        AbstractVehicleWeapon<?> m2 = m2(humvee);
        fit(humvee, kit);
        humvee.getItemStacks().set(0, apCan());
        WeaponUnit unit = roof(humvee);
        AbstractVehicle.Seat seat = humvee.seats.stream().filter(s -> s.partUnit == unit).findFirst().orElseThrow();
        ServerPlayer gunner = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), "gunner"));
        helper.assertTrue(humvee.changeSeat(gunner, seat.seatIndex), "roof seat taken");
        helper.startSequence()
                .thenIdle(160)
                .thenExecute(() -> {
                    helper.assertTrue(m2.getRemainAmmo() == 0 && !humvee.getItemStacks().get(0).isEmpty(),
                            "unfitted gun took the can: " + m2.getRemainAmmo() + " " + humvee.getItemStacks().get(0));
                    fit(humvee, "m2");
                })
                .thenWaitUntil(() -> helper.assertTrue(m2.getRemainAmmo() == 100, "refit M2 ammo " + m2.getRemainAmmo()))
                .thenExecute(() -> {
                    humvee.discard();
                })
                .thenSucceed();
    }

    private static ItemStack apCan() {
        return MagFill.full(new ItemStack(MagazineItems.CAN_12_7X99.get()), AP);
    }

    private static boolean is(ItemStack round, ResourceLocation variant) {
        return variant.equals(round.get(AmmoItems.VARIANT.get()));
    }

    private static AbstractVehicle spawn(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle vehicle = CommonAssetsManager.vehicleDataManager().getVehicleData(HUMVEE).orElseThrow()
                .construct(helper.getLevel(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(HUMVEE);
        helper.getLevel().addFreshEntity(vehicle);
        fit(vehicle, "m2");
        return vehicle;
    }

    private static WeaponUnit roof(AbstractVehicle vehicle) {
        return (WeaponUnit) vehicle.getPartUnit("roof_mount").orElseThrow();
    }

    private static void fit(AbstractVehicle vehicle, @Nullable String kit) {
        WeaponUnit unit = roof(vehicle);
        unit.fitKit(kit == null ? -1 : unit.hardpoint().indexOf(CombinedAssault.modLocation(kit)));
    }

    private static AbstractVehicleWeapon<?> m2(AbstractVehicle vehicle) {
        return ((WeaponUnit) vehicle.getPartUnit("roof_mount").orElseThrow()).getIndexedWeapons().stream()
                .filter(w -> w.getData().getReload() != null && w.getData().getReload().isAmmo(AmmoItems.stack(BALL, 1)))
                .findFirst().orElseThrow();
    }
}
