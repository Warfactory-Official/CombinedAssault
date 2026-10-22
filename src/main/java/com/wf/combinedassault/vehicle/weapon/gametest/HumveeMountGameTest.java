package com.wf.combinedassault.vehicle.weapon.gametest;

import com.mojang.math.Axis;
import com.wf.wflib.kinetic.KineticPreset;
import com.wf.wflib.round.Rounds;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.custom.CommonAssetsManager;
import com.wf.combinedassault.custom.vehicle.BaseVehicleData;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.hit.VehicleHits;
import com.wf.combinedassault.item.HardwareItem;
import com.wf.combinedassault.vehicle.hardware.Hardpoints;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import com.wf.combinedassault.vehicle.pojo.AimContext;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;
import com.wf.combinedassault.vehicle.weapon.VehicleCannon;
import com.wf.combinedassault.vehicle.weapon.VehicleGrenade;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Humvee roof_mount: hull + shield mount; gun = hardware kit (Mk 19 / M2) on the {@code pintle} hardpoint. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class HumveeMountGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation M430 = ResourceLocation.fromNamespaceAndPath("wflib", "40x53/he");
    private static final ResourceLocation BMG = ResourceLocation.fromNamespaceAndPath("wflib", "12.7x99/ball");
    private static final String MK19_HUMVEE = "mk19";
    private static final String M2_HUMVEE = "m2";
    private static final ResourceLocation HUMVEE = CombinedAssault.modLocation("humvee");
    private static final ResourceLocation MK19 = CombinedAssault.modLocation("mk19");
    private static final ResourceLocation M2 = CombinedAssault.modLocation("gun_12.7mm");
    /** Vehicle frame. Mk 19: stage_emb4_humvee T(MUZZLE). M2: m2.gltf socket_bolt_0 tip on the roof mount. */
    private static final Vec3 MK19_MUZZLE = new Vec3(0, 2.4552, 1.6083);
    private static final Vec3 M2_MUZZLE = new Vec3(0, 2.3904, 1.84415);
    /** humvee.gltf roof_mount: the yaw axis. */
    private static final Vec3 YAW_AXIS = new Vec3(0, 0, -0.0162);
    private static final double EPS = 1e-3;

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void eachGunMountPutsTheMuzzleAtItsBarrelTip(GameTestHelper helper) {
        for (var c : List.of(new Object[]{MK19_HUMVEE, MK19_MUZZLE}, new Object[]{M2_HUMVEE, M2_MUZZLE})) {
            WeaponUnit unit = roofMount(helper, (String) c[0]);
            if (unit == null) {
                return;
            }
            Vec3 muzzle = unit.aimContext().from.subtract(unit.getVehicle().position());
            unit.getVehicle().discard();
            if (muzzle.distanceTo((Vec3) c[1]) > EPS) {
                helper.fail(c[0] + " muzzle " + muzzle + " != barrel tip " + c[1]);
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void theMuzzleTraversesWithTheRing(GameTestHelper helper) {
        for (var c : List.of(new Object[]{MK19_HUMVEE, MK19_MUZZLE}, new Object[]{M2_HUMVEE, M2_MUZZLE})) {
            WeaponUnit unit = roofMount(helper, (String) c[0]);
            if (unit == null) {
                return;
            }
            unit.setYRot(90);
            unit.updateRot();
            Vec3 muzzle = unit.aimContext().from.subtract(unit.getVehicle().position());
            unit.getVehicle().discard();
            Vector3f arm = ((Vec3) c[1]).subtract(YAW_AXIS).toVector3f();
            Axis.YN.rotationDegrees(90).transform(arm);
            Vec3 expected = YAW_AXIS.add(arm.x, arm.y, arm.z);
            if (muzzle.distanceTo(expected) > EPS) {
                helper.fail(c[0] + " muzzle at yaw 90 " + muzzle + " != " + expected);
                return;
            }
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void bothGunsShareTheShieldStation(GameTestHelper helper) {
        WeaponUnit mk19 = roofMount(helper, MK19_HUMVEE);
        WeaponUnit m2 = mk19 == null ? null : roofMount(helper, M2_HUMVEE);
        if (m2 == null) {
            return;
        }
        String seat = mk19.getData().getSeatOffset() + "/" + m2.getData().getSeatOffset();
        String view = mk19.getData().getOperatorViewOffset() + "/" + m2.getData().getOperatorViewOffset();
        String exit = mk19.getData().getDismountOffset() + "/" + m2.getData().getDismountOffset();
        boolean same = mk19.getData().getSeatOffset().equals(m2.getData().getSeatOffset())
                && mk19.getData().getOperatorViewOffset().equals(m2.getData().getOperatorViewOffset())
                && mk19.getData().getDismountOffset().equals(m2.getData().getDismountOffset())
                && mk19.getData().getSeatOffset().y > 2;
        mk19.getVehicle().discard();
        m2.getVehicle().discard();
        if (!same) {
            helper.fail("humvee_turret.gltf station differs or unread: seat " + seat + ", view " + view + ", exit " + exit);
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void theMk19FiresAWflibShellFromItsMuzzle(GameTestHelper helper) {
        WeaponUnit unit = roofMount(helper, MK19_HUMVEE);
        if (unit == null) {
            return;
        }
        AbstractVehicleWeapon<?> weapon = unit.getCurrentWeapon().orElse(null);
        if (!(weapon instanceof VehicleGrenade grenade)) {
            helper.fail("roof mount weapon " + weapon + " is not the Mk 19 grenade launcher");
            return;
        }
        grenade.setRemainAmmo(4);
        grenade.setLoadedRound(com.wf.wflib.ammo.AmmoItems.stack(M430, 1));
        var preset = fired(helper, unit, grenade.shoot(new ArrayList<>(List.of(unit.aimContext())), null));
        if (preset == null) {
            return;
        }
        if (!preset.id().equals(M430) || preset.blastSize() != 2.0f) {
            helper.fail("shell flies preset " + preset.id() + ", blast " + preset.blastSize() + "; want " + M430 + ", 2");
            return;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void theM2FiresA127RoundFromItsMuzzle(GameTestHelper helper) {
        WeaponUnit unit = roofMount(helper, M2_HUMVEE);
        if (unit == null) {
            return;
        }
        AbstractVehicleWeapon<?> weapon = unit.getCurrentWeapon().orElse(null);
        if (!(weapon instanceof VehicleCannon gun)) {
            helper.fail("roof mount weapon " + weapon + " is not the 12.7mm gun");
            return;
        }
        gun.setRemainAmmo(4);
        gun.setLoadedRound(com.wf.wflib.ammo.AmmoItems.stack(BMG, 1));
        var preset = fired(helper, unit, gun.shoot(new ArrayList<>(List.of(unit.aimContext())), null));
        if (preset == null) {
            return;
        }
        if (!preset.id().equals(BMG)) {
            helper.fail("round flies preset " + preset.id() + " != " + BMG);
            return;
        }
        helper.succeed();
    }

    /** Gun follows the kit: seat switching stays inside it, tool swaps refused, name follows the gun. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void theKitPicksTheGunNotTheSeat(GameTestHelper helper) {
        WeaponUnit unit = roofMount(helper, MK19_HUMVEE);
        if (unit == null) {
            return;
        }
        AbstractVehicle vehicle = unit.getVehicle();
        unit.switchWeapon(false, true, false);
        ResourceLocation fromSeat = unit.currentWeaponId();
        fit(vehicle, M2_HUMVEE);
        ResourceLocation fromKit = unit.currentWeaponId();
        Object name = vehicle.getDisplayName().getContents() instanceof TranslatableContents t ? t.getKey()
                : vehicle.getDisplayName().getString();
        boolean hardpoint = unit.isHardpoint();
        vehicle.discard();
        if (!MK19.equals(fromSeat) || !M2.equals(fromKit) || !hardpoint
                || !"entity.combinedassault.humvee_m2".equals(name)) {
            helper.fail("seat switch -> " + fromSeat + " (want " + MK19 + "), kit -> " + fromKit + " (want " + M2
                    + "), hardpoint " + hardpoint + ", name " + name + " (want the M2 Humvee's)");
            return;
        }
        helper.succeed();
    }

    /**
     * Ad hoc, no modding tool: unarmed Humvee + carried M2 kit item => fit puts the M2 on, item consumed; take off
     * returns it and the gun stops firing. Refused without the item, with a gun on.
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aCarriedWeaponItemFitsAdHoc(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, HUMVEE);
        if (vehicle == null) {
            return;
        }
        WeaponUnit unit = (WeaponUnit) vehicle.getPartUnit("roof_mount").orElseThrow();
        int m2 = kit(unit, M2_HUMVEE);
        int mk19 = kit(unit, MK19_HUMVEE);
        boolean unarmed = unit.kit() == null && unit.currentWeaponId() == null;
        ServerPlayer player = FakePlayerFactory.getMinecraft(helper.getLevel());
        player.setGameMode(GameType.SURVIVAL);
        Vec3 near = unit.worldPivotPosition();
        player.moveTo(near.x + 1, near.y - player.getEyeHeight(), near.z);
        player.getInventory().clearContent();
        boolean refusedEmpty = Hardpoints.fitRefusal(player, unit, m2) != null;
        player.getInventory().add(HardwareItem.of(CombinedAssault.modLocation(M2_HUMVEE)));
        player.getInventory().add(HardwareItem.of(CombinedAssault.modLocation(MK19_HUMVEE)));
        boolean allowed = Hardpoints.fitRefusal(player, unit, m2) == null;
        Hardpoints.fit(player, unit, m2);
        boolean consumed = count(player, M2_HUMVEE) == 0;
        ResourceLocation gun = unit.currentWeaponId();
        boolean secondRefused = Hardpoints.fitRefusal(player, unit, mk19) != null;
        boolean offAllowed = Hardpoints.takeOffRefusal(player, unit) == null;
        Hardpoints.takeOff(player, unit);
        boolean back = count(player, M2_HUMVEE) == 1;
        boolean silentAgain = unit.currentWeaponId() == null;
        player.getInventory().clearContent();
        vehicle.discard();
        helper.assertTrue(unarmed, "spawned armed: " + unit.currentWeaponId());
        helper.assertTrue(refusedEmpty && allowed && consumed && M2.equals(gun), "refused empty " + refusedEmpty
                + ", allowed " + allowed + ", consumed " + consumed + ", gun " + gun);
        helper.assertTrue(secondRefused, "Mk 19 fit allowed over a fitted M2");
        helper.assertTrue(offAllowed && back && silentAgain, "take off allowed " + offAllowed + ", item back " + back
                + ", silent " + silentAgain);
        helper.assertTrue(unit.hardpoint().kits().get(m2).installTicks() == 120, "install_ticks "
                + unit.hardpoint().kits().get(m2).installTicks());
        helper.succeed();
    }

    /** {@code /data merge} reloads a live vehicle: one part set after, the picked part is the listed one. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void aReloadedVehicleKeepsOnePartSet(GameTestHelper helper) {
        WeaponUnit before = roofMount(helper, MK19_HUMVEE);
        if (before == null) {
            return;
        }
        AbstractVehicle vehicle = before.getVehicle();
        int parts = vehicle.getPartUnits().size();
        vehicle.load(vehicle.saveWithoutId(new CompoundTag()));
        fit(vehicle, M2_HUMVEE);
        long listed = vehicle.getPartUnits().stream().filter(p -> p.getId().equals("roof_mount")).count();
        Object name = vehicle.getDisplayName().getContents() instanceof TranslatableContents t ? t.getKey() : null;
        int after = vehicle.getPartUnits().size();
        vehicle.discard();
        helper.assertTrue(after == parts && listed == 1, "parts " + parts + " -> " + after + ", roof_mount listed " + listed);
        helper.assertTrue("entity.combinedassault.humvee_m2".equals(name), "name after reload + swap " + name);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void theSwappedGunBringsItsBoltAndHitMesh(GameTestHelper helper) {
        WeaponUnit unit = roofMount(helper, MK19_HUMVEE);
        if (unit == null) {
            return;
        }
        AbstractVehicle vehicle = unit.getVehicle();
        // Vertical ray through the M2 barrel ahead of the Mk 19 muzzle: only the M2 mount is there.
        double z = (MK19_MUZZLE.z + M2_MUZZLE.z) / 2;
        Vec3 top = vehicle.position().add(0, 3.5, z);
        Vec3 bottom = vehicle.position().add(0, 2.0, z);
        VehicleHits hits = vehicle.hits();
        boolean mk19Hit = hits.trace(top, bottom, i -> hits.part(i) == unit && hits.live(i)) != null;
        Vec3 mk19Muzzle = unit.aimContext().from.subtract(vehicle.position());
        fit(vehicle, M2_HUMVEE);
        boolean m2Hit = hits.trace(top, bottom, i -> hits.part(i) == unit && hits.live(i)) != null;
        Vec3 m2Muzzle = unit.aimContext().from.subtract(vehicle.position());
        CompoundTag saved = vehicle.saveWithoutId(new CompoundTag());
        vehicle.discard();
        AbstractVehicle reloaded = spawn(helper, HUMVEE);
        if (reloaded == null) {
            return;
        }
        reloaded.load(saved);
        ResourceLocation kept = ((WeaponUnit) reloaded.getPartUnit("roof_mount").orElseThrow()).currentWeaponId();
        reloaded.discard();
        if (mk19Hit || !m2Hit || mk19Muzzle.distanceTo(MK19_MUZZLE) > EPS || m2Muzzle.distanceTo(M2_MUZZLE) > EPS
                || !M2.equals(kept)) {
            helper.fail("ray at z " + z + ": Mk 19 mounted hit " + mk19Hit + ", M2 mounted hit " + m2Hit + "; muzzle "
                    + mk19Muzzle + " -> " + m2Muzzle + "; reloaded weapon " + kept);
            return;
        }
        helper.succeed();
    }

    /** Spawns unarmed: the roof ring is there, no gun answers, nothing fires. */
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    public static void theHumveeSpawnsUnarmed(GameTestHelper helper) {
        AbstractVehicle vehicle = spawn(helper, HUMVEE);
        if (vehicle == null) {
            return;
        }
        WeaponUnit unit = (WeaponUnit) vehicle.getPartUnit("roof_mount").orElseThrow();
        ResourceLocation kit = unit.kit();
        ResourceLocation gun = unit.currentWeaponId();
        vehicle.discard();
        helper.assertTrue(kit == null && gun == null, "spawned " + kit + " with gun " + gun);
        helper.succeed();
    }

    /** Preset of the one round {@code shot} launched at the muzzle (destroyed); null after failing. Vehicle discarded. */
    @Nullable
    private static KineticPreset fired(GameTestHelper helper, WeaponUnit unit, boolean shot) {
        Vec3 muzzle = unit.aimContext().from;
        unit.getVehicle().discard();
        long[] rounds = Rounds.within(helper.getLevel(), new AABB(muzzle, muzzle).inflate(8));
        if (!shot || rounds.length != 1) {
            helper.fail("shot " + shot + ", " + rounds.length + " WFLib rounds after one shot");
            return null;
        }
        Vec3 at = Rounds.position(helper.getLevel(), rounds[0]);
        KineticPreset preset = Rounds.preset(helper.getLevel(), rounds[0]);
        Rounds.destroy(helper.getLevel(), rounds[0]);
        if (at.distanceTo(muzzle) > EPS) {
            helper.fail("round launched at " + at + ", muzzle " + muzzle);
            return null;
        }
        return preset;
    }

    /** Humvee with kit {@code kit} fitted. */
    @Nullable
    private static WeaponUnit roofMount(GameTestHelper helper, String kit) {
        AbstractVehicle vehicle = spawn(helper, HUMVEE);
        if (vehicle == null) {
            return null;
        }
        if (!(vehicle.getPartUnit("roof_mount").orElse(null) instanceof WeaponUnit unit)) {
            helper.fail("humvee has no roof_mount weapon part");
            return null;
        }
        fit(vehicle, kit);
        return unit;
    }

    private static int kit(WeaponUnit unit, String kit) {
        int i = unit.hardpoint().indexOf(CombinedAssault.modLocation(kit));
        if (i < 0) {
            throw new IllegalArgumentException("no kit " + kit);
        }
        return i;
    }

    private static void fit(AbstractVehicle vehicle, String kit) {
        WeaponUnit unit = (WeaponUnit) vehicle.getPartUnit("roof_mount").orElseThrow();
        unit.fitKit(-1);
        unit.fitKit(kit(unit, kit));
    }

    private static int count(ServerPlayer player, String kit) {
        int n = 0;
        for (ItemStack stack : player.getInventory().items) {
            n += Hardpoints.matcher(CombinedAssault.modLocation(kit)).test(stack) ? stack.getCount() : 0;
        }
        return n;
    }

    @Nullable
    private static AbstractVehicle spawn(GameTestHelper helper, ResourceLocation id) {
        Optional<BaseVehicleData> data = CommonAssetsManager.vehicleDataManager().getVehicleData(id);
        if (data.isEmpty()) {
            helper.fail(id + " is not in the roster");
            return null;
        }
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        AbstractVehicle vehicle = data.get().construct(helper.getLevel(),
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5), 0, 0);
        vehicle.setDisplayId(id);
        if (!helper.getLevel().addFreshEntity(vehicle)) {
            helper.fail(id + " could not be added to the world");
            return null;
        }
        return vehicle;
    }
}
