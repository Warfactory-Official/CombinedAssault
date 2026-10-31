package com.wf.combinedassault.vehicle.screen;

import com.wf.combinedassault.CombinedAssault;
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
import com.wf.combinedassault.indirect.client.GunComputer;

/**
 * Screens of one vehicle against its model: each {@code screen_<id>} slot mesh gives pose, size and bone, the pack's
 * {@code screens} entry the layout. Resolved once per model; a binding without a slot, or a slot without a binding,
 * is logged and skipped.
 */
@OnlyIn(Dist.CLIENT)
public final class VehicleScreens {
    private static final Map<AbstractVehicle, State> STATES = new WeakHashMap<>();

    private VehicleScreens() {
    }

    /** Unlit screen textures of one vehicle, bound per frame. */
    public static final class State implements TextureSlots {
        final GemRenderGltfModel model;
        public final int count;
        public final String[] slots;
        public final ScreenInfo[] infos;
        public final GemRenderGltfModel.SlotFrame[] frames;
        public final GunComputer[] views;
        public final ResourceLocation[] textures;

        private State(GemRenderGltfModel model, int count) {
            this.model = model;
            this.count = count;
            slots = new String[count];
            infos = new ScreenInfo[count];
            frames = new GemRenderGltfModel.SlotFrame[count];
            views = new GunComputer[count];
            textures = new ResourceLocation[count];
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

    private static State resolve(AbstractVehicle vehicle, GemRenderGltfModel model) {
        String id = String.valueOf(vehicle.getVehicleId());
        for (String slot : model.slots()) {
            if (slot.startsWith(ScreenInfo.SLOT_PREFIX)
                    && !vehicle.screens.containsKey(slot.substring(ScreenInfo.SLOT_PREFIX.length()))) {
                CombinedAssault.LOGGER.warn("{}: model screen {} has no `screens` binding", id, slot);
            }
        }
        State state = new State(model, vehicle.screens.size());
        int n = 0;
        for (Map.Entry<String, ScreenInfo> entry : vehicle.screens.entrySet()) {
            String slot = ScreenInfo.SLOT_PREFIX + entry.getKey();
            GemRenderGltfModel.SlotFrame frame = model.slotFrame(slot);
            WeaponUnit unit = weapon(vehicle, entry.getValue().part);
            if (frame == null || unit == null) {
                CombinedAssault.LOGGER.error("{}: screen {} skipped: {}", id, entry.getKey(),
                        frame == null ? "model has no textured quad in slot " + slot : "no weapon part");
                continue;
            }
            state.slots[n] = slot;
            state.infos[n] = entry.getValue();
            state.frames[n] = frame;
            state.views[n] = new GunComputer(vehicle, unit);
            n++;
        }
        return n == state.count ? state : shrink(state, n);
    }

    private static State shrink(State from, int n) {
        State state = new State(from.model, n);
        System.arraycopy(from.slots, 0, state.slots, 0, n);
        System.arraycopy(from.infos, 0, state.infos, 0, n);
        System.arraycopy(from.frames, 0, state.frames, 0, n);
        System.arraycopy(from.views, 0, state.views, 0, n);
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
