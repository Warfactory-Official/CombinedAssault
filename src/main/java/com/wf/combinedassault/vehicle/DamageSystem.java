package com.wf.combinedassault.vehicle;

import com.wf.wflib.audio.AudioCategory;
import com.wf.combinedassault.audio.VehicleSounds;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import net.neoforged.neoforge.common.NeoForge;
import com.wf.combinedassault.CombinedAssault;
import com.wf.combinedassault.all.AllDamageTypes;
import com.wf.combinedassault.api.event.HitVehicleEvent;
import com.wf.combinedassault.ballistics.BallisticResult;
import com.wf.combinedassault.ballistics.Ballistics;
import com.wf.combinedassault.ballistics.addon.AddonStrike;
import com.wf.wflib.api.StrikeContext;
import com.wf.combinedassault.ballistics.addon.AddonStrikes;
import com.wf.combinedassault.entity.vehicle.AbstractVehicle;
import com.wf.combinedassault.vehicle.structure.VehicleCubeOBB;
import com.wf.combinedassault.util.PhysicsHelper;
import com.wf.combinedassault.util.VectorUtil;
import com.wf.combinedassault.vehicle.part.PartUnit;
import com.wf.wflib.util.OBB;
import com.wf.combinedassault.vehicle.hit.HitModel;
import com.wf.combinedassault.vehicle.hit.VehicleHits;
import com.wf.combinedassault.vehicle.hit.SurfaceMaterial;
import com.wf.combinedassault.network.message.ServerHitEffect;
import net.neoforged.neoforge.network.PacketDistributor;
import com.wf.combinedassault.ballistics.component.ComponentKind;
import com.wf.combinedassault.ballistics.component.HullComponents;
import com.wf.combinedassault.ballistics.component.VehicleSystems;
import org.joml.Vector3f;
import org.jetbrains.annotations.Nullable;

public class DamageSystem {

