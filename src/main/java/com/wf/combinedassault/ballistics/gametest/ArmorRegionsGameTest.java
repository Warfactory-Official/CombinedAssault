package com.wf.combinedassault.ballistics.gametest;

import com.wf.combinedassault.CombinedAssault;
import com.wf.wflib.armor.api.hull.HullWear;
import com.wf.combinedassault.ballistics.component.ComponentKind;
import com.wf.combinedassault.ballistics.component.VehicleSystems;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.gametest.TestLanes;
import com.wf.combinedassault.vehicle.crew.CrewRole;
import com.wf.combinedassault.vehicle.crew.CrewRules;
import com.wf.combinedassault.weapon.part.BarrelCharge;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.combinedassault.dynamics.structure.VehicleCubeOBB;
import com.wf.wflib.grenade.api.ChargeSpec;
import com.wf.wflib.grenade.api.GrenadeWarheads;
import com.wf.wflib.grenade.api.ThrownCharge;
import com.wf.wflib.round.api.KineticPreset;
import com.wf.wflib.round.api.KineticPresets;
import com.wf.wflib.round.api.RocketEntity;
import com.wf.wflib.geom.api.OBB;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3f;

import java.util.Locale;

/** Regioned armour wear (rounds, thermite) and the barrel charge, on an M1A2. */
@GameTestHolder(CombinedAssault.MOD_ID)
@PrefixGameTestTemplate(false)
public class ArmorRegionsGameTest {

    private static final String TEMPLATE = "empty";
    private static final ResourceLocation M1A2 = CombinedAssault.modLocation("m1a2");
    private static final ResourceLocation PG7V = ResourceLocation.parse("wflib:rpg7/heat");
    private static final int LANE_Z = -2300, LANE_PITCH = -64, FLOOR_X = 6, FLOOR_Z = 8;
    private static final ChargeSpec THERMITE = new ChargeSpec(GrenadeWarheads.THERMITE, 1, 0.1f, false, false, 0, 0, 0);

    /** Main-box point at normalised {@code (x, y, z)} (+X port, +Y up, +Z bow). */
    private static Vec3 local(AbstractVehicle v, double x, double y, double z) {
        VehicleCubeOBB main = v.getMainCubeOBB();
        OBB box = main.obb();
        Vector3f[] axes = box.getAxes();
        Vector3f e = box.extents();
        return box.centerVec().add(new Vec3(axes[0]).scale(x * e.x)).add(new Vec3(axes[1]).scale(y * e.y))
                .add(new Vec3(axes[2]).scale(z * e.z));
    }

    private static float faceWear(AbstractVehicle v, HullWear.Face face) {
        return 1 - v.hullWear().integrity(face);
    }

    private static float track(AbstractVehicle v, VehicleSystems.Side side) {
        float min = Float.MAX_VALUE;
        for (VehicleSystems.Cell cell : VehicleSystems.cells(v)) {
            if (cell.kind() == ComponentKind.TRACK && cell.side() == side) {
                min = Math.min(min, cell.ratio());
            }
        }
        return min;
    }

    /** RPG on the port side: only port cells wear; thickness there drops below an untouched cell's. */
    @GameTest(template = TEMPLATE, timeoutTicks = 300)
    public static void aRoundWearsOnlyTheRegionItStrikes(GameTestHelper helper) {
        TestLanes.run(helper, LANE_Z, FLOOR_X, FLOOR_Z, M1A2, (seq, v) -> seq
                .thenExecute(() -> {
                    AbstractVehicle tank = v.get();
                    Vec3 aim = local(tank, 1.0, 0.0, 0.0);
                    Vec3 out = aim.subtract(tank.getMainCubeOBB().obb().centerVec()).normalize();
                    Vec3 from = aim.add(out.scale(4));
                    KineticPreset rpg = KineticPresets.get(PG7V);
                    RocketEntity.fire(helper.getLevel(), rpg, from, out.reverse(), 0, Vec3.ZERO, null, null, null);
                })
                .thenIdle(10)
                .thenExecute(() -> {
                    AbstractVehicle tank = v.get();
                    float left = faceWear(tank, HullWear.Face.LEFT);
                    float right = faceWear(tank, HullWear.Face.RIGHT);
                    float front = faceWear(tank, HullWear.Face.FRONT);
                    int struck = HullWear.region(tank.mainBox(), local(tank, 1.0, 0.0, 0.0), null);
                    int corner = HullWear.index(HullWear.Face.LEFT, 0, 0);
                    float wf = tank.armorProfile.wearFloor;
                    float hit = tank.hullWear().thicknessScale(struck, wf);
                    float clean = tank.hullWear().thicknessScale(corner, wf);
                    helper.assertTrue(left > 0 && right == 0 && front == 0 && hit < clean,
                            String.format(Locale.ROOT, "wear L %.4f R %.4f F %.4f; scale struck %.4f corner %.4f",
                                    left, right, front, hit, clean));
                }), at -> { });
    }

