package com.wf.combinedassault.vehicle.weapon.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.wflib.ammo.AmmoItems;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.custom.weapon.data.VehicleCannonWeaponData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;
import com.wf.combinedassault.vehicle.weapon.VehicleCannon;

import java.util.Optional;

/** What is in the breech, and what that changes; see {@link AbstractVehicleWeapon#loadedRound}. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class LoadedRoundGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation TANK = CombinedAssault.modLocation("m1a2");

    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void aMagazineTakesOneKindOfRound(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper);
        if (vehicle == null) {
            return;
        }
        VehicleCannon cannon = testCannon(helper, vehicle, 4);
        if (cannon == null) {
            return;
        }
        vehicle.setContainerSize(8);
        vehicle.setItem(0, new ItemStack(Items.STONE, 8));
        vehicle.setItem(1, new ItemStack(Items.DIRT, 8));

        cannon.reload();

        if (cannon.getRemainAmmo() != 4) {
            helper.fail("the magazine holds four and reloading put " + cannon.getRemainAmmo() + " in it");
            return;
        }
        if (!cannon.getLoadedRound().is(Items.STONE)) {
            helper.fail("the gun does not know what it was loaded with: " + cannon.getLoadedRound());
            return;
        }
        if (vehicle.getItem(1).getCount() != 8) {
            helper.fail("the other kind of round was loaded too: " + (8 - vehicle.getItem(1).getCount())
                    + " taken out of a magazine that is already full of something else");
            return;
        }
        vehicle.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void anEmptyGunTakesWhateverIsLoadedNext(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper);
        if (vehicle == null) {
            return;
        }
        VehicleCannon cannon = testCannon(helper, vehicle, 4);
        if (cannon == null) {
            return;
        }
        if (!cannon.getLoadedRound().isEmpty()) {
            helper.fail("a gun that has never been loaded says it has " + cannon.getLoadedRound() + " in it");
            return;
        }
        vehicle.setContainerSize(8);
        vehicle.setItem(0, new ItemStack(Items.STONE, 4));
        cannon.reload();
        if (!cannon.getLoadedRound().is(Items.STONE)) {
            helper.fail("the first reload did not record its round");
            return;
        }

        cannon.setRemainAmmo(0);
        vehicle.setItem(0, new ItemStack(Items.DIRT, 4));
        cannon.reload();

        if (!cannon.getLoadedRound().is(Items.DIRT)) {
            helper.fail("an empty gun would not take a different round: still says "
                    + cannon.getLoadedRound());
            return;
        }
        vehicle.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void theRoundInTheBreechSurvivesASave(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper);
        if (vehicle == null) {
            return;
        }
        VehicleCannon cannon = testCannon(helper, vehicle, 4);
        VehicleCannon reloaded = testCannon(helper, vehicle, 4);
        if (cannon == null || reloaded == null) {
            return;
        }
        vehicle.setContainerSize(8);
        vehicle.setItem(0, new ItemStack(Items.STONE, 4));
        cannon.reload();

        HolderLookup.Provider registries = helper.getLevel().registryAccess();
        CompoundTag tag = cannon.serializeNBT(registries);
        reloaded.deserializeNBT(registries, tag);

        if (reloaded.getRemainAmmo() != cannon.getRemainAmmo()) {
            helper.fail("the magazine came back with " + reloaded.getRemainAmmo() + " rounds, not "
                    + cannon.getRemainAmmo());
            return;
        }
        if (!reloaded.getLoadedRound().is(Items.STONE)) {
            helper.fail("the gun came back not knowing what is in it: " + reloaded.getLoadedRound());
            return;
        }
        vehicle.discard();
        helper.succeed();
    }

    /** The tank gun loads its WFLib calibre and nothing else. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void theTankGunTakesItsCalibre(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper);
        if (vehicle == null) {
            return;
        }
        AbstractVehicleWeapon<?> gun = firstWeapon(helper, vehicle);
        if (gun == null) {
            return;
        }
        boolean own = gun.isAmmoForWeapon(AmmoItems.stack(wflib("120mm/apfsds"), 1));
        boolean other = gun.isAmmoForWeapon(AmmoItems.stack(wflib("125mm/apfsds"), 1))
                || gun.isAmmoForWeapon(new ItemStack(Items.STONE));
        vehicle.discard();
        if (!own || other) {
            helper.fail("m1a2 gun: takes 120mm " + own + ", takes 125mm/stone " + other);
            return;
        }
        helper.succeed();
    }

    /** Same cartridge item, other variant (HEI vs API): a different round, kept out of a part-full magazine. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void aMagazineTakesOneVariant(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper);
        if (vehicle == null) {
            return;
        }
        WeaponUnit unit = firstWeaponUnit(vehicle);
        VehicleCannonWeaponData data = new VehicleCannonWeaponData();
        data.setWeaponId(CombinedAssault.modLocation("gametest_20mm"));
        data.setMaxCapacity(8);
        data.setReload(new VehicleCannonWeaponData.Reload(60,
                Ingredient.of(AmmoItems.item(wflib("20x102")))));
        VehicleCannon cannon = new VehicleCannon(vehicle, unit, 0, data, "gametest_20mm");
        vehicle.setContainerSize(8);
        vehicle.setItem(0, AmmoItems.stack(wflib("20x102/he"), 4));
        vehicle.setItem(1, AmmoItems.stack(wflib("20x102/ap"), 8));
        cannon.reload();
        boolean he = wflib("20x102/he").equals(AmmoItems.variantOf(cannon.getLoadedRound()).id());
        int apLeft = vehicle.getItem(1).getCount();
        vehicle.discard();
        if (cannon.getRemainAmmo() != 4 || !he || apLeft != 8) {
            helper.fail("20mm magazine: " + cannon.getRemainAmmo() + " loaded, HEI " + he + ", API left " + apLeft);
            return;
        }
        helper.succeed();
    }

    private static ResourceLocation wflib(String path) {
        return ResourceLocation.fromNamespaceAndPath("wflib", path);
    }

    @Nullable
    private static AbstractVehicle spawn(GameTestHelper helper) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(TANK);
        if (data.isEmpty()) {
            helper.fail("the test vehicle " + TANK + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(TANK);
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail("the test vehicle could not be added to the world");
            return null;
        }
        return vehicle;
    }

    /** Fresh cannon, not the pack's: pack weapon data is shared across every vehicle of that type; editing it here would edit everybody's gun. */
    @Nullable
    private static VehicleCannon testCannon(GameTestHelper helper, AbstractVehicle vehicle, int capacity) {
        WeaponUnit unit = firstWeaponUnit(vehicle);
        if (unit == null) {
            helper.fail("the test vehicle has no weapon mount to put a gun on");
            return null;
        }
        VehicleCannonWeaponData data = new VehicleCannonWeaponData();
        data.setWeaponId(CombinedAssault.modLocation("gametest_gun"));
        data.setMaxCapacity(capacity);
        data.setReload(new VehicleCannonWeaponData.Reload(60, Ingredient.of(Items.STONE, Items.DIRT)));
        return new VehicleCannon(vehicle, unit, 0, data, "gametest_gun");
    }

    @Nullable
    private static WeaponUnit firstWeaponUnit(AbstractVehicle vehicle) {
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part instanceof WeaponUnit unit) {
                return unit;
            }
        }
        return null;
    }

    @Nullable
    private static AbstractVehicleWeapon<?> firstWeapon(GameTestHelper helper, AbstractVehicle vehicle) {
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part instanceof WeaponUnit unit) {
                AbstractVehicleWeapon<?> weapon = unit.getCurrentWeapon().orElse(null);
                if (weapon != null) {
                    return weapon;
                }
            }
        }
        helper.fail("the test vehicle has no weapon with a round selected");
        return null;
    }
}