    public static void hurt(DamageSource damageSource, float amount, AbstractVehicle vehicle) {
        Entity driver = vehicle.getDriver();
        if (driver != null) {
            Team driverTeam = driver.getTeam();
            if (driverTeam != null) {
                Entity attacker = damageSource.getEntity();
                if (attacker != null && attacker != driver) {
                    Team attackerTeam = attacker.getTeam();
                    if (attackerTeam != null) {
                        if (!driverTeam.isAllowFriendlyFire() && driverTeam.equals(attackerTeam)) {
                            return;
                        }
                    }
                }
            }
        }
        double scale = 1;
        boolean explosion = damageSource.getMsgId().equals("combinedassault.explosion");
        PartUnit<?> hitPartUnit = null;
        Vec3 hitPos = null;
        float caliber = 5.8f;
        float penetrationScale = 1.0f;
        StrikeContext round = StrikeContext.of(damageSource);
        if (round != null) {
            Vec3 position = round.position();
            // Through ERA/slat: where the round met the hull, and what was left of it.
            AddonStrike.Result strike = AddonStrikes.get(round.key());
            Vec3 closestHitPos;
            if (strike != null) {
                closestHitPos = strike.hull();
                penetrationScale = strike.penetrationScale();
                amount *= strike.damageScale();
            } else {
                closestHitPos = VectorUtil.closestHullHit(vehicle, position, position.add(round.velocity()));
            }
            if (closestHitPos != null) {
                hitPos = closestHitPos;
                hitPartUnit = VectorUtil.hitPartUnit(vehicle, position, closestHitPos.add(closestHitPos.subtract(position).normalize().scale(0.1)), false);
            }
            caliber = round.threat().caliberMm();
        }
        if (damageSource.is(AllDamageTypes.VEHICLE_COLLISION) && damageSource.getDirectEntity() == vehicle) {
            hitPos = damageSource.getSourcePosition();
        }
        if (explosion) {
            // Explosion sourcePos = where blast met hull, not the exploding entity; fallback = entity position.
            Vec3 sourcePos = damageSource.getSourcePosition();
            if (sourcePos != null) {
                hitPos = sourcePos;
            } else if (damageSource.getDirectEntity() != null) {
                hitPos = damageSource.getDirectEntity().position();
            }
        }
        if (amount < 0.1) {
            amount = 0;
        }
        // null: no armour model answer (no armour, no penetration round, not a shot) => stock threshold/core-line path below.
        BallisticResult ballistic = Ballistics.resolve(vehicle, damageSource, amount, hitPos, hitPartUnit, penetrationScale);
        if (ballistic == null && hitPartUnit != null && hitPartUnit.isDefensive() && !hitPartUnit.isDetached()) {
            float partUnitDamage = amount;
            if (partUnitDamage < 0.1) {
                partUnitDamage = 0;
            } else if (amount < hitPartUnit.getDefenseStats().damageThreshold) {
                partUnitDamage = 0.1f;
            }
            hitPartUnit.hurt(damageSource, partUnitDamage);
            amount = partUnitDamage * hitPartUnit.getDefenseStats().damageTransferCoefficient;
        }
        if (ballistic != null) {
            if (hitPartUnit != null && !hitPartUnit.isDetached() && ballistic.partDamage() > 0) {
                hitPartUnit.hurt(damageSource, ballistic.partDamage());
            }
            amount = ballistic.hullDamage();
        } else if (amount < vehicle.defenseStats.damageThreshold) {
            amount = 0.1f;
        } else {
            if (hitPos == null) {
                scale = 0.2;
            } else if (!explosion && round != null) {
                Vec3 hitVec = round.velocity();
                OBB obb = vehicle.getMainCubeOBB().obb();
                Vec3 corePos = vehicle.relativeRotPos(new Vec3(obb.center()), false);
                Vec3 diff = corePos.subtract(hitPos);
                Vec3 cross = diff.cross(hitVec);
                double distanceToCore = cross.length() / hitVec.length();
                double distanceMax = obb.extents().get(obb.extents().maxComponent()) * 2;
                scale = (distanceMax - distanceToCore) / distanceMax;
            }
        }
        if (round != null && hitPos != null) {
            hitEffect(vehicle, round, hitPos, ballistic);
        }
        // Core-line falloff applies to hull only; struck system takes the hit whole.
        float systemAmount = amount;
        amount *= (float) scale;
        if (hitPos != null) {
            MutableComponent message;
            float damageScale = amount / vehicle.getMaxHealth();
            if (damageScale >= 0.5) {
                VehicleSounds.at(vehicle, hitPos, VehicleSounds.HIT_BIG, AudioCategory.WEAPONS);
                message = Component.translatable("message.vehicle.damage_system.critical");
            } else if (damageScale >= 0.25) {
                VehicleSounds.at(vehicle, hitPos, VehicleSounds.HIT_MED, AudioCategory.WEAPONS);
                message = Component.translatable("message.vehicle.damage_system.hurt");
            } else if (damageScale > 0 || ballistic != null) {
                // Zero-damage bounce still reported, not dropped.
                VehicleSounds.at(vehicle, hitPos, VehicleSounds.HIT_SMALL, AudioCategory.WEAPONS);
                message = Component.translatable("message.vehicle.damage_system.hit");
            } else {
                return;
            }
            if (ballistic != null) {
                // Overrides hit-size message: plate outcome is what matters for a bounce.
                message = Component.translatable(ballistic.outcome().translationKey());
            }
            if (round != null && round.shooter() != null) {
                HitVehicleEvent hitVehicleEvent = new HitVehicleEvent(round.shooter().getUUID(),
                        vehicle.getId(),
                        hitPos,
                        round.velocity(),
                        caliber,
                        amount,
                        message);
                NeoForge.EVENT_BUS.post(hitVehicleEvent);
            } else if (damageSource.getDirectEntity() instanceof AbstractVehicle && damageSource.getDirectEntity() != vehicle
                    && damageSource.getEntity() instanceof ServerPlayer serverPlayer) {
                Vec3 direction = vehicle.getBoundingBox().getCenter().subtract(hitPos).normalize();
                if (explosion) {
                    message = message.append(" ").append(Component.translatable("message.vehicle.damage_system.explosion").withStyle(ChatFormatting.YELLOW));
                }
                HitVehicleEvent hitVehicleEvent = new HitVehicleEvent(serverPlayer.getUUID(),
                        vehicle.getId(),
                        hitPos,
                        direction,
                        caliber,
                        amount,
                        message);
                NeoForge.EVENT_BUS.post(hitVehicleEvent);
            }
        } else {
            vehicle.playSound(vehicle.getHurtSound(damageSource), 2, 1);
        }
        // Hull-geometry systems (track/engine deck/fuel cell) add a failure mode; do not reduce hull damage.
        hurtHullSystem(vehicle, hitPos, damageSource, systemAmount);
        CombinedAssault.LOGGER.debug("{} damaged by {} with amount: {}", vehicle, damageSource, amount);
        float hull = vehicle.getHealth() - amount;
        vehicle.setHealth(Math.max(0, hull));
        if (hull < 0 && !vehicle.isDestroyed()) {
            VehicleSystems.overflow(vehicle, -hull);
        }
    }

