package com.wf.combinedassault.ballistics.message;

import com.wf.wflib.armor.api.hull.HullResult;
import com.wf.wflib.armor.api.hull.WalkResult;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

/**
 * Debug record of one resolved round for the hit panel: entry plate, angles, thicknesses, damage split and where the
 * line crossed each surface ({@code crossings}: metres from the entry crossing; {@code stoppedAt}: crossing index where
 * the round died, -1 = it left the hull or never entered).
 */
public record HitDetail(String outcome, @Nullable String plate, @Nullable String part, float incidence, float nominal,
                        float effective, float penetration, float hullShare, float hullPool, float partDamage,
                        float interiorPen, float residualPen, int layers, int defeated, int stoppedAt,
                        float[] crossings, boolean[] entering, String[] crossingPlates, int eraHits, int glassHits,
                        boolean spall) {

    /** {@code hullPool}: what the hull pool actually lost, after core-line scaling and {@code hullShare}. */
    public static HitDetail of(HullResult r, float hullPool, boolean spall) {
        WalkResult w = r.walk();
        int n = w.crossings();
        float[] at = new float[n];
        boolean[] in = new boolean[n];
        for (int k = 0; k < n; k++) {
            at[k] = w.crossingAt(k) - w.crossingAt(0);
            in[k] = w.crossingEntering(k);
        }
        int stopCrossing = w.stoppedAt() >= 0 && w.stoppedAt() < w.layers() ? w.crossing(w.stoppedAt()) : -1;
        return new HitDetail(r.outcome().name(), r.plateId(), r.part(), r.incidence(), r.nominalThickness(),
                r.effectiveThickness(), r.penetration(), r.hullShare(), hullPool, r.partDamage(), w.interiorPen(),
                w.residualPen(), w.layers(), w.defeated(), stopCrossing, at, in, r.crossingPlates(), w.eraHits().size(),
                w.glassHits().size(), spall);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(outcome);
        buf.writeNullable(plate, FriendlyByteBuf::writeUtf);
        buf.writeNullable(part, FriendlyByteBuf::writeUtf);
        buf.writeFloat(incidence);
        buf.writeFloat(nominal);
        buf.writeFloat(effective);
        buf.writeFloat(penetration);
        buf.writeFloat(hullShare);
        buf.writeFloat(hullPool);
        buf.writeFloat(partDamage);
        buf.writeFloat(interiorPen);
        buf.writeFloat(residualPen);
        buf.writeVarInt(layers);
        buf.writeVarInt(defeated);
        buf.writeVarInt(stoppedAt + 1);
        buf.writeVarInt(crossings.length);
        for (int k = 0; k < crossings.length; k++) {
            buf.writeFloat(crossings[k]);
            buf.writeBoolean(entering[k]);
            buf.writeNullable(k < crossingPlates.length ? crossingPlates[k] : null, FriendlyByteBuf::writeUtf);
        }
        buf.writeVarInt(eraHits);
        buf.writeVarInt(glassHits);
        buf.writeBoolean(spall);
    }

    public static HitDetail read(FriendlyByteBuf buf) {
        String outcome = buf.readUtf();
        String plate = buf.readNullable(FriendlyByteBuf::readUtf);
        String part = buf.readNullable(FriendlyByteBuf::readUtf);
        float incidence = buf.readFloat(), nominal = buf.readFloat(), effective = buf.readFloat();
        float penetration = buf.readFloat(), hullShare = buf.readFloat(), hullPool = buf.readFloat();
        float partDamage = buf.readFloat(), interiorPen = buf.readFloat(), residualPen = buf.readFloat();
        int layers = buf.readVarInt(), defeated = buf.readVarInt(), stoppedAt = buf.readVarInt() - 1;
        int n = buf.readVarInt();
        float[] at = new float[n];
        boolean[] in = new boolean[n];
        String[] plates = new String[n];
        for (int k = 0; k < n; k++) {
            at[k] = buf.readFloat();
            in[k] = buf.readBoolean();
            plates[k] = buf.readNullable(FriendlyByteBuf::readUtf);
        }
        return new HitDetail(outcome, plate, part, incidence, nominal, effective, penetration, hullShare, hullPool,
                partDamage, interiorPen, residualPen, layers, defeated, stoppedAt, at, in, plates, buf.readVarInt(),
                buf.readVarInt(), buf.readBoolean());
    }
}
