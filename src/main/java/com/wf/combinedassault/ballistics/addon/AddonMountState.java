package com.wf.combinedassault.ballistics.addon;

import net.minecraft.nbt.CompoundTag;

import java.util.Arrays;

/** What is on one mount point now. */
public final class AddonMountState {

    /** Bar health below this draws and counts as bent. */
    public static final float BENT = 0.999f;

    public AddonFit fit = AddonFit.NONE;
    /** ERA: bit i = cell i live. */
    public long live;
    /** Slat: per-bar health 0..1; 0 = gone. */
    public float[] bars = new float[0];

    public boolean isEmpty() {
        return fit == AddonFit.NONE;
    }

    public void install(AddonMount mount, AddonFit fit) {
        this.fit = fit;
        if (fit.kind == AddonKind.ERA) {
            live = allCells(mount);
            bars = new float[0];
        } else {
            live = 0;
            bars = new float[mount.bars];
            Arrays.fill(bars, 1.0f);
        }
    }

    public void clear() {
        fit = AddonFit.NONE;
        live = 0;
        bars = new float[0];
    }

    public static long allCells(AddonMount mount) {
        return mount.cells() == 64 ? -1L : (1L << mount.cells()) - 1;
    }

    public boolean isLive(int cell) {
        return (live >>> cell & 1L) != 0;
    }

    public int liveCells() {
        return Long.bitCount(live);
    }

    public int intactBars() {
        int n = 0;
        for (float bar : bars) {
            if (bar > 0) {
                n++;
            }
        }
        return n;
    }

    public int bentBars() {
        int n = 0;
        for (float bar : bars) {
            if (bar > 0 && bar < BENT) {
                n++;
            }
        }
        return n;
    }

    public boolean barPresent(int k) {
        return k >= 0 && k < bars.length && bars[k] > 0;
    }

    /** Fraction of the slat still worth something, for the item it comes off as. */
    public float slatCondition() {
        float sum = 0;
        for (float bar : bars) {
            sum += bar;
        }
        return bars.length == 0 ? 0 : sum / bars.length;
    }

    public CompoundTag save(String id) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", id);
        tag.putByte("Fit", (byte) fit.ordinal());
        tag.putLong("Live", live);
        byte[] packed = new byte[bars.length];
        for (int i = 0; i < bars.length; i++) {
            packed[i] = (byte) Math.round(bars[i] * 255);
        }
        tag.putByteArray("Bars", packed);
        return tag;
    }

    /** Loads into a state already sized for {@code mount}; a pack edit that changed the shape reinstalls fresh. */
    public void load(AddonMount mount, CompoundTag tag) {
        AddonFit loaded = AddonFit.byId(tag.getByte("Fit"));
        if (loaded == AddonFit.NONE || loaded.kind != mount.info.kind) {
            clear();
            return;
        }
        install(mount, loaded);
        if (loaded.kind == AddonKind.ERA) {
            live = tag.getLong("Live") & allCells(mount);
        } else {
            byte[] packed = tag.getByteArray("Bars");
            if (packed.length == bars.length) {
                for (int i = 0; i < packed.length; i++) {
                    bars[i] = (packed[i] & 0xFF) / 255.0f;
                }
            }
        }
    }
}
