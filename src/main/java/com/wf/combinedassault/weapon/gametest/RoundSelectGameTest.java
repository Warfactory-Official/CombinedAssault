package com.wf.combinedassault.weapon.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.AbstractVehicleWeapon;
import com.wf.combinedassault.weapon.RoundCount;
import com.wf.wflib.ammo.api.AmmoItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import com.wf.combinedassault.weapon.WeaponContent;

/** One gun per calibre: the pick loads, a different breech round goes back with its variant, stock counts kinds. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class RoundSelectGameTest {

    private static final ResourceLocation T90 = CombinedAssault.modLocation("t90");
    private static final ResourceLocation APFSDS = ResourceLocation.parse("wflib:125mm/apfsds");
    private static final ResourceLocation HEAT = ResourceLocation.parse("wflib:125mm/heat");
    private static final ResourceLocation WRONG_CALIBRE = ResourceLocation.parse("wflib:120mm/apfsds");

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void aPickedRoundSwapsTheBreechAndTheStoresGetTheirShellBack(GameTestHelper helper) {
        AbstractVehicle tank = spawn(helper);
        AbstractVehicleWeapon<?> gun = gun(tank);
        tank.getItemStacks().set(0, AmmoItems.stack(APFSDS, 3));
        tank.getItemStacks().set(1, AmmoItems.stack(HEAT, 2));

        gun.reload();
        helper.assertTrue(is(gun.getLoadedRound(), APFSDS) && gun.getRemainAmmo() == 1,
                "first load takes the first kind in the stores, got " + gun.getLoadedRound());
        helper.assertTrue(is(gun.getSelectedRound(), APFSDS), "first load becomes the pick");

        gun.selectRound(AmmoItems.stack(WRONG_CALIBRE, 1));
        helper.assertTrue(is(gun.getSelectedRound(), APFSDS) && gun.getRemainAmmo() == 1, "a 120mm pick is refused");

        gun.selectRound(AmmoItems.stack(HEAT, 1));
        helper.assertTrue(gun.getRemainAmmo() == 0 && gun.getLoadedRound().isEmpty(), "picking HEAT empties the breech");
        helper.assertTrue(is(tank.getItemStacks().get(0), APFSDS) && tank.getItemStacks().get(0).getCount() == 3,
                "the unloaded APFSDS is back as APFSDS: " + tank.getItemStacks().get(0));

        gun.reload();
        helper.assertTrue(is(gun.getLoadedRound(), HEAT), "the next load is the pick, got " + gun.getLoadedRound());
        helper.assertTrue(tank.getItemStacks().get(1).getCount() == 1, "HEAT came out of the stores");

        helper.runAfterDelay(12, () -> {
            List<RoundCount> stock = gun.roundOptions();
            helper.assertTrue(stock.size() == 2 && is(stock.get(0).round(), APFSDS) && stock.get(0).count() == 3
                    && is(stock.get(1).round(), HEAT) && stock.get(1).count() == 1, "stock " + stock);
            tank.discard();
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 60)
    public static void creativeSupplyOffersEveryVariantAndLoadsThePick(GameTestHelper helper) {
        AbstractVehicle tank = spawn(helper);
        AbstractVehicleWeapon<?> gun = gun(tank);
        tank.getItemStacks().set(0, new ItemStack(WeaponContent.AMMO_CREATIVE.get()));
        gun.selectRound(AmmoItems.stack(HEAT, 1));
        gun.reload();
        helper.assertTrue(is(gun.getLoadedRound(), HEAT), "creative loads the pick, got " + gun.getLoadedRound());
        helper.runAfterDelay(12, () -> {
            List<RoundCount> stock = gun.roundOptions();
            helper.assertTrue(stock.size() >= 2 && stock.stream().allMatch(r -> r.count() == RoundCount.UNLIMITED)
                    && stock.stream().anyMatch(r -> is(r.round(), APFSDS)), "creative stock " + stock);
            tank.discard();
            helper.succeed();
        });
    }

    private static boolean is(ItemStack round, ResourceLocation variant) {
        return variant.equals(round.get(AmmoItems.VARIANT.get()));
    }

    private static AbstractVehicle spawn(GameTestHelper helper) {
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle tank = CommonAssetsManager.vehicleDataManager().getVehicleData(T90).orElseThrow()
                .construct(helper.getLevel(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        tank.setDisplayId(T90);
        helper.getLevel().addFreshEntity(tank);
        return tank;
    }

    private static AbstractVehicleWeapon<?> gun(AbstractVehicle tank) {
        return ((WeaponUnit) tank.getPartUnit("turret").orElseThrow()).currentWeapon();
    }
}
