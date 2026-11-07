package com.wf.combinedassault.weapon.gametest;

import com.mojang.authlib.GameProfile;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.uav.fpv.gametest.KamikazeKit;
import com.wf.combinedassault.uav.fpv.payload.FpvTrigger;
import com.wf.combinedassault.uav.fpv.payload.PayloadUnit;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.gametest.TestLanes;
import com.wf.combinedassault.weapon.ordnance.Munitions;
import com.wf.wflib.countermeasure.api.*;
import com.wf.wflib.grenade.api.ChargeSpec;
import com.wf.wflib.grenade.api.GrenadeWarheads;
import com.wf.wflib.grenade.api.SightBlocker;
import com.wf.wflib.grenade.api.ThrownCharge;
import com.wf.wflib.missile.api.MissileEntity;
import com.wf.wflib.missile.api.MissilePreset;
import com.wf.wflib.missile.api.MissilePresets;
import com.wf.wflib.recon.api.*;
import com.wf.wflib.round.api.KineticPreset;
import com.wf.wflib.round.api.KineticPresets;
import com.wf.wflib.round.api.RocketEntity;
import com.wf.wflib.round.api.Rounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.projectile.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Countermeasures end to end: {@link ApsGrenadeEntity} against drone ordnance and a top-attack missile, flares and
 * chaff against each seeker band, smoke, jammer, decoy launcher, launch path. "Characterises" tests pin current
 * behaviour that is likely to change with an APS rework.
 */
