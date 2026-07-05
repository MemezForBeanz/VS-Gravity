package net.memezforbeanz.gravityapi.compat.vs2;

import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.api.RotationParameters;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * API for interacting with ship-relative gravity in Valkyrien Skies 2.
 * 
 * This API allows other mods to:
 * - Set gravity relative to a ship's orientation
 * - Query ship-relative gravity state
 * - Enable/disable automatic gravity alignment on ships
 * 
 * Example usage for a gravity-changing block on a ship:
 * <pre>
 * // When player interacts with your block on a ship:
 * ShipGravityAPI.setShipRelativeGravity(player, Direction.NORTH); // Player's gravity is now ship's north
 * 
 * // Or for more control:
 * ShipGravityAPI.setShipRelativeGravity(player, Direction.UP, rotationParameters); // Invert gravity on ship
 * </pre>
 */
public class ShipGravityAPI {
    
    /**
     * Check if Valkyrien Skies 2 integration is available
     */
    public static boolean isAvailable() {
        return VS2Integration.isVS2Loaded();
    }
    
    /**
     * Check if an entity is currently standing on a VS2 ship
     */
    public static boolean isOnShip(Entity entity) {
        return VS2Integration.isOnShip(entity);
    }
    
    /**
     * Get the ship ID that an entity is standing on
     * @return The ship ID, or null if not on a ship
     */
    @Nullable
    public static Long getShipId(Entity entity) {
        return VS2Integration.getShipMountedOn(entity);
    }
    
    /**
     * Get the ship gravity capability for an entity
     */
    @Nullable
    public static IShipGravityCapability getShipGravityCapability(Entity entity) {
        if (!isAvailable()) {
            return null;
        }
        return entity.getCapability(ShipGravityCapabilities.SHIP_GRAVITY).orElse(null);
    }
    
    /**
     * Set the gravity direction relative to the ship the entity is standing on.
     * If the entity is not on a ship, this will set world gravity instead.
     * 
     * @param entity The entity to change gravity for
     * @param shipRelativeDirection The gravity direction relative to the ship (e.g., Direction.DOWN for normal ship gravity)
     */
    public static void setShipRelativeGravity(Entity entity, Direction shipRelativeDirection) {
        setShipRelativeGravity(entity, shipRelativeDirection, RotationParameters.getDefault());
    }
    
    /**
     * Set the gravity direction relative to the ship the entity is standing on.
     * 
     * @param entity The entity to change gravity for
     * @param shipRelativeDirection The gravity direction relative to the ship
     * @param rotationParameters Parameters for the gravity rotation animation
     */
    public static void setShipRelativeGravity(Entity entity, Direction shipRelativeDirection, RotationParameters rotationParameters) {
        if (!isAvailable()) {
            // Fall back to world gravity if VS2 is not available
            GravityChangerAPI.setBaseGravityDirection(entity, shipRelativeDirection);
            return;
        }
        
        IShipGravityCapability shipGravity = getShipGravityCapability(entity);
        if (shipGravity == null) {
            GravityChangerAPI.setBaseGravityDirection(entity, shipRelativeDirection);
            return;
        }
        
        Long shipId = VS2Integration.getShipMountedOn(entity);
        if (shipId != null) {
            // Entity is on a ship - set ship-relative gravity
            shipGravity.setShipId(shipId);
            shipGravity.setShipRelativeGravityDirection(shipRelativeDirection);
            shipGravity.setShipGravityEnabled(true);
            
            // Apply immediately
            if (shipGravity instanceof ShipGravityCapabilityImpl impl) {
                impl.applyShipRelativeGravity();
            }
        } else {
            // Not on a ship - set world gravity
            shipGravity.setShipGravityEnabled(false);
            GravityChangerAPI.setBaseGravityDirection(entity, shipRelativeDirection);
        }
    }
    
