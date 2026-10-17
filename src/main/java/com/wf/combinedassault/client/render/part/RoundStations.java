package com.wf.combinedassault.client.render.part;

import com.mojang.blaze3d.vertex.PoseStack;
import com.wf.combinedassault.compat.wflib.WfLibCompat;
import com.wf.combinedassault.client.model.VehicleModelInstance;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.vehicle.part.WeaponUnit;
import com.wf.combinedassault.vehicle.weapon.AbstractVehicleWeapon;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

import java.util.List;

/**
 * Weapons with {@code round_bones}: bone i shows a round while {@code i >= bones - remaining}. Drawable loaded round
 * other than {@code reload.ammo}'s default => its own airframe, bone origin = round centre, +Z = nose, baked mesh
 * hidden; else the baked mesh (default round = what the art depicts).
 */
@OnlyIn(Dist.CLIENT)
public final class RoundStations {

    private RoundStations() {
    }

    /** After the animation pose (scripts toggle the same bones), before publish. */
    public static void apply(AbstractVehicle vehicle, VehicleModelInstance model) {
        forEach(vehicle, (weapon, bones) -> {
            boolean own = drawsOwn(weapon);
            int fired = bones.size() - weapon.getRemainAmmo();
            for (int i = 0; i < bones.size(); i++) {
                model.setBoneVisible(bones.get(i), !own && i >= fired);
            }
        });
    }

    /** Pose: vehicle render frame (model root). */
    public static void render(AbstractVehicle vehicle, VehicleModelInstance model, PoseStack poseStack,
                              MultiBufferSource buffers, int light) {
        if (!model.posed()) {
            return;
        }
        forEach(vehicle, (weapon, bones) -> {
            if (!drawsOwn(weapon)) {
                return;
            }
            int fired = bones.size() - weapon.getRemainAmmo();
            for (int i = Math.max(0, fired); i < bones.size(); i++) {
                int bone = model.boneIndex(bones.get(i));
                if (bone < 0) {
                    continue;
                }
                poseStack.pushPose();
                model.mulGlobalTransform(poseStack, bone);
                WfLibCompat.renderRoundCentered(weapon.getLoadedRound(), Float.POSITIVE_INFINITY, poseStack, buffers, light);
                poseStack.popPose();
            }
        });
    }

    private static boolean drawsOwn(AbstractVehicleWeapon<?> weapon) {
        ItemStack round = weapon.getLoadedRound();
        var reload = weapon.getData().getReload();
        return WfLibCompat.canRenderRound(round)
                && (reload == null || !ItemStack.isSameItemSameComponents(round, reload.defaultRound()));
    }

    private interface Visitor {
        void visit(AbstractVehicleWeapon<?> weapon, List<String> bones);
    }

    private static void forEach(AbstractVehicle vehicle, Visitor visitor) {
        for (PartUnit<?> part : vehicle.getPartUnits()) {
            if (part instanceof WeaponUnit unit && !part.isDetached()) {
                for (AbstractVehicleWeapon<?> weapon : unit.getIndexedWeapons()) {
                    if (!weapon.getRoundBones().isEmpty()) {
                        visitor.visit(weapon, weapon.getRoundBones());
                    }
                }
            }
        }
    }
}
