package com.wf.combinedassault.weapon.gametest;

import com.wf.wflib.ammo.api.AmmoItems;
import com.wf.wflib.round.api.Rounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.indirect.IndirectFire;
import com.wf.combinedassault.indirect.RoundSpec;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.pojo.AimContext;
import com.wf.combinedassault.weapon.AbstractVehicleWeapon;

import java.util.List;
import java.util.Optional;

/** WFLib rounds loaded into a vehicle gun, laid for and fired, end to end. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class BallisticsShellGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation TANK = CombinedAssault.modLocation("m1a2");
    private static final ResourceLocation MORTAR = CombinedAssault.modLocation("m252a1");
    private static final ResourceLocation APFSDS = ResourceLocation.fromNamespaceAndPath("wflib", "120mm/apfsds");
    /** Faster and longer-fused than the mortar's own 81 mm: a solution built from it is unmistakable. */
    private static final ResourceLocation ROCKET_ASSISTED_ITEM =
            ResourceLocation.fromNamespaceAndPath("wflib", "shell_rap");

    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void aTankGunLoadsItsCartridge(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, TANK);
        if (vehicle == null) {
            return;
        }
        AbstractVehicleWeapon<?> gun = cannon(helper, vehicle);
        if (gun == null) {
            return;
        }
        ItemStack round = AmmoItems.stack(APFSDS, 4);
        vehicle.setContainerSize(8);
        vehicle.setItem(0, round.copy());
        gun.reload();
        boolean loaded = gun.getRemainAmmo() > 0 && ItemStack.isSameItemSameComponents(gun.getLoadedRound(), round);
        vehicle.discard();
        if (!loaded) {
            helper.fail("tank gun: magazine " + gun.getRemainAmmo() + ", breech " + gun.getLoadedRound());
            return;
        }
        helper.succeed();
    }

    /** Empty tube lays for its default 81 mm; a loaded RAP shell (faster, longer fuse) changes the solution. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void theLoadedShellDecidesTheFiringSolution(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, MORTAR);
        if (vehicle == null) {
            return;
        }
        WeaponUnit unit = weaponUnit(vehicle);
        AbstractVehicleWeapon<?> gun = cannon(helper, vehicle);
        if (unit == null || gun == null) {
            return;
        }
        RoundSpec declared = IndirectFire.specOf(unit);
        if (declared == null) {
            vehicle.discard();
            helper.fail("the mortar has no firing solution before anything is loaded");
            return;
        }
        vehicle.setContainerSize(8);
        vehicle.setItem(0, stack(ROCKET_ASSISTED_ITEM).copyWithCount(4));
        gun.reload();
        RoundSpec loaded = IndirectFire.specOf(unit);
        vehicle.discard();
        if (loaded == null || Math.abs(loaded.muzzleSpeed() - declared.muzzleSpeed()) < 1.0e-6
                || loaded.lifeTicks() <= declared.lifeTicks()) {
            helper.fail("solution not the loaded shell's: default " + declared + ", loaded " + loaded);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void firingALoadedShellPutsItInTheWorld(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AbstractVehicle vehicle = spawn(helper, TANK);
        if (vehicle == null) {
            return;
        }
        AbstractVehicleWeapon<?> gun = cannon(helper, vehicle);
        if (gun == null) {
            return;
        }
        vehicle.setContainerSize(8);
        vehicle.setItem(0, AmmoItems.stack(APFSDS, 4));
        gun.reload();
        if (gun.getRemainAmmo() <= 0) {
            vehicle.discard();
            helper.fail("nothing was loaded: breech " + gun.getLoadedRound() + ", hold " + vehicle.getItem(0));
            return;
        }

        Vec3 muzzle = vehicle.position().add(0.0, 3.0, 0.0);
        AimContext aim = new AimContext();
        aim.from = muzzle;
        // Straight up: keeps round in this arena's chunk, not ticked into another test.
        aim.direction = new Vec2(-90.0f, 0.0f);
        if (!gun.shoot(new java.util.ArrayList<>(List.of(aim)), null)) {
            vehicle.discard();
            helper.fail("the gun refused to fire a loaded round");
            return;
        }

        AABB around = new AABB(muzzle.subtract(24.0, 24.0, 24.0), muzzle.add(24.0, 64.0, 24.0));
        long[] rounds = Rounds.within(level, around);
        List<ResourceLocation> fired = java.util.Arrays.stream(rounds)
                .mapToObj(key -> Rounds.preset(level, key).id()).toList();
        java.util.Arrays.stream(rounds).forEach(key -> Rounds.destroy(level, key));
        vehicle.discard();
        if (!fired.equals(List.of(APFSDS))) {
            helper.fail("one shot fired " + fired + ", expected exactly " + APFSDS);
            return;
        }
        helper.succeed();
    }


    private static ItemStack stack(ResourceLocation id) {
        Item item = BuiltInRegistries.ITEM.get(id);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    @Nullable
    private static AbstractVehicle spawn(GameTestHelper helper, ResourceLocation id) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            helper.fail("the test vehicle " + id + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(id);
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail("the test vehicle could not be added to the world");
            return null;
        }
        return vehicle;
    }

    @Nullable
    private static WeaponUnit weaponUnit(AbstractVehicle vehicle) {
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part instanceof WeaponUnit unit && unit.getCurrentWeapon().isPresent()) {
                return unit;
            }
        }
        return null;
    }

    @Nullable
    private static AbstractVehicleWeapon<?> cannon(GameTestHelper helper, AbstractVehicle vehicle) {
        WeaponUnit unit = weaponUnit(vehicle);
        AbstractVehicleWeapon<?> weapon = unit == null ? null : unit.getCurrentWeapon().orElse(null);
        if (weapon == null) {
            helper.fail("the test vehicle has no gun with a round selected");
        }
        return weapon;
    }
}