@PrefixGameTestTemplate(false)
public class CountermeasureGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation MAVIC = CombinedAssault.modLocation("mavic");
    private static final ResourceLocation JAVELIN = MissilePresets.rl("fgm_148");
    private static final ResourceLocation WFLIB_HEAT = ResourceLocation.fromNamespaceAndPath("wflib", "heat");
    private static final double APS_REACH = 8.0;

    private static ApsGrenadeEntity aps(ServerLevel level, Vec3 at) {
        ApsGrenadeEntity charge = new ApsGrenadeEntity(Countermeasures.APS_GRENADE.get(), level);
        charge.moveTo(at.x, at.y, at.z);
        charge.setNoGravity(true);
        level.addFreshEntity(charge);
        return charge;
    }

    private static ArmorStand stand(ServerLevel level, Vec3 at) {
        ArmorStand stand = new ArmorStand(EntityType.ARMOR_STAND, level);
        stand.moveTo(at.x, at.y, at.z);
        stand.setNoGravity(true);
        level.addFreshEntity(stand);
        return stand;
    }

    private static AbstractVehicle mavic(GameTestHelper helper, Vec3 rel) {
        return TestLanes.spawn(helper, MAVIC, helper.absoluteVec(rel));
    }

    private static MissileEntity missile(ServerLevel level, MissilePreset preset, Vec3 at, Vec3 velocity,
                                         Entity target) {
        MissileEntity m = preset.builder(level, target.position()).startArmed().build();
        m.moveTo(at.x, at.y, at.z, 0.0f, 0.0f);
        m.setDeltaMovement(velocity);
        m.seeker().setDesignatedTarget(target.getUUID());
        level.addFreshEntity(m);
        return m;
    }

    private static Arrow arrow(ServerLevel level, Vec3 at, Vec3 velocity) {
        Arrow arrow = new Arrow(EntityType.ARROW, level);
        arrow.moveTo(at.x, at.y, at.z);
        arrow.setNoGravity(true);
        arrow.setDeltaMovement(velocity);
        level.addFreshEntity(arrow);
        return arrow;
    }

    private static ServerPlayer player(GameTestHelper helper, String name, Vec3 at) {
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name));
        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.moveTo(at.x, at.y, at.z);
        return player;
    }

    @Nullable
    private static MissilePreset preset(GameTestHelper helper, ResourceLocation id) {
        MissilePreset preset = MissilePresets.exists(id) ? MissilePresets.get(id) : MissilePresets.catalogueEntry(id);
        if (preset == null) {
            helper.fail("no missile " + id);
        }
        return preset;
    }

    // ---- APS vs top-attack missile -------------------------------------------------------------------------

    /** The Javelin preset really is a top-attack round: IR seeker, LOFT profile. Guards the tests below. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE)
    public static void theJavelinIsATopAttackRound(GameTestHelper helper) {
        MissilePreset javelin = preset(helper, JAVELIN);
        if (javelin == null) {
            return;
        }
        MissileEntity m = javelin.build(helper.getLevel(), Vec3.ZERO);
        helper.assertTrue(m.seeker().mode() == com.wf.wflib.missile.api.SeekerMode.INFRARED,
                "Javelin seeker " + m.seeker().mode());
        helper.assertTrue(m.flight().getAttackProfile() == com.wf.wflib.missile.api.AttackProfile.LOFT,
                "Javelin attack profile " + m.flight().getAttackProfile());
        m.discard();
        helper.succeed();
    }

    /** Characterises: discard of an armed Javelin is vetoed into a shoot-down. It is downed, not removed, charge spent. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE)
    public static void anApsDownsAJavelinOnItsDive(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MissilePreset javelin = preset(helper, JAVELIN);
        if (javelin == null) {
            return;
        }
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 30.0, 1.5));
        ArmorStand target = stand(level, at.subtract(0.0, 6.0, 0.0));
        ApsGrenadeEntity charge = aps(level, at);
        MissileEntity m = missile(level, javelin, at.add(0.0, 5.0, 0.0), new Vec3(0.0, -4.0, 0.0), target);
        charge.tick();
        boolean spent = charge.isRemoved();
        boolean downed = m.damage().isDowned();
        boolean gone = m.isRemoved();
        boolean detonated = m.fuze().detonated();
        m.leaveWorld();
        charge.discard();
        target.discard();
        helper.assertTrue(spent, "charge not spent on a Javelin 5 blocks overhead");
        helper.assertTrue(downed, "Javelin not downed by the APS");
        helper.assertFalse(gone, "Javelin removed outright");
        helper.assertFalse(detonated, "the warhead fired as the APS took it");
        helper.succeed();
    }

    /** Characterises: the downed Javelin coasts on and its warhead still fires, so the dive is not stopped. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void aDownedJavelinStillDetonates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MissilePreset javelin = preset(helper, JAVELIN);
        if (javelin == null) {
            return;
        }
        Vec3 ground = helper.absoluteVec(new Vec3(1.5, 30.0, 1.5));
        ArmorStand target = stand(level, ground);
        ApsGrenadeEntity charge = aps(level, ground.add(0.0, 2.0, 0.0));
        MissileEntity m = missile(level, javelin, ground.add(0.0, 40.0, 0.0), new Vec3(0.0, -3.0, 0.0), target);
        boolean downed = false;
        int ticks = 0;
        while (!m.isRemoved() && !m.fuze().detonated() && ticks++ < 80) {
            m.tick();
            charge.tick();
            downed |= m.damage().isDowned();
        }
        boolean detonated = m.fuze().detonated();
        m.leaveWorld();
        charge.discard();
        target.discard();
        helper.assertTrue(downed, "the APS never downed the Javelin in " + ticks + " ticks");
        helper.assertTrue(detonated, "the downed Javelin did not detonate in " + ticks + " ticks");
        helper.succeed();
    }

    /** Outside the 8 block reach the missile is untouched and the charge stays live. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE)
    public static void anApsIgnoresAJavelinOutsideItsReach(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MissilePreset javelin = preset(helper, JAVELIN);
        if (javelin == null) {
            return;
        }
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 30.0, 1.5));
        ArmorStand target = stand(level, at.subtract(0.0, 20.0, 0.0));
        ApsGrenadeEntity charge = aps(level, at);
        MissileEntity m = missile(level, javelin, at.add(0.0, APS_REACH + 3.0, 0.0), new Vec3(0.0, -0.5, 0.0), target);
        charge.tick();
        boolean untouched = !m.isRemoved() && !charge.isRemoved();
        m.discard();
        charge.discard();
        target.discard();
        helper.assertTrue(untouched, "APS reacted to a missile " + (APS_REACH + 3.0) + " blocks away");
        helper.succeed();
    }

    // ---- APS vs Mavic with ordnance --------------------------------------------------------------------------

    /** Characterises: a kamikaze Mavic is a vehicle, not a projectile or rocket => the APS never sees it. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void anApsDoesNotSeeAKamikazeMavic(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AbstractVehicle drone = mavic(helper, new Vec3(1.5, 2.0, 1.5));
        helper.runAfterDelay(2, () -> {
            PayloadUnit hardpoint = KamikazeKit.strap(drone);
            hardpoint.setOn(true);
            ApsGrenadeEntity charge = aps(level, drone.position().add(0.0, 1.0, 3.0));
            drone.setDeltaMovement(0.0, 0.0, 1.5);
            charge.tick();
            boolean droneAlive = !drone.isRemoved() && hardpoint.isLoaded();
            boolean chargeAlive = !charge.isRemoved();
            charge.discard();
            drone.discard();
            helper.assertTrue(droneAlive && chargeAlive, "kamikaze Mavic 3 blocks from an APS: drone alive "
                    + droneAlive + ", charge alive " + chargeAlive);
            helper.succeed();
        });
    }

    /** Dropped HE mortar bomb (RELEASE) is an entity-less round: the APS takes it, drone untouched. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void anApsTakesAMortarBombDroppedFromAMavic(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AbstractVehicle drone = mavic(helper, new Vec3(1.5, 2.0, 1.5));
        helper.runAfterDelay(2, () -> {
            PayloadUnit hardpoint = KamikazeKit.strap(drone, ResourceLocation.fromNamespaceAndPath("wflib", "81mm/he"));
            hardpoint.setOn(true);
            helper.runAfterDelay(hardpoint.armingTicks() + 2, () -> {
                int before = Rounds.count(level);
                boolean fired = hardpoint.fire(FpvTrigger.RELEASE, null, 0);
                long[] keys = Rounds.within(level, drone.getBoundingBox().inflate(APS_REACH));
                ApsGrenadeEntity charge = aps(level, drone.position().add(0.0, 0.0, 3.0));
                charge.tick();
                int after = Rounds.count(level);
                boolean droneAlive = !drone.isRemoved();
                boolean chargeSpent = charge.isRemoved();
                charge.discard();
                drone.discard();
                helper.assertTrue(fired && keys.length > 0, "release fired " + fired + ", rounds near drone "
                        + keys.length);
                helper.assertTrue(chargeSpent && after == before, "APS spent " + chargeSpent + ", rounds " + before
                        + " -> " + after);
                helper.assertTrue(droneAlive, "the APS took the carrier with the bomb");
                helper.succeed();
            });
        });
    }

    /** Same, an RPG-7 PG-7V released from the Mavic. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 200)
    public static void anApsTakesAReleasedPg7vRound(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        AbstractVehicle drone = mavic(helper, new Vec3(1.5, 2.0, 1.5));
        helper.runAfterDelay(2, () -> {
            PayloadUnit hardpoint = KamikazeKit.strap(drone);
            hardpoint.setOn(true);
            helper.runAfterDelay(hardpoint.armingTicks() + 2, () -> {
                boolean fired = hardpoint.fire(FpvTrigger.RELEASE, null, 0);
                ApsGrenadeEntity charge = aps(level, drone.position().add(0.0, 0.0, 3.0));
                charge.tick();
                boolean spent = charge.isRemoved();
                int left = Rounds.within(level, drone.getBoundingBox().inflate(APS_REACH)).length;
                charge.discard();
                drone.discard();
                helper.assertTrue(fired && spent && left == 0, "PG-7V release: fired " + fired + ", APS spent "
                        + spent + ", rounds left near the drone " + left);
                helper.succeed();
            });
        });
    }

    /** Characterises: a bomber's dropped hand charge is a Projectile => the APS spends itself on it. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE)
    public static void anApsTakesAThrownChargeDroppedByABomber(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 30.0, 1.5));
        ThrownCharge frag = ThrownCharge.create(level, at.add(0.0, 0.0, 3.0), new Vec3(0.0, -0.3, 0.0), ItemStack.EMPTY,
                new ChargeSpec(GrenadeWarheads.FRAG, 100, 0.5f, false, false, 0, 0, 0), 100, null);
        frag.setNoGravity(true);
        level.addFreshEntity(frag);
        ApsGrenadeEntity charge = aps(level, at);
        charge.tick();
        boolean taken = frag.isRemoved() && charge.isRemoved();
        frag.discard();
        charge.discard();
        helper.assertTrue(taken, "APS vs dropped frag grenade: frag removed " + frag.isRemoved() + ", charge spent "
                + charge.isRemoved());
        helper.succeed();
    }

    // ---- APS selection, reach, lifetime ----------------------------------------------------------------------

    /** Several threats in reach: the fastest goes, the rest stay. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE)
    public static void anApsTakesTheFastestThreatOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        KineticPreset rocketPreset = Munitions.kinetic(BombRoundsGameTest.own("rocket_hydra_70"));
        if (rocketPreset == null) {
            helper.fail("combinedassault:rocket_hydra_70 missing");
            return;
        }
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 30.0, 1.5));
        ApsGrenadeEntity charge = aps(level, at);
        Arrow slow = arrow(level, at.add(2.0, 0.0, 0.0), new Vec3(0.0, 0.0, 0.1));
        Arrow mid = arrow(level, at.add(-2.0, 0.0, 0.0), new Vec3(0.0, 0.0, 1.0));
        RocketEntity fast = RocketEntity.fire(level, rocketPreset, at.add(0.0, 0.0, 4.0), new Vec3(0.0, 0.0, -1.0),
                0.0f, new Vec3(0.0, 0.0, -3.0), null, null, null);
        charge.tick();
        boolean right = fast.isRemoved() && !slow.isRemoved() && !mid.isRemoved() && charge.isRemoved();
        String state = "rocket " + fast.isRemoved() + ", slow arrow " + slow.isRemoved() + ", mid arrow "
                + mid.isRemoved() + ", charge " + charge.isRemoved();
        fast.discard();
        slow.discard();
        mid.discard();
        charge.discard();
        helper.assertTrue(right, "removed: " + state);
        helper.succeed();
    }

    /** Characterises: no armour/warhead check => a slow harmless arrow is a valid target if it is alone. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE)
    public static void anApsSpendsItselfOnAnArrow(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 30.0, 1.5));
        ApsGrenadeEntity charge = aps(level, at);
        Arrow arrow = arrow(level, at.add(0.0, 0.0, 3.0), new Vec3(0.0, 0.0, 0.05));
        charge.tick();
        boolean spent = arrow.isRemoved() && charge.isRemoved();
        arrow.discard();
        charge.discard();
        helper.assertTrue(spent, "arrow removed " + arrow.isRemoved() + ", charge spent " + charge.isRemoved());
        helper.succeed();
    }

    /** One entity-less round at 7 blocks goes; one at 9 stays. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE)
    public static void anApsReachesEightBlocksForRounds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        KineticPreset shell = KineticPresets.get(WFLIB_HEAT);
        if (shell == null) {
            helper.fail("wflib:heat missing");
            return;
        }
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 30.0, 1.5));
        long far = Rounds.launch(level, shell, at.add(0.0, 0.0, APS_REACH + 1.0), new Vec3(0.0, 0.0, 0.01), null, null);
        ApsGrenadeEntity charge = aps(level, at);
        charge.tick();
        boolean farKept = Rounds.position(level, far) != null && !charge.isRemoved();
        long near = Rounds.launch(level, shell, at.add(0.0, 0.0, APS_REACH - 1.0), new Vec3(0.0, 0.0, 0.01), null,
                null);
        charge.tick();
        boolean nearTaken = Rounds.position(level, near) == null && charge.isRemoved();
        Rounds.destroy(level, far);
        Rounds.destroy(level, near);
        charge.discard();
        helper.assertTrue(farKept, "APS reacted to a round " + (APS_REACH + 1.0) + " blocks away");
        helper.assertTrue(nearTaken, "APS ignored a round " + (APS_REACH - 1.0) + " blocks away");
        helper.succeed();
    }

    /** Idle: nothing in reach, charge stays. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE)
    public static void anApsWithNothingInReachStaysLive(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ApsGrenadeEntity charge = aps(level, helper.absoluteVec(new Vec3(1.5, 30.0, 1.5)));
        for (int i = 0; i < 10; i++) {
            charge.tick();
        }
        boolean live = !charge.isRemoved();
        charge.discard();
        helper.assertTrue(live, "idle APS vanished");
        helper.succeed();
    }

    /** Characterises: flares are Projectiles and not CountermeasureGrenades => an APS eats a friendly flare. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE)
    public static void anApsEatsAFlare(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 30.0, 1.5));
        ApsGrenadeEntity charge = aps(level, at);
        FlareEntity flare = new FlareEntity(Countermeasures.FLARE.get(), level);
        flare.moveTo(at.x, at.y, at.z + 3.0);
        flare.setDeltaMovement(0.0, 0.0, 0.5);
        level.addFreshEntity(flare);
        charge.tick();
        boolean eaten = flare.isRemoved();
        flare.discard();
        charge.discard();
        helper.assertTrue(eaten, "flare 3 blocks from an APS survived; charge spent " + charge.isRemoved());
        helper.succeed();
    }

    /** Other countermeasure grenades (smoke, a second APS) are exempt. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE)
    public static void anApsLeavesOtherCountermeasureGrenadesAlone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 30.0, 1.5));
        ApsGrenadeEntity charge = aps(level, at);
        SmokeGrenadeEntity smoke = new SmokeGrenadeEntity(Countermeasures.SMOKE_GRENADE.get(), level);
        smoke.moveTo(at.x, at.y, at.z + 3.0);
        smoke.setNoGravity(true);
        smoke.setDeltaMovement(0.0, 0.0, 1.0);
        level.addFreshEntity(smoke);
        ApsGrenadeEntity other = aps(level, at.add(0.0, 0.0, -3.0));
        other.setDeltaMovement(0.0, 0.0, -1.0);
        charge.tick();
        boolean ignored = !smoke.isRemoved() && !other.isRemoved() && !charge.isRemoved();
        smoke.discard();
        other.discard();
        charge.discard();
        helper.assertTrue(ignored, "APS reacted to a countermeasure grenade");
        helper.succeed();
    }

    /** First block it bounces off ends it; unused charge does not linger on the ground. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void anApsChargeIsSpentOnItsFirstBounce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 4.0, 1.5));
        ApsGrenadeEntity charge = new ApsGrenadeEntity(Countermeasures.APS_GRENADE.get(), level);
        charge.moveTo(at.x, at.y, at.z);
        level.addFreshEntity(charge);
        int[] ticks = {0};
        helper.onEachTick(() -> ticks[0]++);
        helper.succeedWhen(() -> helper.assertTrue(charge.isRemoved(), "charge still bouncing after " + ticks[0]
                + " ticks"));
    }

    /** Uncollected charge lapses at 1200 ticks. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE)
    public static void anApsChargeLapsesAfterItsLife(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ApsGrenadeEntity charge = aps(level, helper.absoluteVec(new Vec3(1.5, 30.0, 1.5)));
        charge.tickCount = 1199;
        charge.tick();
        boolean early = charge.isRemoved();
        charge.tickCount = 1200;
        charge.tick();
        boolean lapsed = charge.isRemoved();
        charge.discard();
        helper.assertFalse(early, "charge lapsed at tick 1199");
        helper.assertTrue(lapsed, "charge still live at tick 1200");
        helper.succeed();
    }

    // ---- Flares and chaff vs seekers -----------------------------------------------------------------------

    /** Chaff role: a flare (RADAR strength 1.0) takes an active-radar round off a Mavic; control holds it. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void aFlareActsAsChaffAgainstAnActiveRadarRound(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MissilePreset aim120 = preset(helper, CombinedAssault.modLocation("aim_120"));
        if (aim120 == null) {
            return;
        }
        AbstractVehicle target = mavic(helper, new Vec3(1.5, 30.0, 1.5));
        helper.runAfterDelay(2, () -> {
            target.setNoGravity(true);
            Vec3 from = target.position().add(0.0, 40.0, 0.0);
            MissileEntity control = missile(level, aim120, from, target.position().subtract(from).normalize()
                    .scale(4.0), target);
            control.tick();
            control.tick();
            boolean held = target.getUUID().equals(control.seeker().getDesignatedTargetId());
            control.discard();
            FlareEntity flare = new FlareEntity(Countermeasures.FLARE.get(), level);
            flare.moveTo(target.getX() + 1.0, target.getY(), target.getZ());
            level.addFreshEntity(flare);
            boolean seduced = false;
            for (int i = 0; i < 12 && !seduced; i++) {
                MissileEntity m = missile(level, aim120, from, target.position().subtract(from).normalize()
                        .scale(4.0), target);
                m.tick();
                m.tick();
                seduced = flare.getUUID().equals(m.seeker().getDesignatedTargetId());
                m.discard();
            }
            flare.discard();
            target.discard();
            helper.assertTrue(held, "no flare: active radar lost the Mavic");
            helper.assertTrue(seduced, "no active-radar round took the flare in 12 tries");
            helper.succeed();
        });
    }

    /** IR round ignores nothing in the other band: a decoy beyond 16 blocks from the lock is not considered. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE)
    public static void aFlareBeyondSixteenBlocksDoesNotSeduce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MissilePreset aim9 = preset(helper, CombinedAssault.modLocation("aim_9"));
        if (aim9 == null) {
            return;
        }
        Vec3 from = helper.absoluteVec(new Vec3(1.5, 90.0, 1.5));
        ArmorStand target = stand(level, from.subtract(0.0, 60.0, 0.0));
        FlareEntity flare = new FlareEntity(Countermeasures.FLARE.get(), level);
        flare.moveTo(target.getX(), target.getY() + 20.0, target.getZ());
        level.addFreshEntity(flare);
        boolean seduced = false;
        for (int i = 0; i < 10 && !seduced; i++) {
            MissileEntity m = missile(level, aim9, from, new Vec3(0.0, -4.0, 0.0), target);
            m.tick();
            seduced = flare.getUUID().equals(m.seeker().getDesignatedTargetId());
            m.discard();
        }
        flare.discard();
        target.discard();
        helper.assertFalse(seduced, "a flare 20 blocks from the lock seduced an IR round");
        helper.succeed();
    }

    /** Optical (TOW) seeker is not decoyable by a flare. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE)
    public static void aFlareDoesNotSeduceAnOpticalRound(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MissilePreset tow = preset(helper, CombinedAssault.modLocation("tow"));
        if (tow == null) {
            return;
        }
        Vec3 from = helper.absoluteVec(new Vec3(1.5, 60.0, 1.5));
        ArmorStand target = stand(level, from.subtract(0.0, 40.0, 0.0));
        FlareEntity flare = new FlareEntity(Countermeasures.FLARE.get(), level);
        flare.moveTo(target.getX() + 1.0, target.getY(), target.getZ());
        level.addFreshEntity(flare);
        boolean seduced = false;
        for (int i = 0; i < 10 && !seduced; i++) {
            MissileEntity m = missile(level, tow, from, new Vec3(0.0, -4.0, 0.0), target);
            m.tick();
            seduced = flare.getUUID().equals(m.seeker().getDesignatedTargetId());
            m.discard();
        }
        flare.discard();
        target.discard();
        helper.assertFalse(seduced, "a flare seduced an optical round");
        helper.succeed();
    }

    /** Smoke on the line of sight also costs a heat seeker its lock. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE)
    public static void smokeBreaksAnInfraredLock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MissilePreset aim9 = preset(helper, CombinedAssault.modLocation("aim_9"));
        if (aim9 == null) {
            return;
        }
        Vec3 from = helper.absoluteVec(new Vec3(1.5, 60.0, 1.5));
        ArmorStand target = stand(level, from.subtract(0.0, 40.0, 0.0));
        SmokeGrenadeEntity smoke = new SmokeGrenadeEntity(Countermeasures.SMOKE_GRENADE.get(), level);
        Vec3 mid = from.add(target.getBoundingBox().getCenter().subtract(from).scale(0.5));
        smoke.moveTo(mid.x, mid.y, mid.z);
        smoke.setNoGravity(true);
        level.addFreshEntity(smoke);
        MissileEntity m = missile(level, aim9, from, new Vec3(0.0, -4.0, 0.0), target);
        m.tick();
        boolean lost = m.seeker().getDesignatedTargetId() == null;
        m.discard();
        smoke.discard();
        target.discard();
        helper.assertTrue(lost, "IR round saw through smoke");
        helper.succeed();
    }

    // ---- Smoke, flare entity lifecycle -----------------------------------------------------------------------

    /** Hard bounce pops the smoke: synced flag, 10 block box, SightBlocker, lapses at 600 ticks. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100)
    public static void smokePopsOnAHardBounceIntoABlockingBox(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 5.0, 1.5));
        SmokeGrenadeEntity smoke = new SmokeGrenadeEntity(Countermeasures.SMOKE_GRENADE.get(), level);
        smoke.moveTo(at.x, at.y, at.z);
        smoke.setDeltaMovement(0.0, -1.0, 0.0);
        level.addFreshEntity(smoke);
        helper.succeedWhen(() -> {
            helper.assertTrue(smoke.popped(), "smoke has not popped");
            smoke.refreshDimensions();
            helper.assertTrue(smoke.getBoundingBox().getXsize() > 9.0, "popped box " + smoke.getBoundingBox());
            helper.assertTrue(((SightBlocker) smoke).blocksSight(), "popped smoke does not block sight");
            smoke.tickCount = 599;
            smoke.tick();
            helper.assertTrue(smoke.isRemoved(), "smoke outlived 600 ticks");
        });
    }

    /** Flare burns out at 200 ticks and on any block. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE)
    public static void aFlareBurnsOutOnTimeAndOnBlocks(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 at = helper.absoluteVec(new Vec3(1.5, 30.0, 1.5));
        FlareEntity timed = new FlareEntity(Countermeasures.FLARE.get(), level);
        timed.moveTo(at.x, at.y, at.z);
        level.addFreshEntity(timed);
        timed.tickCount = FlareEntity.LIFE;
        timed.tick();
        boolean early = timed.isRemoved();
        timed.tickCount = FlareEntity.LIFE + 1;
        timed.tick();
        boolean late = timed.isRemoved();
        timed.discard();
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.STONE);
        Vec3 above = helper.absoluteVec(new Vec3(1.5, 3.0, 1.5));
        FlareEntity falling = new FlareEntity(Countermeasures.FLARE.get(), level);
        falling.moveTo(above.x, above.y, above.z);
        falling.setDeltaMovement(0.0, -1.0, 0.0);
        level.addFreshEntity(falling);
        int n = 0;
        while (!falling.isRemoved() && n++ < 10) {
            falling.tick();
        }
        boolean blocked = falling.isRemoved();
        falling.discard();
        helper.assertFalse(early, "flare burned out a tick early");
        helper.assertTrue(late, "flare outlived " + FlareEntity.LIFE + " ticks");
        helper.assertTrue(blocked, "flare passed through a block");
        helper.succeed();
    }

    /** Both decoy bands: flare THERMAL 2.0 / RADAR 1.0, nothing else. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE)
    public static void aFlareSeducesThermalAndRadarOnly(GameTestHelper helper) {
        FlareEntity flare = new FlareEntity(Countermeasures.FLARE.get(), helper.getLevel());
        for (Band band : Band.values()) {
            float want = band == Band.THERMAL ? 2.0f : band == Band.RADAR ? 1.0f : 0.0f;
            helper.assertTrue(flare.decoyStrength(band) == want, band + " strength " + flare.decoyStrength(band));
        }
        flare.discard();
        helper.succeed();
    }

    // ---- Launch path -----------------------------------------------------------------------------------------

    /** Flare: heading x 0.5 + carrier velocity, spread ignored. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE)
    public static void aFlareLeavesAlongTheHeadingPlusCarrierVelocity(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 from = helper.absoluteVec(new Vec3(1.5, 30.0, 1.5));
        Vec3 carrier = new Vec3(0.2, 0.0, -0.1);
        Entity flare = Countermeasures.FLARE_CARTRIDGE.get().deploy(level, from, new Vec3(0.0, 0.0, 2.0), 30.0f,
                carrier, null, null);
        Vec3 v = flare.getDeltaMovement();
        flare.discard();
        Vec3 want = new Vec3(0.2, 0.0, 0.4);
        helper.assertTrue(v.distanceTo(want) < 1.0e-9, "flare velocity " + v + ", expected " + want);
        helper.succeed();
    }

    /** Grenades: kind speed along the heading, triangular spread, carrier velocity added. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE)
    public static void grenadesLeaveAtTheirKindSpeed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 from = helper.absoluteVec(new Vec3(1.5, 30.0, 1.5));
        Vec3 heading = new Vec3(0.0, 0.0, 1.0);
        Object[][] kinds = {{Countermeasures.SMOKE_GRENADE_ITEM.get(), 1.0}, {Countermeasures.APS_GRENADE_ITEM.get(), 15.0}};
        for (Object[] k : kinds) {
            CountermeasureItem item = (CountermeasureItem) k[0];
            Entity exact = item.deploy(level, from, heading, 0.0f, Vec3.ZERO, null, null);
            Vec3 v = exact.getDeltaMovement();
            exact.discard();
            helper.assertTrue(v.distanceTo(heading.scale((Double) k[1])) < 1.0e-9, item.kind() + " exact velocity " + v);
            boolean spread = false;
            for (int i = 0; i < 20 && !spread; i++) {
                Entity e = item.deploy(level, from, heading, 20.0f, Vec3.ZERO, null, null);
                spread = e.getDeltaMovement().normalize().distanceTo(heading) > 1.0e-3;
                e.discard();
            }
            helper.assertTrue(spread, item.kind() + " ignored its inaccuracy");
        }
        helper.succeed();
    }

    /** A grenade passes its own carrier. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE)
    public static void aGrenadeNeverHitsItsCarrier(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 from = helper.absoluteVec(new Vec3(1.5, 30.0, 1.5));
        ArmorStand carrier = stand(level, from.add(0.0, 0.0, 1.5));
        Entity smoke = Countermeasures.SMOKE_GRENADE_ITEM.get().deploy(level, from, new Vec3(0.0, 0.0, 1.0), 0.0f,
                Vec3.ZERO, null, carrier);
        smoke.setNoGravity(true);
        for (int i = 0; i < 4; i++) {
            smoke.tick();
        }
        boolean passed = smoke.getZ() > carrier.getZ() + 0.5;
        float health = carrier.getHealth();
        smoke.discard();
        carrier.discard();
        helper.assertTrue(passed, "grenade stopped at its carrier, z " + (smoke.getZ() - from.z));
        helper.assertTrue(health == carrier.getMaxHealth(), "carrier hurt: " + health);
        helper.succeed();
    }

    /** Three catalogue ids registered as items when requested. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE)
    public static void theCatalogueItemsAreRegistered(GameTestHelper helper) {
        for (String id : new String[]{"flare_cartridge", "smoke_grenade", "aps_grenade"}) {
            ResourceLocation rl = ResourceLocation.fromNamespaceAndPath("wflib", id);
            helper.assertTrue(BuiltInRegistries.ITEM.containsKey(rl), id + " not registered");
        }
        helper.succeed();
    }

    // ---- Vehicle weapon path ---------------------------------------------------------------------------------

    /** The shipped flare / smoke / APS launchers all load wflib's countermeasure cartridges. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE)
    public static void theLauncherWeaponsLoadCountermeasureCartridges(GameTestHelper helper) {
        String[][] weapons = {{"decoy_flare", "flare_cartridge"}, {"launcher_smoke_grenade", "smoke_grenade"},
                {"launcher_aps_grenade", "aps_grenade"}};
        for (String[] w : weapons) {
            var index = com.wf.combinedassault.pack.CommonAssetsManager.vehicleWeaponManager()
                    .getIndex(CombinedAssault.modLocation(w[0]));
            helper.assertTrue(index.isPresent() && index.get().data().getReload() != null, w[0] + " weapon missing");
            ItemStack ammo = new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("wflib", w[1])));
            helper.assertTrue(index.get().data().getReload().isAmmo(ammo), w[0] + " does not load wflib:" + w[1]);
            helper.assertTrue(Munitions.kinetic(ammo) == null, w[1] + " resolved as a kinetic round");
        }
        helper.succeed();
    }

    // ---- Jammer, decoy launcher ------------------------------------------------------------------------------

    private static TargetSnapshot vehicleAt(Vec3 at) {
        return TargetSnapshot.of(1L, ContactClass.VEHICLE, at.x, at.y, at.z, 0.1, 0, 0,
                new Signature(1.0f, 0, 0, 0, 0), 0L, false);
    }

    private static float radarSeen(ServerLevel level, TargetSnapshot target, Vec3 sensorAt) {
        SensorSpec spec = SensorSpec.fireControlRadar(1L, 512);
        TargetSnapshot presented = Interference.apply(Interference.live(level), target);
        return presented.presentedTo(new SensorSnapshot(2L, sensorAt.x, sensorAt.y, sensorAt.z, spec, 0,
                Atmosphere.STANDARD, level.getGameTime())).radarRcs();
    }

    /** Carried jammer: radar returns x1e-4 inside 32 blocks of the carrier, untouched outside; other bands clean. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 60, batch = "jammer_scale")
    public static void aJammerScalesRadarReturnsInsideItsRadiusOnly(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Interference.shutdown();
        Vec3 origin = helper.absoluteVec(new Vec3(1.5, 2.0, 1.5));
        ArmorStand carrier = stand(level, origin);
        ItemStack jammer = new ItemStack(ReconContent.PORTABLE_JAMMER.get());
        jammer.set(ReconContent.ACTIVE.get(), true);
        helper.runAfterDelay(1, () -> {
            jammer.getItem().inventoryTick(jammer, level, carrier, 0, false);
            Vec3 sensor = origin.add(200, 0, 0);
            float inside = radarSeen(level, vehicleAt(origin.add(PortableJammerItem.RADIUS - 2.0, 0, 0)), sensor);
            float outside = radarSeen(level, vehicleAt(origin.add(PortableJammerItem.RADIUS + 2.0, 0, 0)), sensor);
            float thermal = Interference.factorAt(level, Band.THERMAL, origin);
            float radar = Interference.factorAt(level, Band.RADAR, origin);
            carrier.discard();
            helper.assertTrue(Math.abs(inside - PortableJammerItem.FACTOR) < 1.0e-7, "inside radius x" + inside);
            helper.assertTrue(outside == 1.0f, "outside radius x" + outside);
            helper.assertTrue(thermal == 1.0f && radar == PortableJammerItem.FACTOR, "factors thermal " + thermal
                    + ", radar " + radar);
            helper.succeed();
        });
    }

    /** Off or empty: no area. Use toggles; a spent jammer refuses to switch on. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 60, batch = "jammer_off")
    public static void aSwitchedOffOrSpentJammerJamsNothing(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Vec3 origin = helper.absoluteVec(new Vec3(1.5, 2.0, 1.5));
        Interference.shutdown();
        ServerPlayer holder = player(helper, "ca_jammer", origin);
        ItemStack off = new ItemStack(ReconContent.PORTABLE_JAMMER.get());
        off.getItem().inventoryTick(off, level, holder, 0, false);
        helper.runAfterDelay(1, () -> {
            helper.assertTrue(Interference.factorAt(level, Band.RADAR, origin) == 1.0f, "an off jammer jammed");
            ItemStack spent = new ItemStack(ReconContent.PORTABLE_JAMMER.get());
            spent.setDamageValue(spent.getMaxDamage() - 1);
            spent.getItem().use(level, holder, InteractionHand.MAIN_HAND);
            holder.setItemInHand(InteractionHand.MAIN_HAND, spent);
            spent.getItem().use(level, holder, InteractionHand.MAIN_HAND);
            boolean on = PortableJammerItem.on(spent);
            ItemStack fresh = new ItemStack(ReconContent.PORTABLE_JAMMER.get());
            holder.setItemInHand(InteractionHand.MAIN_HAND, fresh);
            fresh.getItem().use(level, holder, InteractionHand.MAIN_HAND);
            boolean freshOn = PortableJammerItem.on(fresh);
            holder.getInventory().clearContent();
            helper.assertFalse(on, "a spent jammer switched on");
            helper.assertTrue(freshOn, "a fresh jammer did not switch on");
            helper.succeed();
        });
    }

    /** Charge drains one durability per 20 ticks while on and cuts out at the last point. */
    @GameTest(templateNamespace = CombinedAssault.MOD_ID, template = TEMPLATE, timeoutTicks = 100, batch = "jammer_drain")
    public static void aJammerDrainsAndCutsOutWhenSpent(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ArmorStand carrier = stand(level, helper.absoluteVec(new Vec3(1.5, 2.0, 1.5)));
        ItemStack jammer = new ItemStack(ReconContent.PORTABLE_JAMMER.get());
        jammer.set(ReconContent.ACTIVE.get(), true);
        jammer.setDamageValue(jammer.getMaxDamage() - 2);
        int[] ticks = {0};
        helper.onEachTick(() -> {
            jammer.getItem().inventoryTick(jammer, level, carrier, 0, false);
            ticks[0]++;
        });
        helper.succeedWhen(() -> {
            helper.assertFalse(PortableJammerItem.on(jammer), "jammer still on after " + ticks[0] + " ticks, damage "
                    + jammer.getDamageValue() + "/" + jammer.getMaxDamage());
            carrier.discard();
        });
    }
}
