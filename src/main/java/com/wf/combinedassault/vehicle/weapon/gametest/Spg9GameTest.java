package com.wf.combinedassault.vehicle.weapon.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.combinedassault.vehicle.part.PartClipTracks;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.pojo.AimContext;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;
import com.wf.wflib.ammo.AmmoItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SPG-9: weapon clip tracks bound off the model, backblast cone behind the breech. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class Spg9GameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation SPG9 = CombinedAssault.modLocation("spg9");
    private static final ResourceLocation PG9 = ResourceLocation.fromNamespaceAndPath("wflib", "73mm_spg9/heat");

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void theFireAndReloadClipsDriveOffTheGun(GameTestHelper helper) {
        AbstractVehicle spg = spawn(helper, 2);
        WeaponUnit unit = spg == null ? null : gun(helper, spg);
        if (unit == null) {
            return;
        }
        AbstractVehicleWeapon<?> weapon = unit.getCurrentWeapon().orElseThrow();
        PartClipTracks tracks = spg.clipTracks();
        PartClipTracks.Track fire = tracks == null ? null : tracks.track("gun.fire");
        PartClipTracks.Track reload = tracks == null ? null : tracks.track("gun.reload");
        if (fire == null || reload == null || fire == reload) {
            spg.discard();
            helper.fail("clip tracks: fire " + fire + ", reload " + reload);
            return;
        }
        float idleFire = fire.progress(0);
        float idleReload = reload.progress(0);
        weapon.setRemainAmmo(1);
        weapon.setLoadedRound(AmmoItems.stack(PG9, 1));
        boolean fired = weapon.shoot(new ArrayList<>(List.of(unit.aimContext())), null);
        float justFired = fire.progress(0);
        weapon.startReload();
        float reloading = reload.progress(0.5f);
        spg.discard();
        if (idleFire != 1 || idleReload != 0 || !fired || justFired > 0.1f || reloading <= 0) {
            helper.fail("idle fire " + idleFire + " (want 1), idle reload " + idleReload + " (want 0), fired " + fired
                    + ", fire progress after the shot " + justFired + ", reload progress " + reloading);
            return;
        }
        helper.succeed();
    }

    /** Up in the air: the cone reaches past the 3x3 arena, where neighbouring arenas' blocks cut line of sight. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void theBackblastHitsBehindTheBreechOnly(GameTestHelper helper) {
        AbstractVehicle spg = spawn(helper, 12);
        WeaponUnit unit = spg == null ? null : gun(helper, spg);
        if (unit == null) {
            return;
        }
        spg.setNoGravity(true);
        helper.startSequence().thenIdle(2).thenExecute(() -> {
            AbstractVehicleWeapon<?> weapon = unit.getCurrentWeapon().orElseThrow();
            AimContext aim = unit.aimContext();
            Vec3 bore = VectorUtil.rotToVec(aim.direction.x, aim.direction.y).normalize();
            Vec3 breech = aim.from.subtract(bore.scale(unit.getCurrentBolt().barrelLength));
            Vec3 side = new Vec3(-bore.z, 0, bore.x).normalize();
            Pig behind = pig(helper, breech.subtract(bore.scale(3)));
            Pig beside = pig(helper, breech.add(side.scale(3)));
            float behindHealth = behind.getHealth();
            float besideHealth = beside.getHealth();
            weapon.setRemainAmmo(1);
            weapon.setLoadedRound(AmmoItems.stack(PG9, 1));
            boolean fired = weapon.shoot(new ArrayList<>(List.of(aim)), null);
            float behindLost = behindHealth - behind.getHealth();
            float besideLost = besideHealth - beside.getHealth();
            double pushed = behind.getDeltaMovement().dot(bore.reverse());
            spg.discard();
            behind.discard();
            beside.discard();
            if (!fired || behindLost <= 0 || pushed <= 0 || besideLost != 0) {
                helper.fail("fired " + fired + ": pig behind lost " + behindLost + " pushed " + pushed
                        + ", pig beside lost " + besideLost + "; breech " + breech + " bore " + bore + " pig "
                        + behind.getBoundingBox().getCenter());
            }
        }).thenSucceed();
    }

    /** NoAI, no gravity: centre on the given point. */
    private static Pig pig(GameTestHelper helper, Vec3 centre) {
        Pig pig = EntityType.PIG.create(helper.getLevel());
        pig.setNoAi(true);
        pig.setNoGravity(true);
        pig.moveTo(centre.x, centre.y - pig.getBbHeight() / 2, centre.z, 0, 0);
        helper.getLevel().addFreshEntity(pig);
        return pig;
    }

    @Nullable
    private static WeaponUnit gun(GameTestHelper helper, AbstractVehicle spg) {
        if (spg.getPartUnit("gun").orElse(null) instanceof WeaponUnit unit && unit.getCurrentWeapon().isPresent()) {
            return unit;
        }
        spg.discard();
        helper.fail("spg9 has no weapon part 'gun' with a gun on it");
        return null;
    }

    @Nullable
    private static AbstractVehicle spawn(GameTestHelper helper, int y) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(SPG9);
        if (data.isEmpty()) {
            helper.fail(SPG9 + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, y, 1));
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(SPG9);
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail(SPG9 + " could not be added to the world");
            return null;
        }
        return vehicle;
    }
}
