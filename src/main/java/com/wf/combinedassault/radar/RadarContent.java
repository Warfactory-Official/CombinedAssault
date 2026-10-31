package com.wf.combinedassault.radar;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.wf.combinedassault.CombinedAssault;

public final class RadarContent {

    static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, CombinedAssault.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<RadarMarkerEntity>> RADAR_MARKER = ENTITIES.register("radar_marker",
            () -> EntityType.Builder.of(RadarMarkerEntity::new, MobCategory.MISC)
                    .noSummon()
                    .noSave()
                    .fireImmune()
                    .sized(1F, 1F)
                    .clientTrackingRange(1024)
                    .updateInterval(1)
                    .setShouldReceiveVelocityUpdates(true)
                    .build("radar_marker"));

    private RadarContent() {
    }
}
