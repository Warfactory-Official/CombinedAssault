package com.wf.combinedassault.compat.rail;

import cam72cam.immersiverailroading.entity.EntityMoveableRollingStock;
import cam72cam.immersiverailroading.entity.EntityRollingStock;
import cam72cam.immersiverailroading.library.Gauge;
import cam72cam.immersiverailroading.registry.DefinitionManager;
import cam72cam.immersiverailroading.registry.EntityRollingStockDefinition;
import cam72cam.mod.entity.ModdedEntity;
import cam72cam.mod.math.Vec3d;
import cam72cam.mod.world.World;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * IR stock, addressed in vanilla/Limitless types only. Reflection (gametest framework) loads any type named
 * in a signature => an IR type here would fail to link without IR. Bodies may name IR types freely:
 * resolved only when run, after {@link RailCompat#isLoaded()}.
 */
public final class RailStock {

    private RailStock() {
    }

    /** Null: no match. */
    @Nullable
    public static String findDefinition(String contains) {
        String wanted = contains.toLowerCase();
        return DefinitionManager.getDefinitionNames().stream()
                .filter(name -> name.toLowerCase().contains(wanted))
                .findFirst().orElse(null);
    }

    /** Standard gauge; not parameterized. */
    @Nullable
    public static Entity spawn(Level level, String definitionId, Vec3 at, float yaw) {
        EntityRollingStockDefinition definition = DefinitionManager.getDefinition(definitionId);
        if (definition == null) {
            return null;
        }
        EntityRollingStock stock = definition.spawn(World.get(level), new Vec3d(at.x, at.y, at.z), yaw,
                Gauge.from(Gauge.STANDARD), "");
        return stock == null ? null : stock.internal;
    }

    /** Null: not stock. */
    @Nullable
    public static Vec3 positionOf(Entity entity) {
        EntityMoveableRollingStock stock = stockOf(entity);
        if (stock == null) {
            return null;
        }
        Vec3d at = stock.getPosition();
        return new Vec3(at.x, at.y, at.z);
    }

    /** Off-track placement: test/tool use only. */
    public static boolean placeAt(Entity entity, Vec3 at, float yaw) {
        EntityMoveableRollingStock stock = stockOf(entity);
        if (stock == null) {
            return false;
        }
        stock.setPosition(new Vec3d(at.x, at.y, at.z));
        stock.setRotationYaw(yaw);
        return true;
    }

    @Nullable
    private static EntityMoveableRollingStock stockOf(Entity entity) {
        return entity instanceof ModdedEntity modded
                && modded.getSelf() instanceof EntityMoveableRollingStock stock ? stock : null;
    }
}