    /** Surface effects of a round strike: struck face's normal + material from the hit mesh (else away from the shot). */
    private static void hitEffect(AbstractVehicle vehicle, StrikeContext round, Vec3 hitPos,
                                  @Nullable BallisticResult ballistic) {
        Vec3 direction = round.velocity().lengthSqr() < 1.0e-8 ? new Vec3(0, -1, 0) : round.velocity().normalize();
        VehicleHits.Hit surface = vehicle.hits() == null ? null
                : vehicle.hits().surface(hitPos.subtract(direction.scale(0.5)), hitPos.add(direction.scale(0.1)));
        Vec3 normal = ballistic != null ? ballistic.normal()
                : surface != null ? new Vec3(surface.normal()) : direction.reverse();
        PacketDistributor.sendToPlayersTrackingEntity(vehicle, new ServerHitEffect(vehicle.getId(),
                surface != null ? surface.point() : hitPos, direction, normal,
                ballistic == null ? null : ballistic.outcome(),
                surface != null ? surface.material() : SurfaceMaterial.STEEL,
                round.threat().kind(), round.threat().caliberMm(),
                ballistic == null ? 0 : ballistic.nominalThickness()));
    }

    /** Piece found by the armour model's own trace (shot's line); blast (no line) traces hit->core instead. */
    private static void hurtHullSystem(AbstractVehicle vehicle, @Nullable Vec3 hitPos,
                                       DamageSource damageSource, float amount) {
        HullComponents systems = vehicle.hullComponents();
        if (systems.isEmpty() || hitPos == null || amount <= 0) {
            return;
        }
        Vec3 from;
        Vec3 to;
        StrikeContext round = StrikeContext.of(damageSource);
        if (round != null && round.velocity().lengthSqr() > 1.0e-6) {
            Vec3 direction = round.velocity().normalize();
            from = hitPos.subtract(direction.scale(0.1));
            to = hitPos.add(direction.scale(0.1));
        } else if (vehicle.getMainCubeOBB() != null) {
            Vec3 core = new Vec3(vehicle.getMainCubeOBB().obb().centerX(),
                    vehicle.getMainCubeOBB().obb().centerY(),
                    vehicle.getMainCubeOBB().obb().centerZ());
            if (core.distanceToSqr(hitPos) < 1.0e-6) {
                return;
            }
            from = hitPos;
            to = core;
        } else {
            return;
        }
        VehicleHits hits = vehicle.hits();
        if (hits != null) {
            hurtHitSystem(vehicle, hits, systems, hitPos, from, to, amount);
            return;
        }
        hurtImplicit(vehicle, systems, hitPos, amount);
    }

    /**
     * Hit geometry: the first {@code hit_*} module along the shot's onward line (a blast: toward the core); else the
     * struck surface node's own system (rotor, track...); else the implicit pools. Part-owned surface = the part's.
     */
    private static void hurtHitSystem(AbstractVehicle vehicle, VehicleHits hits, HullComponents systems, Vec3 hitPos,
                                      Vec3 from, Vec3 to, float amount) {
        if (to.distanceToSqr(from) < 1.0e-12) {
            return;
        }
        Vec3 onward = to.subtract(from).normalize();
        Vec3 beyond = from.add(onward.scale(Math.max(to.distanceTo(from), vehicle.getStructureLength())));
        VehicleHits.Hit module = hits.module(from, beyond);
        if (module != null && systems.hurt(hits.model().nodes().get(module.node()).systemId(), amount)) {
            return;
        }
        VehicleHits.Hit surface = hits.surface(from, to);
        if (surface != null) {
            HitModel.Node node = hits.model().nodes().get(surface.node());
            if (node.part() != null) {
                return;
            }
            if (node.kind() != null && systems.hurt(node.systemId(), amount)) {
                return;
            }
        }
        hurtImplicit(vehicle, systems, hitPos, amount);
    }

