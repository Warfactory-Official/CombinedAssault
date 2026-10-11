package com.wf.combinedassault.indirect.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.indirect.GunLaying;
import com.wf.combinedassault.indirect.IndirectFire;
import com.wf.combinedassault.indirect.RoundSpec;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.pojo.AimContext;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;

import java.util.Optional;

/**
 * M252A1 pack/gunnery seams: content fails silently (renamed bone, moved pivot, flipped elevation limit
 * still loads and fires, just wrong). Elevation band isn't the rated 45-85: bipod's elevating screw runs
 * out ~53 deg, so the pack declares 55 and the display is staged to match; a change to one not mirrored
 * in the other is what these catch.
 */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class MortarEmplacementGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation MORTAR = CombinedAssault.modLocation("m252a1");

    private static final float MIN_ELEVATION = 55.0f;
    private static final float MAX_ELEVATION = 85.0f;
    private static final float REST_ELEVATION = 62.0f;

    /** From the structure model: pivot height, barrel length to muzzle. */
    private static final double SOCKET_HEIGHT = 0.112;
    private static final double BARREL = 1.31;

    private static final double TOLERANCE = 0.02;

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void theEmplacementStandsAtItsRestingLay(GameTestHelper helper) {
        AbstractVehicle mortar = spawn(helper);
        if (mortar == null) {
            return;
        }
        WeaponUnit tube = tube(helper, mortar);
        if (tube == null) {
            return;
        }
        if (Math.abs(tube.getXRot() + REST_ELEVATION) > TOLERANCE) {
            helper.fail("a mortar nobody has laid sits at " + (-tube.getXRot())
                    + " degrees, but the display is staged for " + REST_ELEVATION);
            return;
        }
        if (Math.abs(tube.getXRotMax() + MIN_ELEVATION) > TOLERANCE
                || Math.abs(tube.getXRotMin() + MAX_ELEVATION) > TOLERANCE) {
            helper.fail("the band is " + (-tube.getXRotMax()) + " to " + (-tube.getXRotMin())
                    + " degrees, not " + MIN_ELEVATION + " to " + MAX_ELEVATION);
            return;
        }
        mortar.discard();
        helper.succeed();
    }

    /** Unsprung => recoil never tips the base plate (it stacked into hops). */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void recoilLeavesTheBasePlateLevel(GameTestHelper helper) {
        AbstractVehicle mortar = spawn(helper);
        if (mortar == null) {
            return;
        }
        WeaponUnit tube = tube(helper, mortar);
        if (tube == null) {
            return;
        }
        tube.setYRot(35.0f);
        tube.updateRot();
        // Settled first: resting contacts leave ~0.01 degree, not recoil's.
        float[] rest = new float[2];
        helper.startSequence()
                .thenIdle(40)
                .thenExecute(() -> {
                    rest[0] = mortar.getXRot();
                    rest[1] = mortar.getZRot();
                    for (int i = 0; i < 10; i++) {
                        mortar.queueRecoil(tube, 1.0f);
                    }
                })
                .thenIdle(20)
                .thenExecute(() -> {
                    float x = mortar.getXRot() - rest[0];
                    float z = mortar.getZRot() - rest[1];
                    mortar.discard();
                    // Resting creep alone: 0.0013 degree over the 20 ticks.
                    if (Math.abs(x) > 1.0e-2f || Math.abs(z) > 1.0e-2f) {
                        helper.fail("ten rounds tipped the base plate by pitch " + x + ", roll " + z);
                    }
                })
                .thenSucceed();
    }

    /**
     * Muzzle = bone pivot + {@code barrel_length}; structure model's pivot and vehicle file's length
     * must agree with the picture, or shells leave the baseplate pointing correctly and nothing says so.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void theMuzzleIsAtTheEndOfTheTubeAtEveryLay(GameTestHelper helper) {
        AbstractVehicle mortar = spawn(helper);
        if (mortar == null) {
            return;
        }
        WeaponUnit tube = tube(helper, mortar);
        if (tube == null) {
            return;
        }
        for (float elevation : new float[]{MIN_ELEVATION, REST_ELEVATION, MAX_ELEVATION}) {
            for (float bearing : new float[]{0.0f, 90.0f, -135.0f}) {
                tube.setXRot(-elevation);
                tube.setYRot(bearing);
                tube.updateRot();

                Vec3 socket = tube.worldCurrentBoltPosition();
                Vec3 expected = mortar.position().add(0.0, SOCKET_HEIGHT, 0.0);
                if (socket.distanceTo(expected) > TOLERANCE) {
                    helper.fail("the tube pivots at " + socket + " but the structure model puts the socket"
                            + " at " + expected + ", laid " + elevation + " / " + bearing);
                    return;
                }

                AimContext aim = tube.aimContext();
                if (Math.abs(socket.distanceTo(aim.from) - BARREL) > TOLERANCE) {
                    helper.fail("the muzzle is " + socket.distanceTo(aim.from) + " blocks out of the socket,"
                            + " but the tube is " + BARREL + " long");
                    return;
                }
                Vec3 bore = aim.from.subtract(socket).normalize();
                double laid = Math.toDegrees(Math.asin(bore.y));
                if (Math.abs(laid - elevation) > 0.5) {
                    helper.fail("the tube is laid to " + elevation + " degrees but points at " + laid);
                    return;
                }
            }
        }
        mortar.discard();
        helper.succeed();
    }

    /**
     * WFLib is compile-only here (shells absent): checks the gun is pointed at the artillery tag, not
     * one item id, since an id-pointed gun can't load anything added after the gun was written.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void theMortarLoadsFromTheArtilleryTag(GameTestHelper helper) {
        AbstractVehicle mortar = spawn(helper);
        if (mortar == null) {
            return;
        }
        WeaponUnit tube = tube(helper, mortar);
        if (tube == null) {
            return;
        }
        AbstractVehicleWeapon<?> gun = tube.getCurrentWeapon().orElse(null);
        if (gun == null) {
            helper.fail("the emplacement has a mount with no gun on it");
            return;
        }
        if (!gun.isAmmoForWeapon(com.wf.wflib.ammo.AmmoItems.stack(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("wflib", "81mm/he"), 1))) {
            helper.fail("the mortar will not take WFLib 81 mm rounds");
            return;
        }
        if (gun.isAmmoForWeapon(new ItemStack(Items.STONE))) {
            helper.fail("the mortar accepts cobblestone, so its ingredient is matching everything");
            return;
        }
        mortar.discard();
        helper.succeed();
    }

    /**
     * Elevation stops = range limits (ceiling => dead zone floor; floor => max range), set independently
     * of each other (bipod vs shell ballistics): must still describe one continuous reach window.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void theStopsGiveOneContinuousReach(GameTestHelper helper) {
        AbstractVehicle mortar = spawn(helper);
        if (mortar == null) {
            return;
        }
        WeaponUnit tube = tube(helper, mortar);
        if (tube == null) {
            return;
        }
        RoundSpec spec = IndirectFire.specOf(tube);
        if (spec == null) {
            helper.fail("the mortar has no firing solution at all, so there is nothing to lay it by");
            return;
        }
        GunLaying laying = tube.indirectFire();
        Vec3 muzzle = IndirectFire.muzzleOf(tube);
        double nearest = Double.NaN;
        double farthest = Double.NaN;
        boolean reopened = false;
        for (double range = 10.0; range <= 500.0; range += 5.0) {
            if (!laying.solveTo(spec, muzzle, muzzle.add(0.0, -SOCKET_HEIGHT, range))) {
                continue;
            }
            boolean inBand = laying.elevation() >= MIN_ELEVATION - TOLERANCE
                    && laying.elevation() <= MAX_ELEVATION + TOLERANCE;
            if (inBand) {
                reopened |= !Double.isNaN(farthest) && range > farthest + 5.5;
                nearest = Double.isNaN(nearest) ? range : nearest;
                farthest = range;
            }
        }
        if (Double.isNaN(nearest)) {
            helper.fail("no range at all can be laid on inside the band, so the mount and the round"
                    + " do not describe the same weapon");
            return;
        }
        if (reopened) {
            helper.fail("the reach has a hole in it between " + nearest + " and " + farthest + " blocks");
            return;
        }
        if (nearest <= 10.0) {
            helper.fail("the tube has no dead zone under it, so its elevation ceiling is not biting");
            return;
        }
        if (farthest >= 500.0) {
            helper.fail("nothing limits the reach, so its elevation floor is not biting");
            return;
        }
        if (farthest - nearest < 200.0) {
            helper.fail("the mortar only covers " + nearest + " to " + farthest + " blocks, which is too"
                    + " narrow a window to lay a fire mission in");
            return;
        }
        mortar.discard();
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void theEmplacementMountsWhereAnEmplacementGoes(GameTestHelper helper) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(MORTAR);
        if (data.isEmpty()) {
            helper.fail("the mortar " + MORTAR + " is not in the roster");
            return;
        }
        var mountable = data.get().getMountable();
        if (mountable == null || !mountable.enabled) {
            helper.fail("the mortar cannot be mounted on anything");
            return;
        }
        if (!mountable.tags.contains("naval_emplacement")) {
            helper.fail("the mortar does not carry the naval emplacement tag, so a ship's mounting points"
                    + " will not take it: " + mountable.tags);
            return;
        }
        helper.succeed();
    }

    @Nullable
    private static AbstractVehicle spawn(GameTestHelper helper) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(MORTAR);
        if (data.isEmpty()) {
            helper.fail("the mortar " + MORTAR + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle mortar = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        mortar.setDisplayId(MORTAR);
        if (!helper.getLevel().addFreshEntity(mortar)) {
            helper.fail("the mortar could not be added to the world");
            return null;
        }
        return mortar;
    }

    @Nullable
    private static WeaponUnit tube(GameTestHelper helper, AbstractVehicle mortar) {
        PartUnit<?> part = mortar.getPartUnit("mortar").orElse(null);
        if (!(part instanceof WeaponUnit unit)) {
            helper.fail("the emplacement has no weapon station called 'mortar'; it has " + parts(mortar));
            return null;
        }
        return unit;
    }

    private static String parts(AbstractVehicle mortar) {
        return mortar.getPartUnits().stream().map(PartUnit::getId).toList().toString();
    }
}
