package com.wf.combinedassault.vehicle;

import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import com.wf.combinedassault.CombinedAssault;

public class VehicleDamageTypes {

    public static final ResourceKey<DamageType> BULLET;
    public static final ResourceKey<DamageType> EXPLOSION;
    public static final ResourceKey<DamageType> VEHICLE_COLLISION;
    /** Incendiary charge on a hull: wears armour hard, no threshold. */
    public static final ResourceKey<DamageType> THERMITE;
    /** Charge burning inside the breech: reaches seated crew ({@code protect_passenger} ignored). */
    public static final ResourceKey<DamageType> BREECH_FIRE;

    static {
        BULLET = ResourceKey.create(Registries.DAMAGE_TYPE, CombinedAssault.modLocation("bullet"));
        EXPLOSION = ResourceKey.create(Registries.DAMAGE_TYPE, CombinedAssault.modLocation("explosion"));
        VEHICLE_COLLISION = ResourceKey.create(Registries.DAMAGE_TYPE, CombinedAssault.modLocation("vehicle_collision"));
        THERMITE = ResourceKey.create(Registries.DAMAGE_TYPE, CombinedAssault.modLocation("thermite"));
        BREECH_FIRE = ResourceKey.create(Registries.DAMAGE_TYPE, CombinedAssault.modLocation("breech_fire"));
    }

    public static class Sources {

        private static Holder.Reference<DamageType> getHolder(RegistryAccess access, ResourceKey<DamageType> damageTypeKey) {
            return access.registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(damageTypeKey);
        }

        public static DamageSource bullet(RegistryAccess access, Entity bullet, Entity shooter, Vec3 damageSourcePosition) {
            return new DamageSource(getHolder(access, BULLET), bullet, shooter, damageSourcePosition);
        }

        public static DamageSource explosion(RegistryAccess access, Entity directEntity, Entity causingEntity, Vec3 damageSourcePosition) {
            return new DamageSource(getHolder(access, EXPLOSION), directEntity, causingEntity, damageSourcePosition);
        }

        public static DamageSource thermite(RegistryAccess access, Entity charge, Entity attacker, Vec3 at) {
            return new DamageSource(getHolder(access, THERMITE), charge, attacker, at);
        }

        public static DamageSource breechFire(RegistryAccess access, Entity charge, Entity attacker) {
            return new DamageSource(getHolder(access, BREECH_FIRE), charge, attacker);
        }

        public static DamageSource vehicleCollision(RegistryAccess access, Entity vehicle, Entity driver, Vec3 damageSourcePosition) {
            return new DamageSource(getHolder(access, VEHICLE_COLLISION), vehicle, driver, damageSourcePosition);
        }

    }

}
