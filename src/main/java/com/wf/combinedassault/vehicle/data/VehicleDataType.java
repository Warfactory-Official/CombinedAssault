package com.wf.combinedassault.vehicle.data;

import com.google.gson.JsonElement;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Entity creation flow not owned here; implementations self-init config at NBT load or client creation packet. */
public record VehicleDataType<D extends BaseVehicleData> (
        ResourceLocation id,
        DataSerializer<D> dataSerializer
) {
    @Nullable
    public D parse(@NotNull JsonElement json) {
        return dataSerializer.parse(json);
    }

    @NotNull
    public ResourceLocation getId() {
        return id;
    }

    @FunctionalInterface
    public interface DataSerializer<D extends BaseVehicleData> {
        @Nullable
        D parse(JsonElement json);
    }

    public static class Builder<D extends BaseVehicleData> {
        private ResourceLocation id;
        private DataSerializer<D> dataSerializer;

        private Builder(ResourceLocation id) {
            this.id = id;
        }

        public static <D extends BaseVehicleData> Builder<D> of(ResourceLocation id) {
            return new Builder<>(id);
        }

        public Builder<D> setDataSerializer(DataSerializer<D> dataSerializer) {
            this.dataSerializer = dataSerializer;
            return this;
        }

        public VehicleDataType<D> build() {
            return new VehicleDataType<>(id, dataSerializer);
        }
    }
}
