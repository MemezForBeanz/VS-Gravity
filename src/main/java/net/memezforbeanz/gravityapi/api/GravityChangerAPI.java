package net.memezforbeanz.gravityapi.api;

import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;

import net.memezforbeanz.gravityapi.EntityTags;
import net.memezforbeanz.gravityapi.RotationAnimation;
import net.memezforbeanz.gravityapi.capabilities.GravityCapabilities;
import net.memezforbeanz.gravityapi.capabilities.GravityCapabilityImpl;
import net.memezforbeanz.gravityapi.compat.vs2.VS2Integration;
import net.memezforbeanz.gravityapi.util.RotationUtil;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

public abstract class GravityChangerAPI {
    /**
     * Returns the applied gravity direction for the given entity (cardinal direction)
     */
    public static Direction getGravityDirection(Entity entity) {
        GravityCapabilityImpl comp = getGravityComponentEarly(entity);
        if (comp == null) {
            return Direction.DOWN;
        }
        
        return comp.getCurrGravityDirection();
    }
    
    /** The applied gravity direction, including arbitrary (VS ship) angles. */
    public static GravityDirection getGravityDirectionVec(Entity entity) {
        GravityCapabilityImpl comp = getGravityComponentEarly(entity);
        if (comp == null) {
            return GravityDirection.DOWN;
        }

        return comp.getCurrentGravityDirection();
    }

    /** Whether the entity's gravity is arbitrary (VS ship); the hitbox is then not transformed, only the camera rotates. */
    public static boolean isArbitraryGravity(Entity entity) {
        // The capability flag first: the ship check is a VS2 query
        GravityCapabilityImpl comp = getGravityComponentEarly(entity);
        if (comp != null && comp.isArbitraryGravity()) {
            return true;
        }

        // Entities on a VS ship are always arbitrary
        return VS2Integration.isVS2Loaded() && VS2Integration.isOnShip(entity);
    }

    /**
     * Strict check for the collision pipeline: ship-relative gravity physics is active. Unlike
     * {@link #isArbitraryGravity(Entity)}, proximity to a ship doesn't count, so normal-gravity
     * entities touching a ship keep VS2's collision.
     */
    public static boolean isShipGravityPhysics(Entity entity) {
        GravityCapabilityImpl comp = getGravityComponentEarly(entity);
        return comp != null && comp.isShipGravityPhysics();
    }

    /** Add the entity's pending field free-fall acceleration (from travel). */
    public static void applyFreeFallAcceleration(Entity entity) {
        GravityCapabilityImpl comp = getGravityComponentEarly(entity);
        if (comp != null) {
            comp.applyFreeFallAcceleration();
        }
    }

    public static double getGravityStrength(Entity entity) {
        return getGravityComponent(entity).getCurrGravityStrength();
    }
    
    /**
     * Override the entity's gravity strength for the next tick (1.0 = overworld gravity),
     * replacing planet gravity from Ad Astra / Cosmic Horizons. Re-apply every tick, like
     * direction effects; the highest priority wins.
     */
    public static void applyGravityStrengthOverride(Entity entity, double strength, double priority) {
        GravityCapabilityImpl comp = getGravityComponentEarly(entity);
        if (comp != null) {
            comp.applyGravityStrengthOverride(strength, priority);
        }
    }

    /**
     * Apply a gravity field's pull for this tick (see
     * {@link GravityCapabilityImpl#applyFieldGravityEffect}).
     *
     * @param landingFace world-space face to stand on (null: {@code pull}'s nearest)
     * @param nearSurface the field found that face close ahead or underfoot
     */
    public static void applyFieldGravityEffect(Entity entity, Vec3 pull, @Nullable Vec3 landingFace,
                                               boolean nearSurface, double priority) {
        GravityCapabilityImpl comp = getGravityComponentEarly(entity);
        if (comp != null) {
            comp.applyFieldGravityEffect(pull, landingFace, nearSurface, false, RotationParameters.getDefault(), priority);
        }
    }

    /** {@link #applyFieldGravityEffect} for a field on a VS ship: faces are ship-relative. */
    public static void applyShipFieldGravityEffect(Entity entity, Vec3 pull, @Nullable Vec3 landingFace,
                                                   boolean nearSurface, double priority) {
        GravityCapabilityImpl comp = getGravityComponentEarly(entity);
        if (comp != null) {
            comp.applyFieldGravityEffect(pull, landingFace, nearSurface, true, RotationParameters.getDefault(), priority);
        }
    }

    /** A field free fall's tilt (render, view vector, movement input) as a player-frame rotation ({@code worldToPlayer' = tilt^-1 * worldToPlayer}), or null. */
    public static @Nullable Quaternionf getVisualTilt(Entity entity, float partialTick) {
        GravityCapabilityImpl comp = getGravityComponentEarly(entity);
        return comp != null ? comp.getVisualTilt(partialTick) : null;
    }

    /** Hand the current visual tilt to a starting rotation animation (clears it). */
    public static @Nullable Quaternionf consumeVisualTilt(Entity entity) {
        GravityCapabilityImpl comp = getGravityComponentEarly(entity);
        return comp != null ? comp.consumeVisualTilt() : null;
    }

    /** Whether a gravity strength override (e.g. a gravity generator's target gravity) holds. */
    public static boolean isGravityStrengthOverridden(Entity entity) {
        GravityCapabilityImpl comp = getGravityComponentEarly(entity);
        return comp != null && comp.isStrengthOverridden();
    }

