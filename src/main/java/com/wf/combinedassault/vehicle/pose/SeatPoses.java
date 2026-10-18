package com.wf.combinedassault.vehicle.pose;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.norwood.ahf.geometry.Obb;
import com.norwood.ahf.hook.AhfHooks;
import com.norwood.ahf.pose.PoseLibrary;
import com.norwood.ahf.rig.HumanoidRig;
import com.norwood.ahf.stance.AhfStances;
import com.norwood.ahf.stance.Stance;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.ThirdPersonRules;
import com.wf.combinedassault.vehicle.crew.CrewRole;
import com.wf.combinedassault.vehicle.crew.CrewRules;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3d;
import org.joml.Vector3d;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rider pose = AHF {@link PoseLibrary} set per seat ({@code ride_lower} + {@code hold_upper}): server rig and client
 * PlayerAnimator layers play the same clips. Part {@code pose}, else driver seat => {@code drive} ({@code pilot} on
 * aircraft), other => {@code sit}. {@link Tilt}: vehicle pitch / roll on the rig, as the rider render.
 */
public final class SeatPoses {

    public static final Map<String, ResourceLocation> SETS = new LinkedHashMap<>();

    static {
        for (String name : new String[]{"sit", "drive", "pilot", "stand", "kneel"}) {
            SETS.put(name, CombinedAssault.modLocation("seat_" + name));
        }
    }

    private SeatPoses() {
    }

    /** Mod construction, both sides. */
    public static void register() {
        SETS.values().forEach(id -> PoseLibrary.register(id, file(id)));
        // TODO: AhfHooks pose sets are first-non-null => a gun set registered earlier (MineAssault) wins over the
        //  seat set when the rider holds a gun; no priority in AHF.
        AhfHooks.addPoseSet(SeatPoses::of);
        AhfStances.register(new Tilt());
    }

    public static JsonObject file(ResourceLocation id) {
        String path = "/assets/" + id.getNamespace() + "/player_animator/" + id.getPath() + ".json";
        try (InputStream in = SeatPoses.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("missing " + path);
            }
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Nullable
    public static ResourceLocation of(LivingEntity entity) {
        if (!(entity instanceof Player) || !(entity.getVehicle() instanceof AbstractVehicle vehicle)
                || vehicle.getDetachedBodyAnchor(entity) != null) {
            return null;
        }
        for (AbstractVehicle.Seat seat : vehicle.seats) {
            if (seat.passengerId == entity.getId()) {
                return SETS.get(name(vehicle, seat));
            }
        }
        return null;
    }

    private static String name(AbstractVehicle vehicle, AbstractVehicle.Seat seat) {
        String declared = seat.partUnit.getData().getPose();
        if (declared != null) {
            return declared;
        }
        if (!CrewRules.roles(seat).contains(CrewRole.DRIVER)) {
            return "sit";
        }
        return ThirdPersonRules.classOf(vehicle).equals("aircraft") ? "pilot" : "drive";
    }

    /** Rig frame: x right, y up, z front, origin = feet, yaw = {@code yBodyRot} (AHF {@code HitboxApi}). */
    static final class Tilt implements Stance {

        @Override
        public String id() {
            return CombinedAssault.MOD_ID + ":vehicle_tilt";
        }

        @Override
        public boolean upright() {
            return true;
        }

        @Override
        public boolean appliesTo(LivingEntity entity) {
            return entity.getRootVehicle() != entity && entity.getRootVehicle() instanceof AbstractVehicle vehicle
                    && vehicle.getDetachedBodyAnchor(entity) == null;
        }

        @Override
        public void apply(LivingEntity entity, HumanoidRig.LocalRig rig) {
            AbstractVehicle vehicle = (AbstractVehicle) entity.getRootVehicle();
            if (vehicle.getXRot() == 0 && vehicle.getZRot() == 0) {
                return;
            }
            double yaw = Math.toRadians(vehicle.getYRot());
            // LivingEntityRendererMixin: Ry(-yaw) Rx(pitch) Rz(roll) Ry(yaw) ahead of the vanilla body yaw.
            Matrix3d tilt = new Matrix3d().rotationY(-yaw).rotateX(Math.toRadians(vehicle.getXRot()))
                    .rotateZ(Math.toRadians(vehicle.getZRot())).rotateY(yaw);
            Matrix3d toWorld = new Matrix3d().rotationY(Math.toRadians(-entity.yBodyRot)).scale(-1, 1, 1);
            Matrix3d local = new Matrix3d(toWorld).invert().mul(tilt).mul(toWorld);
            for (HumanoidRig.LocalRig.Slot slot : HumanoidRig.LocalRig.SLOTS) {
                Obb o = slot.get(rig);
                slot.set(rig, new Obb(turn(local, o.center()), turn(local, o.axisX()), turn(local, o.axisY()),
                        turn(local, o.axisZ()), o.half(), o.limb()));
            }
        }

        private static Vec3 turn(Matrix3d m, Vec3 v) {
            Vector3d out = m.transform(new Vector3d(v.x, v.y, v.z));
            return new Vec3(out.x, out.y, out.z);
        }
    }
}
