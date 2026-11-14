package com.wf.combinedassault.pack.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.ballistics.component.ComponentDamage;
import com.wf.combinedassault.ballistics.component.ComponentState;
import com.wf.combinedassault.ballistics.component.HullComponents;
import com.wf.combinedassault.pack.CommonAssetsManager;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;

/** Bert's Vehicle Pack (`-PoptionalPacks=bvp`); absent => vacuous. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class BvpPackGameTest {

    private static AbstractVehicle spawn(GameTestHelper helper, String id) {
        ResourceLocation rl = ResourceLocation.parse(id);
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle v = CommonAssetsManager.vehicleDataManager().getVehicleData(rl).orElseThrow()
                .construct(helper.getLevel(), new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        v.setDisplayId(rl);
        helper.getLevel().addFreshEntity(v);
        return v;
    }

    private static boolean absent() {
        return !List.of(System.getProperty("combinedassault.optionalPacks", "").split(",")).contains("bvp");
    }

    /** bvp:f_16c: left wing pool 0 => left pylons + their firing station BROKEN, right side not; L/R = one salvo selection. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void aLostBvpF16WingSilencesOnlyItsPylons(GameTestHelper helper) {
        if (absent()) {
            helper.succeed();
            return;
        }
        AbstractVehicle v = spawn(helper, "bvp:f_16c");
        List<String> report = new ArrayList<>();
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(v.hullComponents().indexOf("left wing") >= 0, "no left wing pool"))
                .thenExecute(() -> {
                    HullComponents pools = v.hullComponents();
                    int left = pools.indexOf("left wing");
                    PartUnit<?> sl = v.getPartUnit("stores_l").orElseThrow();
                    PartUnit<?> sr = v.getPartUnit("stores_r").orElseThrow();
                    PartUnit<?> hl = v.getPartUnit("pylons_l").orElseThrow();
                    WeaponUnit pilot = (WeaponUnit) v.getPartUnit("pilot").orElseThrow();
                    int salvo = pilot.weapons.stream().mapToInt(w -> pilot.salvo(w).size()).max().orElse(0);
                    pools.setHealth(left, 0);
                    ComponentState lostL = ComponentDamage.state(sl);
                    ComponentState lostH = ComponentDamage.state(hl);
                    ComponentState lostR = ComponentDamage.state(sr);
                    pools.setHealth(left, pools.maxHealth(left));
                    ComponentState fixedL = ComponentDamage.state(sl);
                    report.add("salvo " + salvo + ", lost " + lostL + "/" + lostH + "/" + lostR + ", repaired " + fixedL);
                    CombinedAssault.LOGGER.info("[bvp] f_16c wing loss: {}", report);
                    v.discard();
                    if (salvo != 2 || lostL != ComponentState.BROKEN || lostH != ComponentState.BROKEN
                            || lostR == ComponentState.BROKEN || fixedL == ComponentState.BROKEN) {
                        helper.fail(report.toString());
                        return;
                    }
                    helper.succeed();
                });
    }
}