    /**
     * Whether planet mods (Ad Astra, Cosmic Horizons) must not add their gravity: a strength
     * override holds, or a projectile is inside a gravity field (which is its only gravity).
     */
    public static boolean isPlanetGravitySuppressed(Entity entity) {
        return isGravityStrengthOverridden(entity)
                || (entity instanceof net.minecraft.world.entity.projectile.Projectile
                    && GravityFields.fieldAcceleration(entity.level(), entity.position()) != null);
    }

    public static double getBaseGravityStrength(Entity entity) {
        return getGravityComponent(entity).getBaseGravityStrength();
    }
    
    public static void setBaseGravityStrength(Entity entity, double strength) {
        GravityCapabilityImpl component = getGravityComponent(entity);
        
        component.setBaseGravityStrength(strength);
    }
    
    public static void resetGravity(Entity entity) {
        if (!EntityTags.canChangeGravity(entity)) {return;}
        
        getGravityComponent(entity).reset();
    }
    
    /**
     * Returns the main gravity direction for the given entity
     * This may not be the applied gravity direction for the player, see GravityChangerAPI#getAppliedGravityDirection
     */
    public static Direction getBaseGravityDirection(Entity entity) {
        return getGravityComponent(entity).getBaseGravityDirection();
    }
    
    public static void setBaseGravityDirection(
        Entity entity, Direction gravityDirection
    ) {
        GravityCapabilityImpl component = getGravityComponent(entity);
        component.setBaseGravityDirection(gravityDirection);
    }
    
    @Nullable
    @OnlyIn(Dist.CLIENT)
    public static RotationAnimation getRotationAnimation(Entity entity) {
        return getGravityComponent(entity).getRotationAnimation();
    }
    
    /**
     * Instantly set gravity direction on client side without performing animation.
     * Not needed in normal cases.
     * (Used by iPortal)
     */
    public static void instantlySetClientBaseGravityDirection(Entity entity, Direction direction) {
        Validate.isTrue(entity.level().isClientSide(), "should only be used on client");
        
        GravityCapabilityImpl component = getGravityComponent(entity);
        
        component.setBaseGravityDirection(direction);
        
        component.updateGravityStatus(false);
        
        component.forceApplyGravityChange();
    }
    
    /**
     * Take the gravity effects applied to {@code entity} so far this tick as its frame right now,
     * with no flip or animation: call after applying a field's effect to a just-spawned entity so
     * it spawns already standing in the field (server only).
     */
    public static void settleGravityNow(Entity entity) {
        GravityCapabilityImpl comp = getGravityComponentEarly(entity);
        if (comp != null) {
            comp.settleNow();
        }
    }

    public static GravityCapabilityImpl getGravityComponent(Entity entity) {
        GravityCapabilityImpl cap = getGravityComponentEarly(entity);
        // A fresh impl only when the capability is absent: callers may mutate it
        return cap != null ? cap : new GravityCapabilityImpl();
    }

    public static @Nullable GravityCapabilityImpl getGravityComponentEarly(Entity entity) {
        // Not orElse(new ...), which allocates on every lookup
        return (GravityCapabilityImpl) entity.getCapability(GravityCapabilities.GRAVITY).resolve().orElse(null);
    }
    
    /**
     * Returns the world relative velocity for the given entity
     * Using minecraft's methods to get the velocity will return entity local velocity
     */
    public static Vec3 getWorldVelocity(Entity entity) {
        GravityDirection gravDir = getGravityDirectionVec(entity);
        return RotationUtil.vecPlayerToWorld(entity.getDeltaMovement(), gravDir);
    }
    
    /**
     * Sets the world relative velocity for the given player
     * Using minecraft's methods to set the velocity of an entity will set player relative velocity
     */
    public static void setWorldVelocity(Entity entity, Vec3 worldVelocity) {
        GravityDirection gravDir = getGravityDirectionVec(entity);
        entity.setDeltaMovement(RotationUtil.vecWorldToPlayer(worldVelocity, gravDir));
    }
    
    /**
     * Returns eye position offset from feet position for the given entity
     */
    public static Vec3 getEyeOffset(Entity entity) {
        GravityDirection gravDir = getGravityDirectionVec(entity);
        return RotationUtil.vecPlayerToWorld(new Vec3(0, (double) entity.getEyeHeight(), 0), gravDir);
    }
    
    public static boolean canChangeGravity(Entity entity) {
        return EntityTags.canChangeGravity(entity);
    }

    /**
     * The region of the entity a gravity field or plate must touch to claim it: a box at the feet,
     * one entity-width tall, in the current gravity frame. The raw bounding box re-anchors on every
     * flip and would swing out of the field at its boundary. When the effect pulls opposite the
     * current gravity (a ceiling plate), or on a ship, the full hitbox is returned.
     */
    public static AABB getGravityEffectCollider(
        Entity entity, @Nullable Vec3 effectWorldDir, boolean onShip
    ) {
        GravityCapabilityImpl comp = getGravityComponent(entity);
        if (comp == null) {
            return entity.getBoundingBox();
        }
        GravityDirection current = comp.getCurrentGravityDirection();
        if (effectWorldDir != null && current.getVector().dot(effectWorldDir) < -0.9) {
            return entity.getBoundingBox();
        }
        if (onShip) {
            return entity.getBoundingBox();
        }
        double hw = entity.getBbWidth() / 2.0;
        Vec3 p1 = current.vecPlayerToWorld(new Vec3(hw, 2.0 * hw, hw));
        Vec3 p2 = current.vecPlayerToWorld(new Vec3(-hw, 0.0, -hw));
        return new AABB(p1, p2).move(entity.position());
    }

}