    /** Low third of the main box = running gear on this side, and rest of the hull = {@link #ENGINE_SHARE} to the engine. */
    private static final double RUNNING_GEAR_BAND = 1.0 / 3.0;
    private static final float ENGINE_SHARE = 0.25f;

    /** Default pools (no geometry, {@link HullComponents#isImplicit}): routed by where on the main box the hit is. */
    private static void hurtImplicit(AbstractVehicle vehicle, HullComponents systems, Vec3 hitPos, float amount) {
        if (vehicle.getMainCubeOBB() == null) {
            return;
        }
        OBB main = vehicle.getMainCubeOBB().obb();
        Vector3f[] axes = main.getAxes();
        // Double until projected: world coords lose the offset in float far out.
        double rx = hitPos.x - main.centerX();
        double ry = hitPos.y - main.centerY();
        double rz = hitPos.z - main.centerZ();
        double localX = rx * axes[0].x + ry * axes[0].y + rz * axes[0].z;
        double localY = rx * axes[1].x + ry * axes[1].y + rz * axes[1].z;
        boolean low = localY < -main.extents().y + 2 * main.extents().y * RUNNING_GEAR_BAND;
        for (int i = 0; i < systems.size(); i++) {
            if (!systems.isImplicit(i)) {
                continue;
            }
            ComponentKind kind = systems.kind(i);
            if (kind == ComponentKind.ENGINE && !low) {
                systems.setHealth(i, systems.health(i) - amount * ENGINE_SHARE);
            } else if (low && kind == ComponentKind.WHEEL) {
                systems.setHealth(i, systems.health(i) - amount);
            } else if (low && kind == ComponentKind.TRACK) {
                // +X = port = left
                VehicleSystems.Side side = localX >= 0 ? VehicleSystems.Side.LEFT : VehicleSystems.Side.RIGHT;
                if (systems.id(i).startsWith(side == VehicleSystems.Side.LEFT ? "left" : "right")) {
                    systems.setHealth(i, systems.health(i) - amount);
                }
            }
        }
    }

    /** m/s vertical at touchdown: below = unhurt; at lethal, 2x max health (hull + overflow onto every system). */
    private static final double LANDING_SAFE = 9, LANDING_LETHAL = 25;

    /**
     * Ground vehicle touching down from a fall; sprung hulls land on suspension, which never reports a contact impact.
     * @param fall blocks/tick downward on the last airborne tick
     */
    public static void landingHurt(AbstractVehicle vehicle, double fall) {
        double speed = fall * PhysicsHelper.TICKS_PER_SECOND;
        if (speed <= LANDING_SAFE) {
            return;
        }
        double excess = (speed - LANDING_SAFE) / (LANDING_LETHAL - LANDING_SAFE);
        VehicleCubeOBB main = vehicle.getMainCubeOBB();
        Vec3 at = main == null ? vehicle.position()
                : main.obb().centerVec().subtract(0, main.obb().extents().y, 0);
        vehicle.hurt(AllDamageTypes.Sources.vehicleCollision(vehicle.level().registryAccess(), vehicle, vehicle.getDriver(), at),
                (float) (excess * excess * 2 * vehicle.getMaxHealth()));
    }

    /** @param at world contact centroid: full hull damage + the struck system, not the positionless 0.2 */
    public static void impactHurt(double velocityDiff, Vec3 at, AbstractVehicle vehicle) {
        velocityDiff *= 20;
        float damage = (float) (0.5 * (vehicle.physicsEngine.mass / PhysicsHelper.KILOGRAMS_PER_TONNE) * velocityDiff * velocityDiff * vehicle.defenseStats.impactMultiplier);
        vehicle.hurt(AllDamageTypes.Sources.vehicleCollision(vehicle.level().registryAccess(), vehicle, vehicle.getDriver(), at), damage);
    }

}
