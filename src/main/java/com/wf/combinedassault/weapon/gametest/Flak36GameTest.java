package com.wf.combinedassault.weapon.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.util.VectorUtil;
import com.wf.combinedassault.dynamics.part.PartClipTracks;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.weapon.pojo.AimContext;
import com.wf.combinedassault.weapon.AbstractVehicleWeapon;
import com.wf.wflib.ammo.api.AmmoItems;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/** Flak 36: eject socket -> case port behind the breech, thrown back and down; 88 mm round fires, recoil clip runs. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class Flak36GameTest {

    private static final ResourceLocation FLAK36 = CombinedAssault.modLocation("flak36");
    private static final ResourceLocation HE = ResourceLocation.fromNamespaceAndPath("wflib", "88x571r/he");

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void theCaseLeavesTheBreechBackAndDown(GameTestHelper helper) {
        BaseVehicleData<?> data = CommonAssetsManager.vehicleDataManager().getVehicleData(FLAK36).orElse(null);
        if (data == null) {
            helper.fail(FLAK36 + " is not in the roster");
            return;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle flak = data.construct(helper.getLevel(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        flak.setDisplayId(FLAK36);
        helper.getLevel().addFreshEntity(flak);
        if (!(flak.getPartUnit("gun").orElse(null) instanceof WeaponUnit unit) || unit.getCurrentWeapon().isEmpty()) {
            flak.discard();
            helper.fail("flak36 has no weapon part 'gun' with a gun on it");
            return;
        }
        AimContext aim = unit.aimContext();
        Vec3 bore = VectorUtil.rotToVec(aim.direction.x, aim.direction.y).normalize();
        Vec3 breech = aim.from.subtract(bore.scale(unit.getCurrentBolt().barrelLength));
        Vec3[] port = unit.worldEject(0);
        AbstractVehicleWeapon<?> weapon = unit.getCurrentWeapon().orElseThrow();
        PartClipTracks.Track fire = flak.clipTracks() == null ? null : flak.clipTracks().track("gun.fire");
        weapon.setRemainAmmo(1);
        weapon.setLoadedRound(AmmoItems.stack(HE, 1));
        boolean fired = weapon.shoot(new ArrayList<>(List.of(aim)), null);
        float kick = fire == null ? -1 : fire.progress(0);
        flak.discard();
        if (port == null) {
            helper.fail("bolt 0 ejects nothing");
            return;
        }
        double behind = port[0].subtract(breech).dot(bore);
        double back = port[1].dot(bore);
        if (port[0].distanceTo(breech) > 0.1 || behind > 0 || back >= 0 || port[1].y >= 0
                || Math.abs(port[1].length() - 3) > 1e-3 || !fired || fire == null || kick > 0.1f) {
            helper.fail("port " + port[0] + " breech " + breech + " (along bore " + behind + "), throw " + port[1]
                    + " bore " + bore + "; fired " + fired + ", fire clip " + fire + " at " + kick);
            return;
        }
        helper.succeed();
    }
}
