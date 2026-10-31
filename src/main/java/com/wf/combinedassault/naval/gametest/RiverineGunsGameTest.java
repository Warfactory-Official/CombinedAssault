package com.wf.combinedassault.naval.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.vehicle.util.VectorUtil;
import com.wf.combinedassault.weapon.pojo.AimContext;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/** Riverine stations on the hull's MOUNT_*_YAW nodes (RIVERINE_ROOT yaw 90 cancelled per station; stern faces aft). */
@PrefixGameTestTemplate(false)
public class RiverineGunsGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation BOAT = CombinedAssault.modLocation("riverine");
    /** id, rest bore sign along vehicle z, target (vehicle frame) inside the arc. */
    private static final List<Object[]> STATIONS = List.of(
            new Object[]{"gun_bow_port", 1, new Vec3(12, 1.5, 14)},
            new Object[]{"gun_bow_starboard", 1, new Vec3(-12, 1.5, 14)},
            new Object[]{"gun_stern", -1, new Vec3(-8, 1.5, -16)});

    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void eachStationRestsOnItsBearingAndLaysOnATarget(GameTestHelper helper) {
        AbstractVehicle boat = spawn(helper);
        if (boat == null) {
            return;
        }
        for (Object[] s : STATIONS) {
            if (!(boat.getPartUnit((String) s[0]).orElse(null) instanceof WeaponUnit unit)) {
                helper.fail("riverine has no weapon part " + s[0]);
                return;
            }
            if (!unit.getData().isSeat() || unit.getData().getSeatOffset().y < 1) {
                helper.fail(s[0] + " station seat unread: " + unit.getData().getSeatOffset());
                return;
            }
            Vec3 pivot = unit.aimPivot().subtract(boat.position());
            Vec3 bore = unit.aimContext().from.subtract(boat.position()).subtract(pivot);
            if (Math.signum(bore.z) != (int) s[1] || Math.abs(bore.x) > 0.05) {
                helper.fail(s[0] + " rests with its muzzle at " + bore + " from the trunnion; expected along z "
                        + s[1]);
                return;
            }
            Vec3 target = boat.position().add((Vec3) s[2]);
            Vec2 aim = unit.aimRot(target);
            unit.setYRot(aim.y - unit.yBarrelSelfRot);
            unit.setXRot(aim.x - unit.xBarrelSelfRot);
            unit.updateRot();
            AimContext fired = unit.aimContext();
            Vec3 laid = VectorUtil.rotToVec(fired.direction.x, fired.direction.y).normalize();
            Vec3 want = target.subtract(fired.from).normalize();
            double degrees = Math.toDegrees(Math.acos(Math.min(1, laid.dot(want))));
            if (degrees > 3) {
                helper.fail(s[0] + " laid " + degrees + " deg off its target (yRot " + unit.getYRot() + ")");
                return;
            }
        }
        boat.discard();
        helper.succeed();
    }

    @Nullable
    private static AbstractVehicle spawn(GameTestHelper helper) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(BOAT);
        if (data.isEmpty()) {
            helper.fail(BOAT + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(BOAT);
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail(BOAT + " could not be added to the world");
            return null;
        }
        return vehicle;
    }
}
