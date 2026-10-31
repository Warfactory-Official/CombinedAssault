package com.wf.combinedassault.render.fx;

import com.wf.gemrender.particle.ParticleEmitter;
import com.wf.gemrender.particle.ParticlePool;
import dev.engine_room.flywheel.api.event.EndClientResourceReloadEvent;
import dev.engine_room.flywheel.api.visual.Effect;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.lib.visual.AbstractVisual;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * One emitter per {@link HitFxKind} around a region origin (float offsets). Camera past {@link #RECENTRE} => new
 * region; old one kept until its particles die. Client thread.
 */
final class HitFxWorld implements Effect {
    static final int RECENTRE = 512;
    static final int REACH = 1024;

    private static final HitFxKind[] KINDS = HitFxKind.values();
    @Nullable
    private static HitFxWorld current;
    private static final List<HitFxWorld> RETIRING = new ArrayList<>();
    /** Re-added next tick: a case model loaded after the visual was built without it. */
    @Nullable
    private static HitFxWorld readd;

    private final ClientLevel level;
    private final Vec3i origin;
    private final ParticleEmitter[] emitters = new ParticleEmitter[KINDS.length];
    /** Resolved on the client thread for the visual (built on Flywheel's executor); null = not loaded. */
    private final dev.engine_room.flywheel.api.model.Model[] models = new dev.engine_room.flywheel.api.model.Model[KINDS.length];
    private boolean partial;

    private HitFxWorld(ClientLevel level, Vec3 at) {
        this.level = level;
        for (HitFxKind k : KINDS) {
            emitters[k.ordinal()] = ParticleEmitter.create(k.style(), k.ring, at.x, at.y, at.z);
        }
        origin = emitters[0].origin();
    }

    /** @return a model still missing */
    private boolean resolveModels() {
        boolean missing = false;
        for (HitFxKind k : KINDS) {
            if (models[k.ordinal()] == null) {
                models[k.ordinal()] = k.model();
                missing |= models[k.ordinal()] == null;
            }
        }
        return missing;
    }

    @Nullable
    static ClientLevel currentLevel() {
        return current == null ? null : current.level;
    }

    /** Reloaded models are new keys; stale case meshes never reach a rebuilt visual. */
    static void onReload(EndClientResourceReloadEvent e) {
        if (current != null) {
            current.dropRigid();
            current.partial = true;
        }
        RETIRING.forEach(HitFxWorld::dropRigid);
    }

    private void dropRigid() {
        for (HitFxKind k : KINDS) {
            if (k.rigid()) {
                models[k.ordinal()] = null;
            }
        }
    }

    /** Null => no FX (no level, no Flywheel, out of reach). */
    @Nullable
    static ParticleEmitter emitter(HitFxKind kind, double x, double y, double z) {
        HitFxWorld w = current;
        if (w == null || Math.abs(x - w.origin.getX()) > REACH || Math.abs(y - w.origin.getY()) > REACH
                || Math.abs(z - w.origin.getZ()) > REACH) {
            return null;
        }
        return w.emitters[kind.ordinal()];
    }

    static void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (readd != null) {
            if (readd == current) {
                VisualizationManager.get(readd.level).effects().queueAdd(readd);
            }
            readd = null;
        }
        if (current != null && (current.level != level || level == null)) {
            retire(true);
        }
        RETIRING.removeIf(HitFxWorld::closeIfIdle);
        if (level == null || !VisualizationManager.supportsVisualization(level)) {
            return;
        }
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        if (current != null && (Math.abs(cam.x - current.origin.getX()) > RECENTRE
                || Math.abs(cam.y - current.origin.getY()) > RECENTRE
                || Math.abs(cam.z - current.origin.getZ()) > RECENTRE)) {
            retire(false);
        }
        if (current == null) {
            current = new HitFxWorld(level, mc.player != null ? mc.player.position() : cam);
            current.partial = current.resolveModels();
            VisualizationManager.get(level).effects().queueAdd(current);
        } else if (current.partial && !current.resolveModels()) {
            current.partial = false;
            VisualizationManager.get(level).effects().queueRemove(current);
            readd = current;
        }
    }

    private static void retire(boolean levelGone) {
        HitFxWorld w = current;
        current = null;
        if (levelGone) {
            w.close();
        } else {
            RETIRING.add(w);
        }
    }

    private boolean closeIfIdle() {
        for (ParticleEmitter em : emitters) {
            if (!em.isIdle()) {
                return false;
            }
        }
        close();
        return true;
    }

    private void close() {
        VisualizationManager vm = VisualizationManager.get(level);
        if (vm != null) {
            vm.effects().queueRemove(this);
        }
        for (ParticleEmitter em : emitters) {
            em.close();
        }
    }

    @Override
    public LevelAccessor level() {
        return level;
    }

    @Override
    public EffectVisual<?> visualize(VisualizationContext ctx, float partialTick) {
        return new Visual(ctx, this, partialTick);
    }

    private static final class Visual extends AbstractVisual implements EffectVisual<HitFxWorld> {
        private final List<ParticlePool> pools = new ArrayList<>();

        Visual(VisualizationContext ctx, HitFxWorld w, float partialTick) {
            super(ctx, (Level) w.level, partialTick);
            for (HitFxKind k : KINDS) {
                dev.engine_room.flywheel.api.model.Model m = w.models[k.ordinal()];
                if (m != null) {
                    pools.add(new ParticlePool(ctx, w.emitters[k.ordinal()], k.type, m));
                }
            }
        }

        @Override
        protected void _delete() {
            pools.forEach(ParticlePool::delete);
        }
    }
}
