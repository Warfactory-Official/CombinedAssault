package com.wf.combinedassault.vehicle.hands;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wf.combinedassault.ballistics.hit.ModelData;
import com.wf.combinedassault.ballistics.hit.ModelMounts;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * {@code hand} / {@code foot} socket: wrist (sole) target of a seat rider's arm (leg) (pack doc §6.7).
 *
 * @param node   grafted glTF node index ({@code ModelMounts.links})
 * @param action null = rest contact; {@link #SHIFT} (hand), {@link #BRAKE} (foot) or a clip track name (part clip
 *               naming, bare {@code fire} / {@code reload} / {@code unjam} = the seat part's)
 * @param kit    kit-scoped mount it came from: applies only while that kit is fitted, over the part's own
 */
public record RiderHand(int node, String name, String part, boolean left, boolean foot, @Nullable String action,
                        @Nullable String kit) {

    public static final String SHIFT = "shift";
    public static final String BRAKE = "brake";

    /** Null: kit contact on a carrying part that is no seat. */
    @Nullable
    public static RiderHand read(int node, String name, String part, boolean foot, JsonObject meta,
                                 @Nullable String kit, Set<String> seats) {
        String limb = foot ? "foot" : "hand";
        for (String key : meta.keySet()) {
            if (!key.equals(limb) && !key.equals("action")) {
                throw new IllegalArgumentException(limb + " " + name + ": unknown key " + key);
            }
        }
        if (!seats.contains(part)) {
            if (kit != null) {
                return null;
            }
            throw new IllegalArgumentException(limb + " " + name + ": interface '" + part + "' is not a seat part");
        }
        JsonElement hand = meta.get(limb);
        String side = hand == null ? "" : hand.getAsString();
        if (!side.equals("right") && !side.equals("left")) {
            throw new IllegalArgumentException(limb + " " + name + ": combinedassault." + limb + " must be right or left");
        }
        JsonElement action = meta.get("action");
        String a = action == null ? null : action.getAsString();
        if (a != null && (a.isEmpty() || a.equals(foot ? SHIFT : BRAKE))) {
            throw new IllegalArgumentException(limb + " " + name + ": action '" + a + "'");
        }
        return new RiderHand(node, name, part, side.equals("left"), foot, a, kit);
    }

    /** Some rest contact of {@code part} ({@code kit} fitted) rides glTF node {@code bone}. */
    public static boolean restOn(ModelData model, String part, @Nullable String kit, String bone) {
        List<ModelMounts.Link> links = model.mounts().links();
        for (RiderHand hand : model.hands()) {
            if (!hand.foot && hand.action == null && hand.part.equals(part) && (hand.kit == null || hand.kit.equals(kit))) {
                for (int at = hand.node; at >= 0; at = links.get(at).parent()) {
                    if (links.get(at).name().equals(bone)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
