package com.wf.combinedassault.tow;

import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;

import java.util.List;

/** Single key: nothing known of hitch names. Attached => detach; else couple to best match (hitch > cable, then nearest). */
public final class TowCoupler {

    private TowCoupler() {
    }

    public record Match(HitchInfo hitch, AbstractVehicle towed, TowMode mode, double distance) {
    }

    public static Component toggle(AbstractVehicle tractor) {
        AbstractVehicle dropped = last(tractor);
        if (dropped != null) {
            TowLink link = dropped.towLink();
            Component hitch = link == null ? Component.empty() : hitchName(tractor, link.hitchId());
            TowLinks.uncouple(dropped);
            return Component.translatable("tips.tow.uncoupled", dropped.getDisplayName(), hitch);
        }
        Match match = best(tractor);
        if (match == null) {
            return Component.translatable("tips.tow.nothing_in_reach");
        }
        if (!TowLinks.couple(tractor, match.towed(), match.hitch(), match.mode())) {
            return Component.translatable("tips.tow.refused", match.towed().getDisplayName());
        }
        return Component.translatable(
                switch (match.mode()) {
                    case HITCH -> "tips.tow.coupled";
                    case CABLE -> "tips.tow.on_cable";
                    case SLING -> "tips.tow.slung";
                },
                match.towed().getDisplayName(), hitchName(tractor, match.hitch().id));
    }

    /** LIFO: a press detaches the most recently coupled. */
    @Nullable
    public static AbstractVehicle last(AbstractVehicle tractor) {
        List<AbstractVehicle> towed = TowLinks.towedBy(tractor);
        return towed.isEmpty() ? null : towed.get(towed.size() - 1);
    }

    /** Best coupling a press would make; null = none. Searched from the hitch, not the driver's eye: hitch is out of sight behind the cab, backing up is done on mirrors. */
    @Nullable
    public static Match best(AbstractVehicle tractor) {
        Match best = null;
        for (HitchInfo hitch : tractor.towing.hitches) {
            Vec3 at = TowLinks.hitchPoint(tractor, hitch);
            double reach = hitch.supports(TowMode.CABLE) || hitch.supports(TowMode.SLING)
                    ? Math.max(hitch.cableLength, TowLinks.COUPLE_REACH) : TowLinks.COUPLE_REACH;
            AABB around = new AABB(at, at).inflate(reach + 16);
            for (AbstractVehicle candidate : tractor.level().getEntitiesOfClass(AbstractVehicle.class,
                    around, vehicle -> vehicle != tractor && vehicle.towing.towable.enabled)) {
                for (TowMode mode : TowMode.values()) {
                    if (TowLinks.check(tractor, candidate, hitch, mode, true) != TowLinks.Refusal.NONE) {
                        continue;
                    }
                    double distance = at.distanceTo(TowLinks.attachPoint(candidate, mode));
                    if (best == null || better(mode, distance, best)) {
                        best = new Match(hitch, candidate, mode, distance);
                    }
                }
            }
        }
        return best;
    }

    /** Hitch beats sling beats cable; else nearer wins. */
    private static boolean better(TowMode mode, double distance, Match against) {
        if (mode != against.mode()) {
            return rank(mode) < rank(against.mode());
        }
        return distance < against.distance();
    }

    private static int rank(TowMode mode) {
        return switch (mode) {
            case HITCH -> 0;
            case SLING -> 1;
            case CABLE -> 2;
        };
    }

    /** Falls back to id when the pack named nothing. */
    public static Component hitchName(AbstractVehicle tractor, String hitchId) {
        HitchInfo hitch = tractor.towing.hitch(hitchId);
        if (hitch == null) {
            return Component.literal(hitchId);
        }
        return hitch.name.isEmpty() ? Component.literal(hitch.id) : Component.translatable(hitch.name);
    }

}