    /**
     * Set gravity relative to a specific ship (even if the entity is not currently on it).
     * The gravity will be updated each tick to match the ship's rotation.
     * 
     * @param entity The entity to change gravity for
     * @param shipId The ID of the ship to use for relative gravity
     * @param shipRelativeDirection The gravity direction relative to the ship
     */
    public static void setGravityRelativeToShip(Entity entity, long shipId, Direction shipRelativeDirection) {
        if (!isAvailable()) {
            return;
        }
        
        IShipGravityCapability shipGravity = getShipGravityCapability(entity);
        if (shipGravity == null) {
            return;
        }
        
        shipGravity.setShipId(shipId);
        shipGravity.setShipRelativeGravityDirection(shipRelativeDirection);
        shipGravity.setShipGravityEnabled(true);
    }
    
    /**
     * Disable ship-relative gravity and optionally reset to world down
     * 
     * @param entity The entity to reset gravity for
     * @param resetToWorldDown If true, resets gravity to world DOWN; if false, keeps current gravity direction
     */
    public static void disableShipGravity(Entity entity, boolean resetToWorldDown) {
        if (!isAvailable()) {
            if (resetToWorldDown) {
                GravityChangerAPI.setBaseGravityDirection(entity, Direction.DOWN);
            }
            return;
        }
        
        IShipGravityCapability shipGravity = getShipGravityCapability(entity);
        if (shipGravity != null) {
            shipGravity.setShipGravityEnabled(false);
            shipGravity.setShipId(null);
        }
        
        if (resetToWorldDown) {
            GravityChangerAPI.setBaseGravityDirection(entity, Direction.DOWN);
        }
    }
    
    /**
     * Enable automatic gravity alignment when stepping on ships.
     * When enabled, the entity's gravity will automatically align to the ship's orientation
     * when they step onto a ship.
     */
    public static void enableAutoAlign(Entity entity) {
        if (!isAvailable()) {
            return;
        }
        
        IShipGravityCapability shipGravity = getShipGravityCapability(entity);
        if (shipGravity != null) {
            shipGravity.setAutoAlignEnabled(true);
        }
    }
    
    /**
     * Disable automatic gravity alignment
     */
    public static void disableAutoAlign(Entity entity) {
        if (!isAvailable()) {
            return;
        }
        
        IShipGravityCapability shipGravity = getShipGravityCapability(entity);
        if (shipGravity != null) {
            shipGravity.setAutoAlignEnabled(false);
        }
    }
    
    /**
     * Check if automatic gravity alignment is enabled for an entity
     */
    public static boolean isAutoAlignEnabled(Entity entity) {
        if (!isAvailable()) {
            return false;
        }
        
        IShipGravityCapability shipGravity = getShipGravityCapability(entity);
        return shipGravity != null && shipGravity.isAutoAlignEnabled();
    }
    
    /**
     * Get the current ship-relative gravity direction for an entity
     * 
     * @return The ship-relative direction, or null if ship gravity is not enabled
     */
    @Nullable
    public static Direction getShipRelativeGravityDirection(Entity entity) {
        if (!isAvailable()) {
            return null;
        }
        
        IShipGravityCapability shipGravity = getShipGravityCapability(entity);
        if (shipGravity != null && shipGravity.isShipGravityEnabled()) {
            return shipGravity.getShipRelativeGravityDirection();
        }
        return null;
    }
    
    /**
     * Check if ship-relative gravity is currently active for an entity
     */
    public static boolean isShipGravityActive(Entity entity) {
        if (!isAvailable()) {
            return false;
        }
        
        IShipGravityCapability shipGravity = getShipGravityCapability(entity);
        return shipGravity != null && shipGravity.isShipGravityEnabled();
    }
    
    /**
     * Get the ship's "up" direction in world space.
     * Useful for determining which world direction corresponds to the ship's up.
     * 
     * @return The ship's up direction in world space, or null if not on a ship
     */
    @Nullable
    public static Vec3 getShipUpInWorld(Entity entity) {
        return VS2Integration.getShipUpDirection(entity);
    }
    
    /**
     * Convert a world-space direction to ship-local space
     */
    @Nullable
    public static Vec3 worldToShipDirection(Entity entity, Vec3 worldDir) {
        return VS2Integration.worldToShipDirection(entity, worldDir);
    }
    
    /**
     * Convert a ship-local direction to world space
     */
    @Nullable
    public static Vec3 shipToWorldDirection(Entity entity, Vec3 shipDir) {
        return VS2Integration.shipToWorldDirection(entity, shipDir);
    }
}

