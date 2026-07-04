package com.min01.gravityapi.compat.vs2.block;

import com.min01.gravityapi.EntityTags;
import com.min01.gravityapi.api.GravityChangerAPI;
import com.min01.gravityapi.api.RotationParameters;
import com.min01.gravityapi.capabilities.GravityCapabilityImpl;
import com.min01.gravityapi.compat.vs2.VS2Helper;
import com.min01.gravityapi.compat.vs2.VS2Integration;
import com.min01.gravityapi.init.GravityBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Block entity for the Gravity Generator.
 * Handles the gravitational field logic, including ship-relative gravity.
 */
public class GravityGeneratorBlockEntity extends BlockEntity {
    
    private static final Logger GRAVITY_LOG = LoggerFactory.getLogger("GravityGenerator");

    /**
     * The radius of the gravitational field in blocks.
     */
    public static final double FIELD_RADIUS = 20.0;
    
    /**
     * Priority for the gravity effect (higher = overrides other effects).
     */
    public static final double GRAVITY_PRIORITY = 50.0;
    
    /**
     * Cached ship ID if this block is on a ship.
     */
    @Nullable
    private Long cachedShipId = null;
    
    /**
     * Whether we've checked for ship status this tick.
     */
    private boolean shipCheckDone = false;
    
    /**
     * Tick counter for throttled debug logging.
     */
    private int tickCounter = 0;

