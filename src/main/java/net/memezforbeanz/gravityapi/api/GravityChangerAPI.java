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
    
    /**
     * Returns the applied gravity direction for the given entity.
     * This version supports arbitrary angles (for VS ship rotation compatibility).
     */
    public static GravityDirection getGravityDirectionVec(Entity entity) {
        GravityCapabilityImpl comp = getGravityComponentEarly(entity);
        if (comp == null) {
            return GravityDirection.DOWN;
        }

        return comp.getCurrentGravityDirection();
    }

    /**
     * Check if the entity's gravity is from an arbitrary (non-cardinal) source like VS ships.
     * When true, hitbox should NOT be transformed (only camera rotates).
     */
    public static boolean isArbitraryGravity(Entity entity) {
        // First, check if entity is on a VS ship - this is the most reliable check
        // and should always prevent hitbox transformation for entities on ships
        if (VS2Integration.isVS2Loaded() && VS2Integration.isOnShip(entity)) {
            return true;
        }

        // Then check the capability for other cases
        GravityCapabilityImpl comp = getGravityComponentEarly(entity);
        if (comp == null) {
            return false;
        }
        return comp.isArbitraryGravity();
    }

    /**
     * STRICT check for the collision pipeline: true only when ship-relative gravity physics is
     * actually active for this entity (gravity generator field / camera-only ship gravity /
     * synced arbitrary direction). Unlike {@link #isArbitraryGravity(Entity)}, this does NOT
     * treat mere proximity to a VS ship as arbitrary, so normal-gravity entities bumping into a
     * ship keep VS2's stock collision instead of our ship-local pipeline.
     */
    public static boolean isShipGravityPhysics(Entity entity) {
        GravityCapabilityImpl comp = getGravityComponentEarly(entity);
        return comp != null && comp.isShipGravityPhysics();
    }

    public static double getGravityStrength(Entity entity) {
        return getGravityComponent(entity).getCurrGravityStrength();
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
    
    public static GravityCapabilityImpl getGravityComponent(Entity entity) {
    	GravityCapabilityImpl cap = (GravityCapabilityImpl) entity.getCapability(GravityCapabilities.GRAVITY).orElse(new GravityCapabilityImpl());
        return cap;
    }
    
    public static @Nullable GravityCapabilityImpl getGravityComponentEarly(Entity entity) {
        return getGravityComponent(entity);
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
     * The region of the entity a gravity field/plate must touch to claim it
     * (AmethystGravity's detection collider). The raw bounding box is unusable for this:
     * it is re-anchored at the feet on every cardinal gravity change, so at a field
     * boundary the box swings out of the field the moment the field claims the entity
     * and membership oscillates. This collider is a box spanning the entity's feet, one
     * entity-width tall, oriented by the entity's CURRENT gravity frame — the feet stay
     * fixed through 90° flips, so membership is stable through the transition.
     * <p>
     * When the effect pulls opposite to the current gravity ({@code effectWorldDir}
     * within ~25° of straight "up", e.g. a ceiling plate overhead), the full hitbox is
     * returned so head contact counts. On ships the physics hitbox stays world-aligned
     * and the full box is the honest representation.
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
