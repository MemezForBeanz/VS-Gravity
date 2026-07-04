package com.min01.gravityapi.compat.vs2;

import com.min01.gravityapi.api.GravityChangerAPI;
import com.min01.gravityapi.api.RotationParameters;
import com.min01.gravityapi.capabilities.GravityCapabilityImpl;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Implementation of ship-relative gravity capability.
 * This handles gravity that is relative to a Valkyrien Skies ship's orientation.
 */
public class ShipGravityCapabilityImpl implements IShipGravityCapability {
    
    private Entity entity;
    
    @Nullable
    private Long shipId = null;
    
    private Direction shipRelativeGravityDirection = Direction.DOWN;
    
    private boolean shipGravityEnabled = false;
    
    private boolean autoAlignEnabled = false;
    
    @Nullable
    private Long lastShipId = null;
    
    public ShipGravityCapabilityImpl() {
    }
    
    public void setEntity(Entity entity) {
        this.entity = entity;
    }
    
    @Override
    @Nullable
    public Long getShipId() {
        return shipId;
    }
    
    @Override
    public void setShipId(@Nullable Long shipId) {
        this.shipId = shipId;
    }
    
    @Override
    public Direction getShipRelativeGravityDirection() {
        return shipRelativeGravityDirection;
    }
    
    @Override
    public void setShipRelativeGravityDirection(Direction direction) {
        this.shipRelativeGravityDirection = direction;
    }
    
    @Override
    public boolean isShipGravityEnabled() {
        return shipGravityEnabled;
    }
    
    @Override
    public void setShipGravityEnabled(boolean enabled) {
        this.shipGravityEnabled = enabled;
    }
    
    @Override
    public boolean isAutoAlignEnabled() {
        return autoAlignEnabled;
    }
    
    @Override
    public void setAutoAlignEnabled(boolean enabled) {
        this.autoAlignEnabled = enabled;
    }
    
    @Override
    public void tick() {
        if (entity == null || !VS2Integration.isVS2Loaded()) {
            return;
        }
        
        Long currentShipId = VS2Integration.getShipMountedOn(entity);
        
        // Handle auto-alignment when stepping onto a new ship
        if (autoAlignEnabled && currentShipId != null && !currentShipId.equals(lastShipId)) {
            // Player stepped onto a new ship - align gravity to ship's down direction
            shipId = currentShipId;
            shipGravityEnabled = true;
            shipRelativeGravityDirection = Direction.DOWN;
            applyShipRelativeGravity();
        }
        
        // Handle leaving a ship
        if (autoAlignEnabled && lastShipId != null && currentShipId == null) {
            // Player left the ship - reset to world gravity
            shipGravityEnabled = false;
            shipId = null;
            GravityChangerAPI.setBaseGravityDirection(entity, Direction.DOWN);
        }
        
        lastShipId = currentShipId;
        
        // Apply ship-relative gravity if enabled and on a ship
        if (shipGravityEnabled && shipId != null && currentShipId != null && currentShipId.equals(shipId)) {
            applyShipRelativeGravity();
        }
    }
    
    /**
     * Apply the ship-relative gravity direction to the entity's actual gravity
     */
    public void applyShipRelativeGravity() {
        if (entity == null || !VS2Integration.isVS2Loaded()) {
            return;
        }
        
        // Convert ship-relative gravity direction to world gravity direction
        Direction worldGravity = getWorldGravityFromShipRelative();
        if (worldGravity != null) {
            GravityCapabilityImpl gravityComponent = GravityChangerAPI.getGravityComponent(entity);
            
            // Apply the gravity direction effect with high priority
            gravityComponent.applyGravityDirectionEffect(
                worldGravity,
                RotationParameters.getDefault(),
                100.0 // High priority to override other effects
            );
        }
    }
    
    /**
     * Calculate the world gravity direction based on ship-relative direction
     */
    @Nullable
    public Direction getWorldGravityFromShipRelative() {
        if (!VS2Integration.isVS2Loaded() || entity == null) {
            return shipRelativeGravityDirection;
        }
        
        // Get the ship-relative direction as a vector
        Vec3 shipRelativeVec = Vec3.atLowerCornerOf(shipRelativeGravityDirection.getNormal());
        
        // Transform to world space
        Vec3 worldVec = VS2Integration.shipToWorldDirection(entity, shipRelativeVec);
        if (worldVec == null) {
            return shipRelativeGravityDirection;
        }
        
        // Find the closest Direction to this world vector
        return Direction.getNearest((float) worldVec.x, (float) worldVec.y, (float) worldVec.z);
    }
    
    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("shipGravityEnabled", shipGravityEnabled);
        tag.putBoolean("autoAlignEnabled", autoAlignEnabled);
        tag.putString("shipRelativeGravityDirection", shipRelativeGravityDirection.getName());
        if (shipId != null) {
            tag.putLong("shipId", shipId);
        }
        return tag;
    }
    
    @Override
    public void deserializeNBT(CompoundTag tag) {
        shipGravityEnabled = tag.getBoolean("shipGravityEnabled");
        autoAlignEnabled = tag.getBoolean("autoAlignEnabled");
        
        if (tag.contains("shipRelativeGravityDirection")) {
            shipRelativeGravityDirection = Direction.byName(tag.getString("shipRelativeGravityDirection"));
            if (shipRelativeGravityDirection == null) {
                shipRelativeGravityDirection = Direction.DOWN;
            }
        }
        
        if (tag.contains("shipId")) {
            shipId = tag.getLong("shipId");
        } else {
            shipId = null;
        }
    }
}

