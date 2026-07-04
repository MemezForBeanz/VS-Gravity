package com.min01.gravityapi.compat.vs2.block;

import com.min01.gravityapi.api.GravityDirection;
import com.min01.gravityapi.api.RotationParameters;
import com.min01.gravityapi.compat.vs2.VS2Helper;
import com.min01.gravityapi.compat.vs2.VS2Integration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for active gravity generators.
 * Allows entities to efficiently query if they're in a gravity field.
 */
public class GravityGeneratorRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("GravityGeneratorRegistry");

    /**
     * Field radius constant (matches GravityGeneratorBlockEntity)
     */
    public static final double FIELD_RADIUS = 20.0;

    /**
     * Gravity priority constant (matches GravityGeneratorBlockEntity)
     */
    public static final double GRAVITY_PRIORITY = 50.0;

    /**
     * Map of level -> set of active gravity generator positions
     */
    private static final Map<Level, Set<BlockPos>> ACTIVE_GENERATORS = new ConcurrentHashMap<>();

    /**
     * Register a gravity generator as active
     */
    public static void registerGenerator(Level level, BlockPos pos) {
        ACTIVE_GENERATORS.computeIfAbsent(level, k -> ConcurrentHashMap.newKeySet()).add(pos);
    }

    /**
     * Unregister a gravity generator (when disabled or removed)
     */
    public static void unregisterGenerator(Level level, BlockPos pos) {
        Set<BlockPos> generators = ACTIVE_GENERATORS.get(level);
        if (generators != null) {
            generators.remove(pos);
        }
    }

    /**
     * Clear all generators for a level (when level unloads)
     */
    public static void clearLevel(Level level) {
        ACTIVE_GENERATORS.remove(level);
    }

    /**
     * Result of checking for gravity effect - now supports arbitrary gravity direction
     */
    public record GravityFieldResult(GravityDirection gravityDirection, RotationParameters parameters, double priority) {
        /**
         * Get the nearest cardinal direction for backward compatibility
         */
        public Direction direction() {
            return gravityDirection.getNearestDirection();
        }
    }

    /**
     * Check if an entity is in a gravity field and get the gravity direction.
     * This is called during the entity's gravity update event.
     *
     * @param entity The entity to check
     * @return The gravity field result if in a field, null otherwise
     */
    @Nullable
    public static GravityFieldResult getGravityFieldEffect(Entity entity) {
        Level level = entity.level();
        Set<BlockPos> generators = ACTIVE_GENERATORS.get(level);

        // Only log for players to reduce spam
        boolean shouldLog = (entity instanceof Player) && (entity.tickCount % 100 == 0);

        if (generators == null || generators.isEmpty()) {
            if (shouldLog) {
                LOGGER.info("[Registry] No active generators for level");
            }
            return null;
        }

        if (shouldLog) {
            LOGGER.info("[Registry] Checking {} generators for entity {} at {}",
                    generators.size(), entity.getName().getString(), entity.position());
        }

        Vec3 entityPos = entity.position();
        GravityFieldResult bestResult = null;
        double closestDistanceSq = Double.MAX_VALUE;

        // Create a copy to avoid ConcurrentModificationException during cleanup
        List<BlockPos> toRemove = new ArrayList<>();

        for (BlockPos generatorPos : generators) {
            BlockEntity blockEntity = level.getBlockEntity(generatorPos);
            if (blockEntity == null || !(blockEntity instanceof GravityGeneratorBlockEntity)) {
                // Generator no longer exists, mark for cleanup
                toRemove.add(generatorPos);
                continue;
            }

            GravityGeneratorBlockEntity generator = (GravityGeneratorBlockEntity) blockEntity;

            // Get the effective world position of the generator
            Vec3 generatorWorldPos = getGeneratorWorldPosition(generator, generatorPos);
            if (generatorWorldPos == null) {
                if (shouldLog) {
                    LOGGER.warn("[Registry] Could not get world position for generator at {}", generatorPos);
                }
                continue;
            }

            // Check distance
            double distanceSq = entityPos.distanceToSqr(generatorWorldPos);
            double maxRangeSq = FIELD_RADIUS * FIELD_RADIUS;

            if (shouldLog) {
                LOGGER.info("[Registry] Generator at {} (world pos {}), distance^2: {}, max: {}",
                        generatorPos, generatorWorldPos, distanceSq, maxRangeSq);
            }

            if (distanceSq <= maxRangeSq && distanceSq < closestDistanceSq) {
                // Entity is in range of this generator
                GravityDirection gravityDir = getGeneratorGravityDirection(generator, generatorPos);
                if (gravityDir != null) {
                    if (shouldLog) {
                        LOGGER.info("[Registry] Entity in range! Gravity direction: {}", gravityDir);
                    }
                    bestResult = new GravityFieldResult(
                            gravityDir,
                            RotationParameters.getDefault(),
                            GRAVITY_PRIORITY
                    );
                    closestDistanceSq = distanceSq;
                }
            }
        }

        // Clean up stale entries
        for (BlockPos pos : toRemove) {
            generators.remove(pos);
        }

        if (shouldLog && bestResult != null) {
            LOGGER.info("[Registry] Returning gravity effect: gravityDirection={}, isCardinal={}, priority={}",
                    bestResult.gravityDirection(), bestResult.gravityDirection().isCardinal(), bestResult.priority());
        }

        return bestResult;
    }

    /**
     * Get the world position of a gravity generator, accounting for ship transformation.
     */
    @Nullable
    private static Vec3 getGeneratorWorldPosition(GravityGeneratorBlockEntity generator, BlockPos pos) {
        Level level = generator.getLevel();
        if (level == null) {
            return null;
        }

        Vec3 blockCenter = Vec3.atCenterOf(pos);

        if (generator.isOnShip() && VS2Integration.isVS2Loaded()) {
            // Transform from ship-local to world coordinates
            return VS2Helper.shipToWorldPosition(level, pos, blockCenter);
        }

        return blockCenter;
    }

    /**
     * Get the gravity direction for a generator, accounting for ship orientation.
     * Returns a GravityDirection that may be an arbitrary angle (not just cardinal directions).
     */
    @Nullable
    private static GravityDirection getGeneratorGravityDirection(GravityGeneratorBlockEntity generator, BlockPos pos) {
        Level level = generator.getLevel();
        if (level == null) {
            return GravityDirection.DOWN;
        }

        if (generator.isOnShip() && VS2Integration.isVS2Loaded()) {
            // Transform ship's "down" to world space
            Vec3 shipDown = new Vec3(0, -1, 0);
            Vec3 worldDown = VS2Helper.shipToWorldDirectionFromBlock(level, pos, shipDown);

            if (worldDown != null) {
                // Return the actual vector for arbitrary angles
                return GravityDirection.fromVector(worldDown);
            }
        }

        return GravityDirection.DOWN;
    }
}
