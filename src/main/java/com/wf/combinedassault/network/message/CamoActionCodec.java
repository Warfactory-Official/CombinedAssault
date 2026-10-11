package com.wf.combinedassault.network.message;

import net.minecraft.network.FriendlyByteBuf;
import com.wf.combinedassault.vehicle.camo.CamoKind;
import com.wf.combinedassault.vehicle.camo.CamoPiece;
import com.wf.combinedassault.vehicle.pojo.CamoAction;

/**
 * Wire form of a resolved {@link CamoAction}: server states what it placed, not rays.
 * Server resolves placement itself; see {@link ClientCamoAction} for the other direction.
 */
final class CamoActionCodec {

    /** Cap on one stroke's pieces; bounds decode allocation against an untrusted wire count. */
    static final int MAX_STROKE = 4096;

    private CamoActionCodec() {
    }

    static void encode(FriendlyByteBuf buf, CamoAction action) {
        buf.writeEnum(action.action);
        buf.writeInt(action.vehicleId);
        switch (action.action) {
            case CLEAR -> {
            }
            case ERASE -> {
                buf.writeUtf(action.eraseBone == null ? "" : action.eraseBone);
                buf.writeFloat(action.eraseX);
                buf.writeFloat(action.eraseY);
                buf.writeFloat(action.eraseZ);
                buf.writeFloat(action.eraseRadius);
            }
            case PAINT -> {
                buf.writeVarInt(action.bones.size());
                for (String bone : action.bones) {
                    buf.writeUtf(bone);
                }
                buf.writeVarInt(action.palette.size());
                for (String state : action.palette) {
                    buf.writeUtf(state);
                }
                buf.writeVarInt(Math.min(action.pieces.size(), MAX_STROKE));
                int written = 0;
                for (CamoPiece piece : action.pieces) {
                    if (written++ >= MAX_STROKE) {
                        break;
                    }
                    buf.writeVarInt(piece.bone());
                    buf.writeVarInt(piece.material());
                    buf.writeByte(piece.kind().ordinal());
                    buf.writeFloat(piece.x());
                    buf.writeFloat(piece.y());
                    buf.writeFloat(piece.z());
                    buf.writeFloat(piece.scale());
                    buf.writeFloat(piece.pitch());
                    buf.writeFloat(piece.yaw());
                    buf.writeFloat(piece.roll());
                }
            }
        }
    }

    static void decode(FriendlyByteBuf buf, CamoAction action) {
        action.action = buf.readEnum(CamoAction.Action.class);
        action.vehicleId = buf.readInt();
        switch (action.action) {
            case CLEAR -> {
            }
            case ERASE -> {
                action.eraseBone = buf.readUtf();
                action.eraseX = buf.readFloat();
                action.eraseY = buf.readFloat();
                action.eraseZ = buf.readFloat();
                action.eraseRadius = buf.readFloat();
            }
            case PAINT -> {
                int boneCount = Math.min(buf.readVarInt(), MAX_STROKE);
                for (int i = 0; i < boneCount; i++) {
                    action.bones.add(buf.readUtf());
                }
                int paletteCount = Math.min(buf.readVarInt(), MAX_STROKE);
                for (int i = 0; i < paletteCount; i++) {
                    action.palette.add(buf.readUtf());
                }
                int pieceCount = Math.min(buf.readVarInt(), MAX_STROKE);
                for (int i = 0; i < pieceCount; i++) {
                    action.pieces.add(new CamoPiece(
                            buf.readVarInt(), buf.readVarInt(), CamoKind.byId(buf.readByte()),
                            buf.readFloat(), buf.readFloat(), buf.readFloat(),
                            buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()));
                }
            }
        }
    }
}
