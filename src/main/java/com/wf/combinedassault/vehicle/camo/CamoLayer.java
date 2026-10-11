package com.wf.combinedassault.vehicle.camo;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import com.wf.combinedassault.vehicle.pojo.CamoAction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Camo pieces for one vehicle + bone/block-state intern tables: a piece is a pair of shorts, not
 * a string and a serialized block state (300 oak leaves = 300 short-pairs + one state copy).
 */
public final class CamoLayer {

    /** Bytes/piece, wire + disk. See {@link #pack()}. */
    private static final int PIECE_BYTES = 15;
    /** Fixed-point steps/block; +-64 block range. */
    private static final float OFFSET_SCALE = 512f;
    /** Unsigned-byte steps/unit; 0..8 range. */
    private static final float SIZE_SCALE = 32f;
    private static final float ANGLE_SCALE = 128f / 180f;

    private final List<String> bones = new ArrayList<>();
    private final Map<String, Integer> boneIds = new HashMap<>();
    private final List<BlockState> palette = new ArrayList<>();
    private final Map<BlockState, Integer> paletteIds = new HashMap<>();
    private final List<CamoPiece> pieces = new ArrayList<>();

    private int version;
    private float footprint;
    private boolean dirty;

    public List<CamoPiece> pieces() {
        return Collections.unmodifiableList(pieces);
    }

    public boolean isEmpty() {
        return pieces.isEmpty();
    }

    public int size() {
        return pieces.size();
    }

    /** Bumped by every mutation; the renderer's only rebake signal. */
    public int version() {
        return version;
    }

    /**
     * True when the layer has been edited since it was last sent. Set by every mutation, cleared
     * by whoever syncs it. The vehicle checks this per server tick and resends the whole layer if
     * set, so any future mutator (a command, a damage or signature system, another mod) reaches
     * watching clients without that code having to remember to send anything. Paths that send an
     * incremental edit clear it themselves, at no extra cost.
     */
    public boolean isDirty() {
        return dirty;
    }

    /** Count the layer as synced. */
    public void markClean() {
        dirty = false;
    }

    /** Square blocks, maintained incrementally. Unused: a future signature model's coverage = this / hull surface area. */
    public float footprint() {
        return footprint;
    }

    public BlockState material(int index) {
        return index >= 0 && index < palette.size() ? palette.get(index) : Blocks.AIR.defaultBlockState();
    }

    public int boneCount() {
        return bones.size();
    }

    public int paletteCount() {
        return palette.size();
    }

    public String bone(int index) {
        return index >= 0 && index < bones.size() ? bones.get(index) : "";
    }

    public int internBone(String name) {
        String key = name == null ? "" : name;
        Integer existing = boneIds.get(key);
        if (existing != null) {
            return existing;
        }
        bones.add(key);
        int id = bones.size() - 1;
        boneIds.put(key, id);
        return id;
    }

    public int internMaterial(BlockState state) {
        Integer existing = paletteIds.get(state);
        if (existing != null) {
            return existing;
        }
        palette.add(state);
        int id = palette.size() - 1;
        paletteIds.put(state, id);
        return id;
    }

    public void add(CamoPiece piece) {
        pieces.add(piece);
        footprint += piece.scale() * piece.scale();
        version++;
        dirty = true;
    }

    /**
     * Drops the piece nearest a point in a bone's own space, within {@code radius}.
     *
     * @return true if something was removed
     */
    public boolean removeNearest(int bone, float x, float y, float z, float radius) {
        int best = -1;
        float bestSqr = radius * radius;
        for (int i = 0; i < pieces.size(); i++) {
            CamoPiece piece = pieces.get(i);
            if (piece.bone() != bone) {
                continue;
            }
            float dx = piece.x() - x;
            float dy = piece.y() - y;
            float dz = piece.z() - z;
            float distSqr = dx * dx + dy * dy + dz * dz;
            if (distSqr < bestSqr) {
                bestSqr = distSqr;
                best = i;
            }
        }
        if (best < 0) {
            return false;
        }
        CamoPiece removed = pieces.remove(best);
        footprint -= removed.scale() * removed.scale();
        version++;
        dirty = true;
        return true;
    }

