package com.wf.combinedassault.compat.wflib;

import com.wf.wflib.MissileEntity;
import com.wf.wflib.kinetic.KineticPreset;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.pojo.AimContext;

import java.util.List;

/** Dumb bombs are resting WFLib rounds; a guided bomb glides at its aim point as a WFLib missile. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class BombRoundsGameTest {

    private static final String TEMPLATE = "empty";

    static ItemStack wflib(String item) {
        return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("wflib", item)));
    }

    static ItemStack own(String item) {
        return new ItemStack(BuiltInRegistries.ITEM.get(CombinedAssault.modLocation(item)));
    }

    /** mk_82 weapon loads the bomb (resting round: fuse, no contact, dropped) and refuses a cartridge. */
    @GameTest(template = TEMPLATE)
    public static void aMk82IsARestingRound(GameTestHelper helper) {
        var index = CommonAssetsManager.vehicleWeaponManager().getIndex(CombinedAssault.modLocation("mk_82"));
        if (index.isEmpty()) {
            helper.fail("mk_82 missing");
            return;
        }
        var reload = index.get().data().getReload();
        ItemStack bomb = own("bomb_mk_82");
        KineticPreset preset = Munitions.kinetic(bomb);
        if (reload == null || !reload.isAmmo(bomb) || reload.isAmmo(wflib("cartridge_20x102"))) {
            helper.fail("mk_82 ammo: takes bomb " + (reload != null && reload.isAmmo(bomb)));
            return;
        }
        if (preset == null || preset.fuseDelay() != 60 || preset.entityContact() || preset.muzzleSpeed() != 0.0f
                || Math.abs(preset.quadraticDrag() - 1.135 / 227) > 1.0e-7) {
            helper.fail("bomb_mk_82 preset: " + (preset == null ? "missing" : preset.fuseDelay() + " / "
                    + preset.quadraticDrag() + " / contact " + preset.entityContact()));
            return;
        }
        helper.succeed();
    }

    /** Dropped level 40 blocks up, 30 short: closes on the aim point, guided. */
    @GameTest(template = TEMPLATE, timeoutTicks = 40)
    public static void aGbuGlidesAtItsAimPoint(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BaseVehicleData heli = CommonAssetsManager.vehicleDataManager()
                .getVehicleData(CombinedAssault.modLocation("ah64d")).orElse(null);
        if (heli == null) {
            helper.fail("ah64d missing");
            return;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle vehicle = heli.construct(level, Vec3.atBottomCenterOf(at), 0, 0);
        vehicle.setDisplayId(CombinedAssault.modLocation("ah64d"));
        level.addFreshEntity(vehicle);
        // Same tick: a neighbouring arena's setup clears entities overlapping it, and a helicopter overlaps.
        WeaponUnit unit = null;
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part instanceof WeaponUnit w) {
                unit = w;
            }
        }
        if (unit == null) {
            vehicle.discard();
            helper.fail("ah64d has no weapon unit");
            return;
        }
        Vec3 target = helper.absoluteVec(new Vec3(1.5, 6.0, 31.5));
        AimContext aim = new AimContext();
        aim.from = target.add(0.0, 40.0, -30.0);
        aim.direction = new Vec2(0.0f, 0.0f);
        aim.position = target;
        vehicle.setDeltaMovement(0.0, 0.0, 2.0);
        Munitions.fire(vehicle, unit, aim, own("bomb_gbu_39"), 0f, null, null);
        List<MissileEntity> dropped = level.getEntitiesOfClass(MissileEntity.class,
                new AABB(aim.from, aim.from).inflate(1.0));
        vehicle.discard();
        if (dropped.size() != 1) {
            helper.fail("guided bombs dropped: " + dropped.size());
            return;
        }
        MissileEntity bomb = dropped.get(0);
        double before = bomb.position().distanceTo(target);
        for (int i = 0; i < 12 && !bomb.isRemoved(); i++) {
            bomb.tick();
        }
        double after = bomb.position().distanceTo(target);
        boolean flying = !bomb.isRemoved();
        bomb.kill();
        if (!flying || !(after < before - 12.0)) {
            helper.fail(String.format("gbu: distance %.1f -> %.1f, flying %s", before, after, flying));
            return;
        }
        helper.succeed();
    }
}