    /** Thermite under the port track: belly wears, port track burns, starboard and top untouched. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void thermiteUnderATrackBurnsTrackAndBelly(GameTestHelper helper) {
        TestLanes.run(helper, LANE_Z + LANE_PITCH, FLOOR_X, FLOOR_Z, M1A2, (seq, v) -> {
            float[] before = new float[2];
            return seq.thenExecute(() -> {
                        AbstractVehicle tank = v.get();
                        before[0] = track(tank, VehicleSystems.Side.LEFT);
                        before[1] = track(tank, VehicleSystems.Side.RIGHT);
                        Vec3 under = local(tank, 0.8, -1.0, 0.0);
                        Vec3 ground = new Vec3(under.x, Math.floor(tank.getY()) + 0.06, under.z);
                        ThrownCharge.react(helper.getLevel(), ground, ItemStack.EMPTY, THERMITE, new Vec3(0, 1, 0),
                                null, null, false);
                    })
                    .thenIdle(70)
                    .thenExecute(() -> {
                        AbstractVehicle tank = v.get();
                        float belly = faceWear(tank, HullWear.Face.BOTTOM);
                        float top = faceWear(tank, HullWear.Face.TOP);
                        float port = track(tank, VehicleSystems.Side.LEFT);
                        float starboard = track(tank, VehicleSystems.Side.RIGHT);
                        helper.assertTrue(belly > 0 && top == 0 && port < before[0] && starboard >= before[1],
                                String.format(Locale.ROOT, "belly %.4f top %.4f port %.3f->%.3f stbd %.3f->%.3f",
                                        belly, top, before[0], port, before[1], starboard));
                    });
        }, at -> { });
    }

    /** Thermite on the roof: top wears, belly untouched. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void thermiteOnTheRoofWearsTheTop(GameTestHelper helper) {
        TestLanes.run(helper, LANE_Z + 2 * LANE_PITCH, FLOOR_X, FLOOR_Z, M1A2, (seq, v) -> seq
                .thenExecute(() -> {
                    AbstractVehicle tank = v.get();
                    Vec3 roof = local(tank, 0.0, 1.0, -0.6).add(0, 0.06, 0);
                    ThrownCharge.react(helper.getLevel(), roof, ItemStack.EMPTY, THERMITE, new Vec3(0, 1, 0), null,
                            tank, false);
                })
                .thenIdle(70)
                .thenExecute(() -> {
                    AbstractVehicle tank = v.get();
                    float top = faceWear(tank, HullWear.Face.TOP);
                    float belly = faceWear(tank, HullWear.Face.BOTTOM);
                    helper.assertTrue(top > 0 && belly == 0,
                            String.format(Locale.ROOT, "top %.4f belly %.4f", top, belly));
                }), at -> { });
    }

    /** Charge down the main gun: after the fuse the gun is broken and the loader burns; the driver does not. */
    @GameTest(template = TEMPLATE, timeoutTicks = 400)
    public static void aBarrelChargeBreaksTheGunAndBurnsTheLoader(GameTestHelper helper) {
        Husk[] crew = new Husk[2];
        TestLanes.run(helper, LANE_Z + 3 * LANE_PITCH, FLOOR_X, FLOOR_Z, M1A2, (seq, v) -> seq
                .thenExecute(() -> {
                    AbstractVehicle tank = v.get();
                    ServerLevel level = helper.getLevel();
                    WeaponUnit gun = null;
                    for (PartUnit<?> part : tank.getPartUnits()) {
                        if (part instanceof WeaponUnit unit && BarrelCharge.accepts(unit)) {
                            gun = unit;
                        }
                    }
                    if (gun == null) {
                        StringBuilder units = new StringBuilder();
                        for (PartUnit<?> part : tank.getPartUnits()) {
                            if (part instanceof WeaponUnit unit) {
                                units.append(String.format(Locale.ROOT, " [%d hp %.0f det %s bore %.1f n %d %s]",
                                        unit.getIndex(), unit.getHealth(), unit.isDetached(), BarrelCharge.bore(unit),
                                        unit.weapons.size(), unit.weapons.isEmpty() ? "-"
                                                : unit.weapons.get(0).solutionRound()));
                            }
                        }
                        helper.fail("no large-bore weapon:" + units);
                    }
                    for (int i = 0; i < 2; i++) {
                        crew[i] = new Husk(EntityType.HUSK, level);
                        crew[i].setNoAi(true);
                        crew[i].moveTo(tank.position());
                        level.addFreshEntity(crew[i]);
                        crew[i].startRiding(tank, true);
                    }
                    for (AbstractVehicle.Seat seat : tank.seats) {
                        if (CrewRules.roles(seat).contains(CrewRole.LOADER)) {
                            tank.changeSeat(crew[0], seat.seatIndex);
                        }
                    }
                    helper.assertTrue(CrewRules.roles(seatOf(tank, crew[0])).contains(CrewRole.LOADER),
                            "husk not in the loader seat");
                    BarrelCharge.insert(level, tank, gun, 0, ItemStack.EMPTY, THERMITE, null);
                })
                .thenIdle(40)
                .thenExecute(() -> {
                    AbstractVehicle tank = v.get();
                    WeaponUnit gun = null;
                    for (PartUnit<?> part : tank.getPartUnits()) {
                        if (part instanceof WeaponUnit unit && BarrelCharge.bore(unit) >= BarrelCharge.LARGE_BORE_MM) {
                            gun = unit;
                        }
                    }
                    float loader = crew[0].getHealth();
                    float other = crew[1].getHealth();
                    for (Husk h : crew) {
                        h.stopRiding();
                        h.discard();
                    }
                    helper.assertTrue(gun != null && gun.getHealth() <= 0 && loader < crew[0].getMaxHealth()
                                    && other >= crew[1].getMaxHealth(),
                            String.format(Locale.ROOT, "gun hp %s, loader %.1f, other %.1f",
                                    gun == null ? "?" : gun.getHealth(), loader, other));
                }), at -> { });
    }

    private static AbstractVehicle.Seat seatOf(AbstractVehicle tank, Husk h) {
        for (AbstractVehicle.Seat seat : tank.seats) {
            if (seat.passengerId == h.getId()) {
                return seat;
            }
        }
        throw new AssertionError("not seated");
    }
}
