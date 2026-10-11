package com.wf.combinedassault.ballistics.addon.gametest;

import com.wf.wflib.util.ForcedChunks;
import com.wf.wflib.aef.ExplosionAEF;
import com.wf.wflib.api.ProjectileStrikeEvent;
import com.wf.wflib.api.Threat;
import com.wf.wflib.api.ThreatKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.all.AllItems;
import com.wf.combinedassault.ballistics.addon.AddonActions;
import com.wf.combinedassault.ballistics.addon.AddonArmorLayer;
import com.wf.combinedassault.ballistics.addon.AddonCommand;
import com.wf.combinedassault.ballistics.addon.AddonFit;
import com.wf.combinedassault.ballistics.addon.AddonKind;
import com.wf.combinedassault.ballistics.addon.AddonMount;
import com.wf.combinedassault.ballistics.addon.AddonMountInfo;
import com.wf.combinedassault.ballistics.addon.AddonMountState;
import com.wf.combinedassault.ballistics.addon.AddonPhysics;
import com.wf.combinedassault.ballistics.ArmorProfile;
import com.wf.combinedassault.ballistics.addon.AddonStrike;
import com.wf.combinedassault.ballistics.addon.AddonStrikes;
import com.wf.combinedassault.ballistics.addon.EraSpec;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.combinedassault.vehicle.hit.VehicleHits;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;

