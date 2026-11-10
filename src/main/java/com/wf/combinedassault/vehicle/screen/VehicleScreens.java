package com.wf.combinedassault.vehicle.screen;

import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.indirect.client.GunComputer;
import com.wf.combinedassault.vehicle.entity.AbstractVehicle;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.combinedassault.weapon.part.WeaponUnit;
import com.wf.gemrender.direct.TextureSlots;
import com.wf.gemrender.gltf.GemRenderGltfModel;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Screens of one vehicle against its model: each {@code screen_<id>} slot mesh gives pose, size and bone, the pack's
 * {@code screens} entry the layout(s). Resolved once per model; a binding without a slot, or a slot without a binding,
 * is logged and skipped.
 */
@OnlyIn(Dist.CLIENT)
public final class VehicleScreens {
    private static final Map<AbstractVehicle, State> STATES = new WeakHashMap<>();
    /** Look ray within this many screen half-diagonals of a screen centre => that screen is aimed at. */
    private static final float AIM_SLACK = 1.5f;

    private VehicleScreens() {
    }

    /** Unlit screen textures of one vehicle, bound per frame. {@link #infos} / {@link #views}: current page. */
    public static final class State implements TextureSlots {
        public final AbstractVehicle vehicle;
        final GemRenderGltfModel model;
        public final int count;
        public final String[] slots;
        public final ScreenInfo[] infos;
        public final GemRenderGltfModel.SlotFrame[] frames;
        public final ScreenView[] views;
        public final ResourceLocation[] textures;
        final ScreenInfo[][] pageInfos;
        final ScreenView[][] pageViews;
        final int[] page;
        /** Look-ray angle / screen angular half-diagonal, last drawn frame; NaN = not drawn. */
        public final float[] aim;

        private State(AbstractVehicle vehicle, GemRenderGltfModel model, int count) {
            this.vehicle = vehicle;
            this.model = model;
            this.count = count;
            slots = new String[count];
            infos = new ScreenInfo[count];
            frames = new GemRenderGltfModel.SlotFrame[count];
            views = new ScreenView[count];
            textures = new ResourceLocation[count];
            pageInfos = new ScreenInfo[count][];
            pageViews = new ScreenView[count][];
            page = new int[count];
            aim = new float[count];
        }

        @Override
        @Nullable
        public ResourceLocation texture(String slot) {
            for (int i = 0; i < count; i++) {
                if (slots[i].equals(slot)) {
                    return textures[i];
                }
            }
            return null;
        }

        @Override
        public boolean emissive(String slot) {
            return slot.startsWith(ScreenInfo.SLOT_PREFIX);
        }

        void show(int i, int p) {
            page[i] = p;
            infos[i] = pageInfos[i][p];
            views[i] = pageViews[i][p];
        }

        /** Next page of the screen the look ray hits; false: none aimed at, or single page. */
        public boolean cycleAimed() {
            int best = -1;
            for (int i = 0; i < count; i++) {
                if (aim[i] < AIM_SLACK && (best < 0 || aim[i] < aim[best])) {
                    best = i;
                }
            }
            if (best < 0 || pageInfos[best].length < 2) {
                return false;
            }
            show(best, (page[best] + 1) % pageInfos[best].length);
            return true;
        }
    }

    /** Null while the vehicle has no screen the model can show. */
    @Nullable
    public static State of(AbstractVehicle vehicle, GemRenderGltfModel model) {
        if (vehicle.screens.isEmpty()) {
            return null;
        }
        State state = STATES.get(vehicle);
        if (state == null || state.model != model) {
            state = resolve(vehicle, model);
            STATES.put(vehicle, state);
        }
        return state.count == 0 ? null : state;
    }

    /** Resolved screens of {@code vehicle}, or null if none drawn yet. */
    @Nullable
    public static State of(AbstractVehicle vehicle) {
        State state = STATES.get(vehicle);
        return state == null || state.count == 0 ? null : state;
    }

    private static State resolve(AbstractVehicle vehicle, GemRenderGltfModel model) {
        String id = String.valueOf(vehicle.getVehicleId());
        for (String slot : model.slots()) {
            if (slot.startsWith(ScreenInfo.SLOT_PREFIX)
                    && !vehicle.screens.containsKey(slot.substring(ScreenInfo.SLOT_PREFIX.length()))) {
                CombinedAssault.LOGGER.warn("{}: model screen {} has no `screens` binding", id, slot);
            }
        }
        State state = new State(vehicle, model, vehicle.screens.size());
        int n = 0;
        screens:
        for (Map.Entry<String, ScreenInfo> entry : vehicle.screens.entrySet()) {
            String slot = ScreenInfo.SLOT_PREFIX + entry.getKey();
            GemRenderGltfModel.SlotFrame frame = model.slotFrame(slot);
            if (frame == null) {
                CombinedAssault.LOGGER.error("{}: screen {} skipped: model has no textured quad in slot {}", id, entry.getKey(), slot);
                continue;
            }
            ScreenInfo[] pages = entry.getValue().pages().toArray(ScreenInfo[]::new);
            ScreenView[] views = new ScreenView[pages.length];
            for (int p = 0; p < pages.length; p++) {
                views[p] = view(vehicle, pages[p]);
                if (views[p] == null) {
                    CombinedAssault.LOGGER.error("{}: screen {} skipped: no weapon part", id, entry.getKey());
                    continue screens;
                }
            }
            state.slots[n] = slot;
            state.frames[n] = frame;
            state.pageInfos[n] = pages;
            state.pageViews[n] = views;
            state.show(n, 0);
            n++;
        }
        return n == state.count ? state : shrink(state, n);
    }

    @Nullable
    private static ScreenView view(AbstractVehicle vehicle, ScreenInfo info) {
        return switch (info.kind) {
            case DASH -> new DashView(vehicle);
            case MFD -> new MfdView(vehicle);
            case GUN_COMPUTER, SIGHT -> {
                WeaponUnit unit = weapon(vehicle, info.part);
                yield unit == null ? null : new GunComputer(vehicle, unit);
            }
        };
    }

    private static State shrink(State from, int n) {
        State state = new State(from.vehicle, from.model, n);
        System.arraycopy(from.slots, 0, state.slots, 0, n);
        System.arraycopy(from.frames, 0, state.frames, 0, n);
        System.arraycopy(from.pageInfos, 0, state.pageInfos, 0, n);
        System.arraycopy(from.pageViews, 0, state.pageViews, 0, n);
        for (int i = 0; i < n; i++) {
            state.show(i, 0);
        }
        return state;
    }

    @Nullable
    private static WeaponUnit weapon(AbstractVehicle vehicle, @Nullable String part) {
        if (part != null) {
            return vehicle.getPartUnit(part).orElse(null) instanceof WeaponUnit unit ? unit : null;
        }
        for (PartUnit<?> candidate : vehicle.getPartUnits()) {
            if (candidate instanceof WeaponUnit unit) {
                return unit;
            }
        }
        return null;
    }
}
