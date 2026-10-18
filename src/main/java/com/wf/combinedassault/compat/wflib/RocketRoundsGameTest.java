package com.wf.combinedassault.compat.wflib;

import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.kinetic.KineticPresetRegistry;
import com.wf.wflib.round.RocketEntity;
import com.wf.wflib.round.Rounds;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.wflib.countermeasure.ApsGrenadeEntity;
import com.wf.wflib.countermeasure.Countermeasures;
import net.minecraft.world.item.ItemStack;

/** Rocket weapons fly as WFLib rockets; the APS takes rockets and entity-less rounds. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class RocketRoundsGameTest {

    private static final String TEMPLATE = "empty";

    /** hydra_70 pod loads the Hydra (4520 N / 11.3 kg, CdA 0.0565); faster at burn-out; engageable. */
    @GameTest(template = TEMPLATE)
    public static void aHydraFliesAsAnInterceptableRocket(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var index = CommonAssetsManager.vehicleWeaponManager().getIndex(CombinedAssault.modLocation("hydra_70"));
        ItemStack hydra = BombRoundsGameTest.own("rocket_hydra_70");
        if (index.isEmpty() || index.get().data().getReload() == null
                || !index.get().data().getReload().isAmmo(hydra)) {
            helper.fail("hydra_70 does not load combinedassault:rocket_hydra_70");
            return;
        }
        KineticPreset preset = Munitions.kinetic(hydra);
        if (preset == null || Math.abs(preset.motorAccel() - 4520 / 11.3 / 400.0) > 1.0e-4
                || preset.burnTicks() != 10 || Math.abs(preset.quadraticDrag() - 0.0565 / 11.3) > 1.0e-6) {
            helper.fail("hydra_70 preset: " + (preset == null ? "missing"
                    : preset.motorAccel() + " / " + preset.burnTicks() + " / " + preset.quadraticDrag()));
            return;
        }
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 30.0, 1.5));
        RocketEntity rocket = RocketEntity.fire(level, preset, at, new Vec3(0.0, 1.0, 0.0), 0.0f, Vec3.ZERO, null,
                null, null);
        for (int i = 0; i < preset.burnTicks(); i++) {
            rocket.tick();
        }
        double speed = rocket.getDeltaMovement().length();
        boolean engageable = rocket.interceptEngageable();
        rocket.discard();
        if (!(speed > preset.muzzleSpeed()) || !engageable) {
            helper.fail(String.format("burn-out speed %.3f (muzzle %.3f), engageable %s", speed,
                    preset.muzzleSpeed(), engageable));
            return;
        }
        helper.succeed();
    }

    /** One APS charge per threat: a rocket inside the radius is removed, then a round is. */
    @GameTest(template = TEMPLATE)
    public static void theApsTakesARocketAndARound(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        KineticPreset rocketPreset = Munitions.kinetic(BombRoundsGameTest.own("rocket_hydra_70"));
        KineticPreset shell = KineticPresetRegistry.get(ResourceLocation.fromNamespaceAndPath("wflib", "heat"));
        if (rocketPreset == null || shell == null) {
            helper.fail("combinedassault:rocket_hydra_70 / wflib:heat missing");
            return;
        }
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 30.0, 1.5));
        ApsGrenadeEntity first = charge(level, at);
        RocketEntity rocket = RocketEntity.fire(level, rocketPreset, at.add(0.0, 0.0, 4.0),
                new Vec3(0.0, 0.0, -1.0), 0.0f, Vec3.ZERO, null, null, null);
        first.tick();
        boolean rocketGone = rocket.isRemoved() && first.isRemoved();
        rocket.discard();
        ApsGrenadeEntity second = charge(level, at);
        long round = Rounds.launch(level, shell, at.add(0.0, 0.0, 4.0), new Vec3(0.0, 0.0, -2.0), null, null);
        second.tick();
        boolean roundGone = Rounds.position(level, round) == null && second.isRemoved();
        Rounds.destroy(level, round);
        second.discard();
        if (!rocketGone || !roundGone) {
            helper.fail("APS: rocket taken " + rocketGone + ", round taken " + roundGone);
            return;
        }
        helper.succeed();
    }

    private static ApsGrenadeEntity charge(ServerLevel level, Vec3 at) {
        ApsGrenadeEntity charge = new ApsGrenadeEntity(Countermeasures.APS_GRENADE.get(), level);
        charge.moveTo(at.x, at.y, at.z);
        charge.setNoGravity(true);
        level.addFreshEntity(charge);
        return charge;
    }
}