/** ERA and slat: the mechanism in isolation, then through a real hull, a real round, a save and the probe actions. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class AddonArmorGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation M1A2 = CombinedAssault.modLocation("m1a2");
    /** Far from every other arena: rounds fly 12 blocks and blasts reach further. */
    private static final int LANE_ORIGIN = 2600;
    private static final int LANE_SPACING = 64;
    private static final float SIN_60 = Mth.sin((float) Math.toRadians(60));

    private static final Threat HEAT_125 = new Threat(ThreatKind.HEAT, 125, 650);
    private static final Threat RPG = new Threat(ThreatKind.HEAT, 85, 400);
    private static final Threat HYDRA = new Threat(ThreatKind.HEAT, 70, 300);
    private static final Threat TANDEM = new Threat(ThreatKind.TANDEM_HEAT, 152, 900);
    private static final Threat ROD = new Threat(ThreatKind.KINETIC, 120, 700);
    private static final Threat CANNON_30 = new Threat(ThreatKind.KINETIC, 30, 60);
    private static final Threat BULLET = new Threat(ThreatKind.KINETIC, 12.7f, 0);
    private static final Threat RIFLE = new Threat(ThreatKind.KINETIC, 7.62f, 0);

    @GameTest(template = TEMPLATE)
    public static void eraCutsJetsByObliquityAndBarelyTouchesRods(GameTestHelper helper) {
        float cos60 = 0.5f;
        expectEra(helper, "light vs HEAT at 60", EraSpec.LIGHT, HEAT_125, cos60, 0.5f, true, 1 - 0.65f);
        expectEra(helper, "light vs HEAT head-on", EraSpec.LIGHT, HEAT_125, 1.0f, 0.5f, true, 1 - 0.65f * 0.3f);
        expectEra(helper, "light vs HEAT on a brick edge", EraSpec.LIGHT, HEAT_125, cos60, 0.05f, true, 1 - 0.65f * 0.5f);
        expectEra(helper, "light vs rod", EraSpec.LIGHT, ROD, cos60, 0.5f, true, 0.95f);
        expectEra(helper, "heavy vs rod", EraSpec.HEAVY, ROD, cos60, 0.5f, true, 0.65f);
        expectEra(helper, "light vs 30 mm", EraSpec.LIGHT, CANNON_30, cos60, 0.5f, true, 0.95f);
        expectEra(helper, "heavy vs 30 mm", EraSpec.HEAVY, CANNON_30, cos60, 0.5f, false, 1.0f);
        expectEra(helper, "light vs tandem", EraSpec.LIGHT, TANDEM, cos60, 0.5f, true, 1.0f);
        expectEra(helper, "light vs 12.7 mm", EraSpec.LIGHT, BULLET, cos60, 0.5f, false, 1.0f);
        expectEra(helper, "light vs 76 mm HE", EraSpec.LIGHT, new Threat(ThreatKind.HE, 76, 0), cos60, 0.5f, true, 1.0f);
        expectEra(helper, "light vs 40 mm HE", EraSpec.LIGHT, new Threat(ThreatKind.HE, 40, 0), cos60, 0.5f, false, 1.0f);
        helper.succeed();
    }

    private static void expectEra(GameTestHelper helper, String what, EraSpec spec, Threat threat, float cos,
                                  float edge, boolean initiated, float scale) {
        AddonPhysics.EraOutcome outcome = AddonPhysics.era(spec, threat, cos, edge);
        if (outcome.initiated() != initiated || Math.abs(outcome.penetrationScale() - scale) > 1.0e-4f) {
            helper.fail(String.format(Locale.ROOT, "%s: initiated %s scale %.4f, want %s %.4f", what,
                    outcome.initiated(), outcome.penetrationScale(), initiated, scale));
        }
    }

    @GameTest(template = TEMPLATE)
    public static void slatWedgesRpgBodiesAndPassesSmallerRounds(GameTestHelper helper) {
        AddonMountInfo info = new AddonMountInfo();
        info.id = "test";
        info.kind = AddonKind.SLAT;
        info.width = 1.0f;
        info.height = 0.9f;
        AddonMount mount = AddonMount.resolve(0, info, null);
        AddonMountState state = new AddonMountState();
        state.install(mount, AddonFit.SLAT);
        float gap = (mount.barY(4) + mount.barY(5)) / 2;
        float bar = mount.barY(4);
        RandomSource random = RandomSource.create(42);

        int trials = 4000;
        int duds = 0;
        for (int i = 0; i < trials; i++) {
            AddonPhysics.SlatOutcome outcome = AddonPhysics.slat(mount, state, RPG, gap, random);
            if (outcome.verdict() == AddonPhysics.SlatVerdict.DUD) {
                duds++;
            } else if (outcome.verdict() != AddonPhysics.SlatVerdict.BURST || Math.abs(outcome.penetrationScale() - 0.5f) > 1.0e-6f) {
                helper.fail("wedged RPG: " + outcome);
                return;
            }
        }
        float rate = (float) duds / trials;
        if (Math.abs(rate - AddonPhysics.SHORT_CHANCE) > 0.03f) {
            helper.fail("wedged RPGs dudded " + rate + ", want " + AddonPhysics.SHORT_CHANCE);
            return;
        }
        expectSlat(helper, "RPG nose on a bar", mount, state, RPG, bar, random, AddonPhysics.SlatVerdict.BURST);
        expectSlat(helper, "70 mm through a gap", mount, state, HYDRA, gap, random, AddonPhysics.SlatVerdict.MISS);
        expectSlat(helper, "7.62 through a gap", mount, state, RIFLE, gap, random, AddonPhysics.SlatVerdict.MISS);
        expectSlat(helper, "7.62 on a bar", mount, state, RIFLE, bar, random, AddonPhysics.SlatVerdict.STOPPED);
        expectSlat(helper, "rod through a gap", mount, state, ROD, gap, random, AddonPhysics.SlatVerdict.PERFORATED);

        int tandemDuds = 0;
        for (int i = 0; i < trials; i++) {
            if (AddonPhysics.slat(mount, state, TANDEM, gap, random).verdict() == AddonPhysics.SlatVerdict.DUD) {
                tandemDuds++;
            }
        }
        if (Math.abs((float) tandemDuds / trials - AddonPhysics.TANDEM_DUD_CHANCE) > 0.02f) {
            helper.fail("wedged tandems dudded " + (float) tandemDuds / trials);
            return;
        }

        // Two bars shot away: the RPG body fits the doubled gap.
        state.bars[4] = 0;
        state.bars[5] = 0;
        expectSlat(helper, "RPG through a shot-out gap", mount, state, RPG, gap, random, AddonPhysics.SlatVerdict.MISS);
        helper.succeed();
    }

    private static void expectSlat(GameTestHelper helper, String what, AddonMount mount, AddonMountState state,
                                   Threat threat, float y, RandomSource random, AddonPhysics.SlatVerdict want) {
        AddonPhysics.SlatVerdict got = AddonPhysics.slat(mount, state, threat, y, random).verdict();
        if (got != want) {
            helper.fail(what + ": " + got + ", want " + want);
        }
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aJetSpendsTheBrickItHitsAndTheNextOneGoesThrough(GameTestHelper helper) {
        onLane(helper, 0, M1A2, vehicle -> {
            fillAll(vehicle, AddonFit.ERA_LIGHT);
            int skirt = mount(helper, vehicle, "port_skirt_1");
            AddonMount m = vehicle.addonArmor().mounts().get(skirt);
            AddonMountState state = vehicle.addonArmor().state(skirt);
            AddonStrike.Result first = shotAtCell(vehicle, skirt, 5, 30, HEAT_125);
            float want = 1 - 0.65f * Math.max(0.3f, Mth.sin((float) Math.toRadians(30)) / SIN_60);
            if (first.verdict() != AddonStrike.Verdict.BURST || first.hull() == null
                    || Math.abs(first.penetrationScale() - want) > 0.01f) {
                helper.fail("HEAT onto a live brick: " + first + ", want BURST reaching the hull at " + want);
                return;
            }
            if (state.isLive(5) || state.liveCells() != m.cells() - 1) {
                helper.fail("the brick that fired is still live; " + state.liveCells() + " of " + m.cells());
                return;
            }
            AddonStrike.Result second = shotAtCell(vehicle, skirt, 5, 30, HEAT_125);
            if (second.verdict() != AddonStrike.Verdict.THROUGH || second.penetrationScale() != 1.0f) {
                helper.fail("HEAT through the spent brick's hole: " + second + ", want THROUGH at full penetration");
                return;
            }
            AddonStrike.Result tandem = shotAtCell(vehicle, skirt, 4, 30, TANDEM);
            if (tandem.verdict() != AddonStrike.Verdict.BURST || tandem.penetrationScale() != 1.0f || state.isLive(4)) {
                helper.fail("tandem: " + tandem + ", live " + state.isLive(4) + "; precursor should spend it untouched");
                return;
            }
            AddonStrike.Result bullet = shotAtCell(vehicle, skirt, 3, 30, BULLET);
            if (bullet.verdict() != AddonStrike.Verdict.STOPPED || !state.isLive(3)) {
                helper.fail("12.7 mm on a brick: " + bullet + ", live " + state.isLive(3));
                return;
            }
            helper.succeed();
        });
    }

    /** Through the panel frame, at a cell's centre, {@code obliquity} degrees off the normal about panel up. */
    private static AddonStrike.Result shotAtCell(AbstractVehicle vehicle, int mount, int cell, float obliquity,
                                                 Threat threat) {
        AddonArmorLayer layer = vehicle.addonArmor();
        AddonMount m = layer.mounts().get(mount);
        AddonArmorLayer.Pose pose = layer.pose(vehicle, mount);
        Vec3 local = m.cellCentre(cell);
        Vec3 aim = pose.toWorld((float) local.x, (float) local.y, layer.state(mount).fit.era.thickness());
        Vector3f out = new Quaternionf(pose.rotation()).transform(new Quaternionf()
                .rotationY((float) Math.toRadians(obliquity)).transform(new Vector3f(0, 0, 1)));
        Vec3 direction = new Vec3(out.x, out.y, out.z);
        return AddonStrike.resolve(vehicle, threat, aim.add(direction.scale(3)), direction.scale(-15));
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void slatStopsABulletOnABarAndPassesOneThroughAGap(GameTestHelper helper) {
        onLane(helper, 1, M1A2, vehicle -> {
            fillAll(vehicle, AddonFit.SLAT);
            int rear = mount(helper, vehicle, "rear_slat_1");
            AddonMount m = vehicle.addonArmor().mounts().get(rear);
            AddonMountState state = vehicle.addonArmor().state(rear);
            ResourceLocation gun = ResourceLocation.fromNamespaceAndPath("wflib", "12.7x99/ball");

            float before = vehicle.getHealth();
            fire(helper, vehicle, rear, gun, m.barY(4));
            if (vehicle.getHealth() != before) {
                helper.fail("a bullet on bar 4 hurt the hull: " + before + " -> " + vehicle.getHealth());
                return;
            }
            if (state.bars[4] >= 1.0f) {
                helper.fail("the bar that stopped a bullet is unmarked");
                return;
            }
            fire(helper, vehicle, rear, gun, (m.barY(6) + m.barY(7)) / 2);
            if (vehicle.getHealth() >= before) {
                helper.fail("a bullet through the gap between bars 6 and 7 never reached the hull");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aWfLibHeatShellSetsOffTheBrickItHits(GameTestHelper helper) {
        onLane(helper, 2, M1A2, vehicle -> {
            fillAll(vehicle, AddonFit.ERA_HEAVY);
            int skirt = mount(helper, vehicle, "starboard_skirt_1");
            AddonMountState state = vehicle.addonArmor().state(skirt);
            int cells = vehicle.addonArmor().mounts().get(skirt).cells();
            Vec3 at = vehicle.addonArmor().mounts().get(skirt).cellCentre(5);
            AddonCommand.Shot shell = AddonCommand.launch(vehicle, skirt,
                    ResourceLocation.fromNamespaceAndPath("wflib", "heat"), 0, (float) at.x, (float) at.y);
            if (shell == null) {
                helper.fail("wflib:heat is not a kinetic preset");
                return;
            }
            shell.tickUntilGone(helper.getLevel());
            if (state.liveCells() >= cells) {
                helper.fail("a WFLib HEAT shell on the skirt left all " + cells + " bricks live");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void spentBricksAndBentBarsSurviveSaveAndLoad(GameTestHelper helper) {
        onLane(helper, 3, M1A2, vehicle -> {
            fillAll(vehicle, AddonFit.ERA_LIGHT);
            fillAll(vehicle, AddonFit.SLAT);
            int skirt = mount(helper, vehicle, "port_skirt_1");
            int rear = mount(helper, vehicle, "rear_slat_1");
            vehicle.addonArmor().state(skirt).live = 0b101010L;
            vehicle.addonArmor().state(rear).bars[0] = 0.5f;
            vehicle.addonArmor().state(rear).bars[3] = 0.0f;
            CompoundTag saved = vehicle.saveWithoutId(new CompoundTag());
            AbstractVehicle copy = construct(helper, vehicle.level(), vehicle.position(), M1A2);
            if (copy == null) {
                return;
            }
            copy.load(saved);
            AddonMountState skirtCopy = copy.addonArmor().state(skirt);
            AddonMountState rearCopy = copy.addonArmor().state(rear);
            if (skirtCopy.fit != AddonFit.ERA_LIGHT || skirtCopy.live != 0b101010L) {
                helper.fail("skirt reloaded as " + skirtCopy.fit + " " + Long.toBinaryString(skirtCopy.live));
                return;
            }
            if (rearCopy.fit != AddonFit.SLAT || Math.abs(rearCopy.bars[0] - 0.5f) > 1.0f / 255 || rearCopy.bars[3] != 0) {
                helper.fail("rear slat reloaded as " + rearCopy.fit + " bars " + java.util.Arrays.toString(rearCopy.bars));
                return;
            }
            copy.discard();
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void probeActionsFitRefillRemoveAndWeld(GameTestHelper helper) {
        onLane(helper, 4, M1A2, vehicle -> {
            // makeMockServerPlayerInLevel joins the player list; Create Aeronautics then sends it an unnegotiated payload.
            ServerPlayer player = FakePlayerFactory.getMinecraft(helper.getLevel());
            player.setGameMode(GameType.SURVIVAL);
            player.getInventory().clearContent();
            int skirt = mount(helper, vehicle, "port_skirt_1");
            int side = mount(helper, vehicle, "port_rear_slat");
            AddonArmorLayer layer = vehicle.addonArmor();
            standBy(player, vehicle, skirt);
            // Held item first: setting the hand afterwards overwrites slot 0.
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
            player.getInventory().add(new ItemStack(AllItems.ERA_BRICK.get(), 5));
            if (AddonActions.install(player, vehicle, skirt, AddonFit.ERA_LIGHT)) {
                helper.fail("fitted six bricks from five");
                return;
            }
            player.getInventory().add(new ItemStack(AllItems.ERA_BRICK.get(), 1));
            if (!AddonActions.install(player, vehicle, skirt, AddonFit.ERA_LIGHT)
                    || player.getInventory().countItem(AllItems.ERA_BRICK.get()) != 0) {
                helper.fail("install: " + layer.state(skirt).fit + ", bricks left "
                        + player.getInventory().countItem(AllItems.ERA_BRICK.get()));
                return;
            }
            layer.state(skirt).live &= ~0b111L;
            player.getInventory().add(new ItemStack(AllItems.ERA_BRICK.get(), 2));
            if (!AddonActions.replace(player, vehicle, skirt) || layer.state(skirt).liveCells() != 5) {
                helper.fail("partial refill left " + layer.state(skirt).liveCells() + " live");
                return;
            }
            if (!AddonActions.remove(player, vehicle, skirt) || !layer.state(skirt).isEmpty()
                    || player.getInventory().countItem(AllItems.ERA_BRICK.get()) != 5) {
                helper.fail("remove returned " + player.getInventory().countItem(AllItems.ERA_BRICK.get()) + ", want the 5 live");
                return;
            }

            standBy(player, vehicle, side);
            int cost = layer.mounts().get(side).slatCost();
            player.getInventory().add(new ItemStack(AllItems.SLAT_PANEL.get(), cost));
            if (!AddonActions.install(player, vehicle, side, AddonFit.SLAT)) {
                helper.fail("slat install with " + cost + " panels refused");
                return;
            }
            AddonMountState slat = layer.state(side);
            slat.bars[2] = 0.4f;
            slat.bars[5] = 0.0f;
            if (AddonActions.repair(player, vehicle, side)) {
                helper.fail("welded without a welder");
                return;
            }
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(AllItems.REPAIR_TOOL.get()));
            if (!AddonActions.repair(player, vehicle, side) || slat.bars[2] != 1.0f || slat.bars[5] != 0.0f) {
                helper.fail("weld with no stock: bent " + slat.bars[2] + " broken " + slat.bars[5]);
                return;
            }
            player.getInventory().add(new ItemStack(Items.IRON_INGOT));
            if (!AddonActions.repair(player, vehicle, side) || slat.bars[5] != 1.0f
                    || player.getInventory().countItem(Items.IRON_INGOT) != 0) {
                helper.fail("weld with one ingot: broken bar " + slat.bars[5] + ", ingots "
                        + player.getInventory().countItem(Items.IRON_INGOT));
                return;
            }
            player.getInventory().clearContent();
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aBlastBendsNearbySlatAndLeavesEraAlone(GameTestHelper helper) {
        onLane(helper, 5, M1A2, vehicle -> {
            fillAll(vehicle, AddonFit.ERA_LIGHT);
            fillAll(vehicle, AddonFit.SLAT);
            int rear = mount(helper, vehicle, "rear_slat_1");
            AddonArmorLayer.Pose pose = vehicle.addonArmor().pose(vehicle, rear);
            Vec3 at = pose.toWorld(0, 0, 1.0f);
            vehicle.level().explode(null, at.x, at.y, at.z, 3.0f, Level.ExplosionInteraction.NONE);
            AddonMountState state = vehicle.addonArmor().state(rear);
            if (state.bentBars() + (state.bars.length - state.intactBars()) == 0) {
                helper.fail("a 3-block blast a block off the rear slat left every bar straight");
                return;
            }
            for (int i = 0; i < vehicle.addonArmor().mounts().size(); i++) {
                AddonMountState era = vehicle.addonArmor().state(i);
                if (era.fit.kind == AddonKind.ERA && era.liveCells() != vehicle.addonArmor().mounts().get(i).cells()) {
                    helper.fail("blast set off ERA on " + vehicle.addonArmor().mounts().get(i).info.id);
                    return;
                }
            }
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void theHullShadowsTheFarCageAndSlatMutesTheBlast(GameTestHelper helper) {
        onLane(helper, 6, M1A2, vehicle -> {
            int port = mount(helper, vehicle, "port_rear_slat");
            int starboard = mount(helper, vehicle, "starboard_rear_slat");
            Vec3 at = vehicle.addonArmor().pose(vehicle, port).toWorld(0, 0, 1.0f);
            fillAll(vehicle, AddonFit.ERA_LIGHT);
            float bare = AddonStrike.blast(vehicle, at, 3.0f, null);
            fillAll(vehicle, AddonFit.SLAT);
            float caged = AddonStrike.blast(vehicle, at, 3.0f, null);
            AddonMountState near = vehicle.addonArmor().state(port);
            AddonMountState far = vehicle.addonArmor().state(starboard);
            if (near.bentBars() + near.bars.length - near.intactBars() == 0) {
                helper.fail("a 3-block blast a block off the port cage left it straight");
                return;
            }
            if (far.bentBars() + far.bars.length - far.intactBars() != 0) {
                helper.fail("the starboard cage bent through the hull: " + java.util.Arrays.toString(far.bars));
                return;
            }
            if (!(caged < bare * 0.9f)) {
                helper.fail("slat left the hull " + caged + " of the blast, bare " + bare);
                return;
            }
            long best = Long.MAX_VALUE;
            for (int i = 0; i < 20; i++) {
                long start = System.nanoTime();
                AddonStrike.blast(vehicle, at, 3.0f, null);
                best = Math.min(best, System.nanoTime() - start);
            }
            CombinedAssault.LOGGER.info("[addon] ray blast on m1a2: best {} us of 20, hull exposure {} bare / {} caged",
                    best / 1000, bare, caged);
            helper.succeed();
        });
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void aShapedChargeBendsOnlyTheCageItFaces(GameTestHelper helper) {
        onLane(helper, 7, M1A2, vehicle -> {
            fillAll(vehicle, AddonFit.SLAT);
            int rear = mount(helper, vehicle, "rear_slat_1");
            AddonArmorLayer.Pose pose = vehicle.addonArmor().pose(vehicle, rear);
            Vec3 at = pose.toWorld(0, 0, 1.0f);
            Vec3 toCage = pose.toWorld(0, 0, 0).subtract(at).normalize();
            AddonMountState state = vehicle.addonArmor().state(rear);
            new ExplosionAEF(vehicle.level(), at.x, at.y, at.z, 3.0f).makeShapedCharge(toCage.scale(-1))
                    .setBlockAllocator(null).explode();
            if (state.bentBars() + state.bars.length - state.intactBars() != 0) {
                helper.fail("a charge pointed away bent the cage behind it");
                return;
            }
            new ExplosionAEF(vehicle.level(), at.x, at.y, at.z, 3.0f).makeShapedCharge(toCage)
                    .setBlockAllocator(null).explode();
            if (state.bentBars() + state.bars.length - state.intactBars() == 0) {
                helper.fail("a charge pointed at the cage left it straight");
                return;
            }
            helper.succeed();
        });
    }

    private static void fire(GameTestHelper helper, AbstractVehicle vehicle, int mount, ResourceLocation weapon, float y) {
        AddonCommand.Shot round = AddonCommand.launch(vehicle, mount, weapon, 0, 0.2f, y);
        if (round == null) {
            helper.fail(weapon + " is not a WFLib kinetic preset");
            return;
        }
        round.tickUntilGone(helper.getLevel());
    }

    private static void standBy(ServerPlayer player, AbstractVehicle vehicle, int mount) {
        AddonArmorLayer.Pose pose = vehicle.addonArmor().pose(vehicle, mount);
        Vec3 at = pose.toWorld(0, 0, 1.5f);
        player.moveTo(at.x, at.y - player.getEyeHeight(), at.z);
    }

    private static void fillAll(AbstractVehicle vehicle, AddonFit fit) {
        AddonArmorLayer layer = vehicle.addonArmor();
        for (int i = 0; i < layer.mounts().size(); i++) {
            if (layer.mounts().get(i).info.kind == fit.kind) {
                layer.state(i).install(layer.mounts().get(i), fit);
            }
        }
        layer.changed();
    }

    /**
     * Hull armour 50 mm every face, ricochet past 70 deg incidence, vs 12.7 mm (20 mm pen) posted as a WFLib strike
     * onto a side face: 85 deg => RICOCHET leaving the face, nothing put for {@code hurt}; head-on, or a striker that
     * cannot ricochet => none.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void hullGlancesKineticRoundsPastTheRicochetAngle(GameTestHelper helper) {
        onLane(helper, 8, M1A2, vehicle -> {
            ArmorProfile profile = new ArmorProfile();
            profile.front = profile.side = profile.rear = profile.top = profile.bottom = 50;
            vehicle.armorProfile = profile;
            Vec3 centre = vehicle.getBoundingBox().getCenter();
            Vec3[] face = surface(vehicle, centre.add(20, 0, 0), centre);
            Vec3 n = face[1];
            Vec3 t = n.cross(new Vec3(0, 1, 0)).normalize();
            double a = Math.toRadians(5);
            Vec3 graze = t.scale(Math.cos(a)).subtract(n.scale(Math.sin(a))).scale(40);
            Vec3[] met = surface(vehicle, face[0].subtract(graze), face[0].add(graze.normalize().scale(0.1)));
            double incidence = Math.toDegrees(Math.acos(-graze.normalize().dot(met[1])));
            helper.assertTrue(incidence > 70, "graze met a face at " + incidence + " deg");
            ProjectileStrikeEvent glanced = strike(vehicle, 1, face[0], graze, true);
            helper.assertTrue(glanced.outcome() == ProjectileStrikeEvent.Outcome.RICOCHET, "graze " + glanced.outcome());
            helper.assertTrue(glanced.ricochet().dot(met[1]) > 0, "into the plate: " + glanced.ricochet());
            helper.assertTrue(AddonStrikes.get(1L) == null, "glance left a pending hull result");
            helper.assertTrue(strike(vehicle, 2, face[0], n.scale(-40), true).outcome()
                    != ProjectileStrikeEvent.Outcome.RICOCHET, "head-on ricocheted");
            helper.assertTrue(strike(vehicle, 3, face[0], graze, false).outcome()
                    != ProjectileStrikeEvent.Outcome.RICOCHET, "striker without ricochet ricocheted");
            helper.succeed();
        });
    }

    private static ProjectileStrikeEvent strike(AbstractVehicle vehicle, long key, Vec3 at, Vec3 v, boolean can) {
        ProjectileStrikeEvent e = new ProjectileStrikeEvent(null, key, vehicle, at, v,
                new Threat(ThreatKind.SMALL_ARMS, 12.7f, 20f), null);
        return NeoForge.EVENT_BUS.post(can ? e.allowRicochet() : e);
    }

    /** {@code {point, outward normal}} where {@code from -> to} meets the hull, as {@code ArmorResolver} looks. */
    private static Vec3[] surface(AbstractVehicle vehicle, Vec3 from, Vec3 to) {
        if (vehicle.hits() != null) {
            VehicleHits.Hit h = vehicle.hits().surface(from, to);
            return new Vec3[]{h.point(), new Vec3(h.normal())};
        }
        VectorUtil.HitOBB h = VectorUtil.hitOBB(vehicle.getOBBs(), from, to);
        return new Vec3[]{h.hitPos(), new Vec3(VectorUtil.faceNormal(h.obb(), h.hitPos()))};
    }

    private static int mount(GameTestHelper helper, AbstractVehicle vehicle, String id) {
        for (int i = 0; i < vehicle.addonArmor().mounts().size(); i++) {
            if (vehicle.addonArmor().mounts().get(i).info.id.equals(id)) {
                return i;
            }
        }
        throw new IllegalStateException(vehicle.getVehicleId() + " has no mount " + id);
    }

    /** Lane chunks forced; once entity-ticking, {@code body} runs with a fresh vehicle posed in place. */
    private static void onLane(GameTestHelper helper, int lane, ResourceLocation id, Consumer<AbstractVehicle> body) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(BlockPos.ZERO).offset(0, 8, LANE_ORIGIN + lane * LANE_SPACING);
        force(level, at, true);
        AbstractVehicle[] spawned = new AbstractVehicle[1];
        helper.startSequence()
                // Explosions only see entities in entity-ticking sections; a far arena's chunks can take longer
                // than a fixed idle to generate under a full batch.
                .thenWaitUntil(() -> {
                    if (!level.isPositionEntityTicking(at)) {
                        throw new GameTestAssertException("lane not entity-ticking yet");
                    }
                })
                .thenExecute(() -> {
                    spawned[0] = construct(helper, level, Vec3.atBottomCenterOf(at), id);
                    if (spawned[0] == null) {
                        return;
                    }
                    try {
                        body.accept(spawned[0]);
                    } finally {
                        spawned[0].discard();
                        force(level, at, false);
                    }
                });
    }

    @Nullable
    private static AbstractVehicle construct(GameTestHelper helper, Level level, Vec3 at, ResourceLocation id) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            helper.fail(id + " is not in the roster");
            return null;
        }
        AbstractVehicle vehicle = data.get().construct(level, at, 0, 0);
        vehicle.setDisplayId(id);
        if (!level.addFreshEntity(vehicle)) {
            helper.fail(id + " could not be added");
            return null;
        }
        vehicle.updateOBBs();
        if (vehicle.addonArmor().isEmpty()) {
            helper.fail(id + " loaded with no addon_armor mounts");
            return null;
        }
        return vehicle;
    }

    private static void force(ServerLevel level, BlockPos at, boolean forced) {
        int cx = SectionPos.blockToSectionCoord(at.getX());
        int cz = SectionPos.blockToSectionCoord(at.getZ());
        for (int ox = -1; ox <= 1; ox++) {
            for (int oz = -1; oz <= 1; oz++) {
                ForcedChunks.set(level, cx + ox, cz + oz, forced);
            }
        }
    }

}