    public void clear() {
        if (pieces.isEmpty() && palette.isEmpty() && bones.isEmpty()) {
            return;
        }
        pieces.clear();
        palette.clear();
        paletteIds.clear();
        bones.clear();
        boneIds.clear();
        footprint = 0;
        version++;
        dirty = true;
    }

    // Disk and the spawn packet share one form: intern tables as string lists, pieces as a single
    // packed byte array. 300 pieces come to about 4.5 KB.

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        if (pieces.isEmpty()) {
            return tag;
        }
        ListTag boneTag = new ListTag();
        for (String bone : bones) {
            boneTag.add(StringTag.valueOf(bone));
        }
        ListTag paletteTag = new ListTag();
        for (BlockState state : palette) {
            paletteTag.add(StringTag.valueOf(BlockStateParser.serialize(state)));
        }
        tag.put("Bones", boneTag);
        tag.put("Palette", paletteTag);
        tag.putByteArray("Pieces", pack());
        return tag;
    }

    public void load(HolderLookup.Provider provider, CompoundTag tag) {
        clear();
        // Whatever load produces is by definition what the sender already has; never a reason to send anything back.
        dirty = false;
        if (tag == null || !tag.contains("Pieces", Tag.TAG_BYTE_ARRAY)) {
            return;
        }
        ListTag boneTag = tag.getList("Bones", Tag.TAG_STRING);
        for (int i = 0; i < boneTag.size(); i++) {
            internBone(boneTag.getString(i));
        }
        HolderLookup.RegistryLookup<Block> blocks = provider.lookupOrThrow(Registries.BLOCK);
        ListTag paletteTag = tag.getList("Palette", Tag.TAG_STRING);
        for (int i = 0; i < paletteTag.size(); i++) {
            palette.add(parseState(blocks, paletteTag.getString(i)));
        }
        // putIfAbsent after the full list is built: two entries that round-trip onto one state
        // must keep both slots, or every piece indexing the later one resolves to the wrong material.
        for (int i = 0; i < palette.size(); i++) {
            paletteIds.putIfAbsent(palette.get(i), i);
        }
        unpack(tag.getByteArray("Pieces"));
        version++;
        dirty = false;
    }

    private static BlockState parseState(HolderLookup.RegistryLookup<Block> blocks, String serialized) {
        try {
            return BlockStateParser.parseForBlock(blocks, serialized, false).blockState();
        } catch (Exception exception) {
            return Blocks.AIR.defaultBlockState();
        }
    }

    private byte[] pack() {
        byte[] out = new byte[pieces.size() * PIECE_BYTES];
        int i = 0;
        for (CamoPiece piece : pieces) {
            i = putShort(out, i, piece.bone());
            i = putShort(out, i, piece.material());
            out[i++] = (byte) piece.kind().ordinal();
            i = putShort(out, i, Math.round(Mth.clamp(piece.x(), -64f, 64f) * OFFSET_SCALE));
            i = putShort(out, i, Math.round(Mth.clamp(piece.y(), -64f, 64f) * OFFSET_SCALE));
            i = putShort(out, i, Math.round(Mth.clamp(piece.z(), -64f, 64f) * OFFSET_SCALE));
            out[i++] = (byte) Math.round(Mth.clamp(piece.scale(), 0f, 7.9f) * SIZE_SCALE);
            out[i++] = (byte) Math.round(Mth.wrapDegrees(piece.pitch()) * ANGLE_SCALE);
            out[i++] = (byte) Math.round(Mth.wrapDegrees(piece.yaw()) * ANGLE_SCALE);
            out[i++] = (byte) Math.round(Mth.wrapDegrees(piece.roll()) * ANGLE_SCALE);
        }
        return out;
    }

    private void unpack(byte[] data) {
        int count = data.length / PIECE_BYTES;
        int i = 0;
        for (int p = 0; p < count; p++) {
            int bone = getShort(data, i);
            int material = getShort(data, i + 2);
            CamoKind kind = CamoKind.byId(data[i + 4]);
            float x = getShort(data, i + 5) / OFFSET_SCALE;
            float y = getShort(data, i + 7) / OFFSET_SCALE;
            float z = getShort(data, i + 9) / OFFSET_SCALE;
            float scale = (data[i + 11] & 0xFF) / SIZE_SCALE;
            float pitch = data[i + 12] / ANGLE_SCALE;
            float yaw = data[i + 13] / ANGLE_SCALE;
            float roll = data[i + 14] / ANGLE_SCALE;
            i += PIECE_BYTES;
            pieces.add(new CamoPiece(bone, material, kind, x, y, z, scale, pitch, yaw, roll));
            footprint += scale * scale;
        }
    }

    private static int putShort(byte[] out, int i, int value) {
        out[i] = (byte) (value >> 8);
        out[i + 1] = (byte) value;
        return i + 2;
    }

    private static int getShort(byte[] data, int i) {
        return (short) (((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF));
    }

    /**
     * Applies one edit. Both sides run this, so paint and erase mean the same thing on the server
     * and on every watching client. The action's own tables are packet-local and re-interned
     * here, so a client painting a block the layer has never seen doesn't need to negotiate an
     * index first. {@link CamoAction.Action#PAINT} rewrites {@code action.pieces} to exactly the
     * subset that landed, which lets the server echo the request object itself and have every
     * client converge on the server's layer without the two sides agreeing on the cap.
     *
     * @param maxPieces cap on the layer's size; an over-cap stroke is trimmed, not rejected
     * @return how many pieces the edit actually touched; zero means the caller can skip the broadcast
     */
    public int apply(HolderLookup.Provider provider, CamoAction action, int maxPieces) {
        return switch (action.action) {
            case CLEAR -> {
                int before = pieces.size();
                clear();
                yield before;
            }
            case ERASE -> {
                Integer bone = boneIds.get(action.eraseBone == null ? "" : action.eraseBone);
                yield bone != null
                        && removeNearest(bone, action.eraseX, action.eraseY, action.eraseZ, action.eraseRadius) ? 1 : 0;
            }
            case PAINT -> paint(provider, action, maxPieces);
        };
    }

    private int paint(HolderLookup.Provider provider, CamoAction action, int maxPieces) {
        if (action.pieces.isEmpty() || pieces.size() >= maxPieces) {
            return 0;
        }
        HolderLookup.RegistryLookup<Block> blocks = provider.lookupOrThrow(Registries.BLOCK);
        // Lazy intern: a cap-trimmed stroke must not leave dangling table entries behind, since
        // the tables are what the spawn packet pays for.
        int[] boneMap = new int[action.bones.size()];
        int[] materialMap = new int[action.palette.size()];
        Arrays.fill(boneMap, -1);
        Arrays.fill(materialMap, -1);
        List<CamoPiece> accepted = new ArrayList<>(action.pieces.size());
        for (CamoPiece piece : action.pieces) {
            if (pieces.size() >= maxPieces) {
                break;
            }
            if (piece.bone() < 0 || piece.bone() >= boneMap.length
                    || piece.material() < 0 || piece.material() >= materialMap.length) {
                continue;
            }
            if (materialMap[piece.material()] < 0) {
                BlockState state = parseState(blocks, action.palette.get(piece.material()));
                if (state.isAir()) {
                    continue;
                }
                materialMap[piece.material()] = internMaterial(state);
            }
            if (boneMap[piece.bone()] < 0) {
                boneMap[piece.bone()] = internBone(action.bones.get(piece.bone()));
            }
            add(new CamoPiece(boneMap[piece.bone()], materialMap[piece.material()], piece.kind(),
                    piece.x(), piece.y(), piece.z(),
                    piece.scale(), piece.pitch(), piece.yaw(), piece.roll()));
            accepted.add(piece);
        }
        action.pieces = accepted;
        return accepted.size();
    }
}
