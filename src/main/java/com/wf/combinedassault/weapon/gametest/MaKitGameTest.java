package com.wf.combinedassault.weapon.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.hit.ModelData;
import com.wf.combinedassault.ballistics.hit.ModelMounts;
import com.wf.combinedassault.ballistics.hit.NodeRole;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.data.BaseVehicleData;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.hands.RiderHand;
import com.wf.combinedassault.weapon.kit.HardwareKit;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** MineAssault gun kits ({@code tools/internal/stage_ma_kits.py}); generated only with MA loaded ({@code -PmaJar}). */
@GameTestHolder(CombinedAssault.MOD_ID)
public class MaKitGameTest {

    private static final String TEMPLATE = CombinedAssault.MOD_ID + ":empty";
    private static final String MA_ITEMS = "ma_";

    /** vehicle, hardpoint part, kits. */
    private static final String[][] CASES = {
            {"w3", "door_gun", "ma_pkm", "ma_m240b", "ma_m249"},
            {"mh60l", "door_gun_r", "ma_m240b", "ma_m249"},
            {"mh60l", "door_gun_l", "ma_m240b", "ma_m249"},
            {"humvee", "roof_mount", "ma_pkm", "ma_m240b", "ma_m249"}};

    @GameTestGenerator
    public static List<TestFunction> maKits() {
        List<TestFunction> tests = new ArrayList<>();
        if (!ModList.get().isLoaded("mineassault")) {
            return tests;
        }
        for (String[] c : CASES) {
            for (int k = 2; k < c.length; k++) {
                String kit = c[k];
                String name = String.format(Locale.ROOT, "makit_%s_%s_%s", c[0], c[1], kit);
                tests.add(new TestFunction("makits", name, TEMPLATE, 100, 0, true,
                        helper -> fitted(helper, c[0], c[1], kit)));
            }
        }
        return tests;
    }

    /** Offered, fits, its weapon; carried as the MA item; belt feed + mouth, both reload hand goals, reload clip. */
    private static void fitted(GameTestHelper helper, String id, String part, String kitName) {
        ResourceLocation rl = CombinedAssault.modLocation(id);
        BaseVehicleData data = CommonAssetsManager.vehicleDataManager().getVehicleData(rl).orElseThrow();
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle vehicle = data.construct(helper.getLevel(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(rl);
        helper.assertTrue(helper.getLevel().addFreshEntity(vehicle), id + " not added");
        WeaponUnit unit = (WeaponUnit) vehicle.getPartUnit(part).orElseThrow();
        ResourceLocation kitId = CombinedAssault.modLocation(kitName);
        int index = unit.hardpoint().indexOf(kitId);
        helper.assertTrue(index >= 0, part + " offers no " + kitId);
        HardwareKit kit = unit.hardpoint().kits().get(index);
        unit.fitKit(-1);
        unit.fitKit(index);
        ResourceLocation gun = unit.currentWeaponId();
        String item = BuiltInRegistries.ITEM.getKey(kit.stack().getItem()).getNamespace();
        vehicle.discard();
        helper.assertTrue(kitId.equals(unit.kit()) && kitId.equals(gun), "fitted " + unit.kit() + ", gun " + gun);
        helper.assertTrue(item.startsWith(MA_ITEMS), "carried as " + item);
        helper.assertTrue(kit.belt() != null, "no belt");

        ModelData model = CommonAssetsManager.modelData(vehicle.getVehicleId()).orElseThrow();
        String scope = kitId.toString();
        int feed = 0, mouth = 0;
        for (ModelMounts.Mount mount : model.mounts().mounts()) {
            if (mount.role() == NodeRole.BELT && scope.equals(mount.kit()) && mount.target().equals(part)) {
                feed += mount.index() == 0 ? 1 : 0;
                mouth += mount.index() == 1 ? 1 : 0;
            }
        }
        helper.assertTrue(feed == 1 && mouth == 1, "belt feed " + feed + ", mouth " + mouth);
        int right = 0, left = 0;
        for (RiderHand hand : model.hands()) {
            if (scope.equals(hand.kit()) && hand.part().equals(part) && !hand.foot() && "reload".equals(hand.action())) {
                left += hand.left() ? 1 : 0;
                right += hand.left() ? 0 : 1;
            }
        }
        helper.assertTrue(right == 1 && left == 1, "reload hands right " + right + ", left " + left);
        String clip = part + ".reload";
        helper.assertTrue(model.clips() != null && model.clips().clips().stream().anyMatch(c -> c.name().equals(clip)),
                "no clip " + clip);
        helper.succeed();
    }
}