    public GravityGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(GravityBlocks.GRAVITY_GENERATOR_BLOCK_ENTITY.get(), pos, state);
    }
    
    /**
     * Track previous active state for registration changes.
     */
    private boolean wasActive = false;

    /**
     * Server tick for the gravity generator.
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, GravityGeneratorBlockEntity blockEntity) {
        if (level.isClientSide()) return;
        
        boolean isActive = state.getValue(GravityGeneratorBlock.ACTIVE);

        // Handle registration state changes
        if (isActive && !blockEntity.wasActive) {
            // Just became active - register
            GravityGeneratorRegistry.registerGenerator(level, pos);
            GRAVITY_LOG.info("[GravityGenerator] Registered at pos {}", pos);
        } else if (!isActive && blockEntity.wasActive) {
            // Just became inactive - unregister
            GravityGeneratorRegistry.unregisterGenerator(level, pos);
            GRAVITY_LOG.info("[GravityGenerator] Unregistered at pos {}", pos);
        }
        blockEntity.wasActive = isActive;

        // Only process if active
        if (!isActive) {
            return;
        }
        
        // Reset ship check cache each tick
        blockEntity.shipCheckDone = false;
        
        // Increment tick counter for debug logging
        blockEntity.tickCounter++;
        boolean shouldLog = (blockEntity.tickCounter % 100 == 0); // Log every 5 seconds

        if (shouldLog) {
            GRAVITY_LOG.info("[GravityGenerator] Active at pos {}, isOnShip: {}, shipId: {}",
                    pos, blockEntity.isOnShip(), blockEntity.getShipId());
        }
    }

    /**
     * Client tick for the gravity generator.
     * Registers the generator in the client-side registry so the local player
     * can properly compute gravity effects for smooth client-side prediction.
     */
    public static void clientTick(Level level, BlockPos pos, BlockState state, GravityGeneratorBlockEntity blockEntity) {
        if (!level.isClientSide()) return;

        boolean isActive = state.getValue(GravityGeneratorBlock.ACTIVE);

        // Handle registration state changes on client
        if (isActive && !blockEntity.wasActive) {
            // Just became active - register in client registry
            GravityGeneratorRegistry.registerGenerator(level, pos);
        } else if (!isActive && blockEntity.wasActive) {
            // Just became inactive - unregister from client registry
            GravityGeneratorRegistry.unregisterGenerator(level, pos);
        }
        blockEntity.wasActive = isActive;

        // Only process if active
        if (!isActive) {
            return;
        }

        // Reset ship check cache each tick
        blockEntity.shipCheckDone = false;

        // Increment tick counter
        blockEntity.tickCounter++;
    }

    /**
     * Check if this block is on a Valkyrien Skies ship.
     */
    public boolean isOnShip() {
        if (!VS2Integration.isVS2Loaded() || level == null) {
            return false;
        }
        
        if (!shipCheckDone) {
            cachedShipId = VS2Helper.getShipIdAtBlock(level, getBlockPos());
            shipCheckDone = true;
        }
        
        return cachedShipId != null;
    }
    
    /**
     * Get the ship ID this block is on, or null if not on a ship.
     */
    @Nullable
    public Long getShipId() {
        isOnShip(); // Ensure cache is populated
        return cachedShipId;
    }
    
    /**
     * Get all entities within the gravitational field.
     * This accounts for ship rotation when the block is on a ship.
     */
    private List<Entity> getEntitiesInGravityField(Level level, BlockPos pos) {
        Vec3 blockCenter = Vec3.atCenterOf(pos);
        Vec3 searchCenter = blockCenter;

        boolean shouldLog = (tickCounter % 100 == 0);

        if (isOnShip() && VS2Integration.isVS2Loaded()) {
            // On a ship - need to transform the field to world coordinates
            // Get the world position of this block (transformed by ship)
            Vec3 worldPos = getWorldPosition(blockCenter);
            if (worldPos != null) {
                searchCenter = worldPos;
                if (shouldLog) {
                    GRAVITY_LOG.info("[GravityGenerator] Block ship pos {} -> world pos {}", blockCenter, worldPos);
                }
            } else {
                if (shouldLog) {
                    GRAVITY_LOG.warn("[GravityGenerator] Failed to transform ship pos {} to world pos!", blockCenter);
                }
            }
        }
        
        // Create an AABB that encompasses the spherical field
        // We use a cube and then filter by distance
        AABB searchBox = new AABB(
                searchCenter.x - FIELD_RADIUS,
                searchCenter.y - FIELD_RADIUS,
                searchCenter.z - FIELD_RADIUS,
                searchCenter.x + FIELD_RADIUS,
                searchCenter.y + FIELD_RADIUS,
                searchCenter.z + FIELD_RADIUS
        );
        
        if (shouldLog) {
            GRAVITY_LOG.info("[GravityGenerator] Search box: {} to {}",
                    new Vec3(searchBox.minX, searchBox.minY, searchBox.minZ),
                    new Vec3(searchBox.maxX, searchBox.maxY, searchBox.maxZ));
        }

        final Vec3 finalSearchCenter = searchCenter;

        // Get all entities in the box
        List<Entity> entities = level.getEntitiesOfClass(Entity.class, searchBox, entity -> {
            if (!EntityTags.canChangeGravity(entity)) {
                return false;
            }
            
            // Check if entity is within the spherical radius
            Vec3 entityPos = entity.position();
            double distanceSq = entityPos.distanceToSqr(finalSearchCenter);

            return distanceSq <= FIELD_RADIUS * FIELD_RADIUS;
        });
        
        return entities;
    }
    
    /**
     * Get the world position of a ship-local position.
     */
    @Nullable
    private Vec3 getWorldPosition(Vec3 shipLocalPos) {
        if (!VS2Integration.isVS2Loaded() || level == null) {
            GRAVITY_LOG.debug("[GravityGenerator] getWorldPosition: VS2 not loaded or level is null");
            return null;
        }
        
        // Use the VS2Helper to check if we're on a ship
        Object ship = VS2Helper.getShipAtBlock(level, getBlockPos());
        if (ship == null) {
            GRAVITY_LOG.debug("[GravityGenerator] getWorldPosition: No ship found at block pos {}", getBlockPos());
            return null;
        }
        
        // Transform ship-local position to world position
        Vec3 result = VS2Helper.shipToWorldPosition(level, getBlockPos(), shipLocalPos);
        if (result == null) {
            GRAVITY_LOG.warn("[GravityGenerator] getWorldPosition: Failed to transform position {} using ship at {}",
                    shipLocalPos, getBlockPos());
        }
        return result;
    }
    
    /**
     * Apply gravity to an entity based on the ship's orientation.
     */
    private void applyGravityToEntity(Entity entity) {
        if (!EntityTags.canChangeGravity(entity)) {
            return;
        }
        
        boolean shouldLog = (tickCounter % 100 == 0);

        Direction gravityDirection;
        Vec3 shipDownVec = null;

        if (isOnShip() && VS2Integration.isVS2Loaded()) {
            // On a ship - calculate world gravity direction based on ship's "down"
            // The ship's "down" in ship space is (0, -1, 0)
            // Transform this to world space to find what direction entities should fall
            Vec3 shipDown = new Vec3(0, -1, 0);
            shipDownVec = VS2Helper.shipToWorldDirectionFromBlock(level, getBlockPos(), shipDown);

            if (shipDownVec != null) {
                // Find the closest cardinal direction to this vector
                gravityDirection = Direction.getNearest((float) shipDownVec.x, (float) shipDownVec.y, (float) shipDownVec.z);

                if (shouldLog) {
                    GRAVITY_LOG.info("[GravityGenerator] Ship down vector in world: {} -> Direction: {}",
                            shipDownVec, gravityDirection);
                }
            } else {
                gravityDirection = Direction.DOWN;
                if (shouldLog) {
                    GRAVITY_LOG.warn("[GravityGenerator] Could not compute ship down direction, defaulting to DOWN");
                }
            }
        } else {
            // Not on a ship - use world down
            gravityDirection = Direction.DOWN;
        }
        
        if (gravityDirection == null) {
            gravityDirection = Direction.DOWN;
        }
        
        if (shouldLog) {
            Direction currentGravity = GravityChangerAPI.getGravityDirection(entity);
            GRAVITY_LOG.info("[GravityGenerator] Entity {} current gravity: {}, applying: {} (priority {})",
                    entity.getName().getString(), currentGravity, gravityDirection, GRAVITY_PRIORITY);
        }

        // Apply gravity effect
        GravityCapabilityImpl gravityComponent = GravityChangerAPI.getGravityComponent(entity);
        gravityComponent.applyGravityDirectionEffect(
                gravityDirection,
                RotationParameters.getDefault(),
                GRAVITY_PRIORITY
        );

        if (shouldLog) {
            Direction newGravity = GravityChangerAPI.getGravityDirection(entity);
            GRAVITY_LOG.info("[GravityGenerator] Entity {} gravity after apply: {}",
                    entity.getName().getString(), newGravity);
        }
    }
    
    /**
     * Get the ship's "down" direction in world space.
     * This is the direction that entities should fall toward when on the ship.
     */
    @Nullable
    private Direction getShipDownDirectionInWorld() {
        if (!VS2Integration.isVS2Loaded() || level == null) {
            return Direction.DOWN;
        }
        
        // Get the ship's down direction (negative Y in ship space) transformed to world space
        Vec3 shipDown = new Vec3(0, -1, 0);
        Vec3 worldDown = VS2Helper.shipToWorldDirectionFromBlock(level, getBlockPos(), shipDown);
        
        if (worldDown == null) {
            return Direction.DOWN;
        }
        
        // Find the closest Direction to this world vector
        return Direction.getNearest((float) worldDown.x, (float) worldDown.y, (float) worldDown.z);
    }
    
    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
    }
    
    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
    }
    
    @Override
    public void setRemoved() {
        super.setRemoved();
        // Unregister from the registry when removed (both client and server)
        if (level != null) {
            GravityGeneratorRegistry.unregisterGenerator(level, getBlockPos());
            if (!level.isClientSide()) {
                GRAVITY_LOG.info("[GravityGenerator] Removed and unregistered at pos {}", getBlockPos());
            }
        }
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
    
    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }
}
