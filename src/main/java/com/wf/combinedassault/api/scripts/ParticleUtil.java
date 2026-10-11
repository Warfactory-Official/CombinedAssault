package com.wf.combinedassault.api.scripts;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.resources.ResourceLocation;
import com.wf.combinedassault.CombinedAssault;

public enum ParticleUtil {

    INSTANCE;
    public record ParticleOptionsWrapper(ParticleOptions options) {}

    public ParticleOptionsWrapper buildParticleOptions(String particleName, String params) {
        ResourceLocation location = CombinedAssault.resourceLocation(particleName);
        if (!net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE.containsKey(location)) {
            return null;
        }
        var type = net.minecraft.core.registries.BuiltInRegistries.PARTICLE_TYPE.get(CombinedAssault.resourceLocation(particleName));
        if (type == null) {
            return null;
        }
        try {
            return new ParticleOptionsWrapper(readParticle(params, type));
        } catch (CommandSyntaxException e) {
            return null;
        }
    }

    private static <T extends ParticleOptions> T readParticle(String params, ParticleType<T> pType) throws CommandSyntaxException {
        // TODO: getDeserializer() removed in 1.21.1; migrate to codec system
        return null;
    }

}
