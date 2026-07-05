package net.memezforbeanz.gravityapi.api.generator;

import net.memezforbeanz.gravityapi.api.GravityDirection;
import net.memezforbeanz.gravityapi.api.RotationParameters;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for active gravity generators.
 * <p>
 * Any {@link AbstractGravityGeneratorBlockEntity} registers itself here while active;
 * entities query it every tick to find the gravity field affecting them. Field radius,
 * priority, rotation parameters and gravity direction all come from the individual
 * generator, so mods extending the abstract classes get their overrides applied
 * automatically.
 */
public class GravityGeneratorRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("GravityGeneratorRegistry");

    /**
     * Map of level -> set of active gravity generator positions.
     * Maintained separately per logical side (server/client levels are distinct objects).
     */
    private static final Map<Level, Set<BlockPos>> ACTIVE_GENERATORS = new ConcurrentHashMap<>();

    /**
     * Register a gravity generator as active.
     */
    public static void registerGenerator(Level level, BlockPos pos) {
        ACTIVE_GENERATORS.computeIfAbsent(level, k -> ConcurrentHashMap.newKeySet()).add(pos);
    }

    /**
     * Unregister a gravity generator (when disabled or removed).
     */
    public static void unregisterGenerator(Level level, BlockPos pos) {
        Set<BlockPos> generators = ACTIVE_GENERATORS.get(level);
        if (generators != null) {
            generators.remove(pos);
        }
    }

    /**
     * Clear all generators for a level (when level unloads).
     */
    public static void clearLevel(Level level) {
        ACTIVE_GENERATORS.remove(level);
    }

    /**
     * Result of checking for gravity effect - supports arbitrary gravity directions.
     */
    public record GravityFieldResult(GravityDirection gravityDirection, RotationParameters parameters, double priority) {
        /**
         * Get the nearest cardinal direction for backward compatibility.
         */
        public Direction direction() {
            return gravityDirection.getNearestDirection();
        }
    }

    /**
     * Check if an entity is in a gravity field and get the gravity direction.
     * This is called during the entity's gravity update event.
     * When multiple fields overlap, the closest generator wins.
     *
     * @param entity The entity to check
     * @return The gravity field result if in a field, null otherwise
     */
    @Nullable
    public static GravityFieldResult getGravityFieldEffect(Entity entity) {
        Level level = entity.level();
        Set<BlockPos> generators = ACTIVE_GENERATORS.get(level);

        if (generators == null || generators.isEmpty()) {
            return null;
        }

        Vec3 entityPos = entity.position();
        GravityFieldResult bestResult = null;
        double closestDistanceSq = Double.MAX_VALUE;

        // Collect stale entries for cleanup after iteration
        List<BlockPos> toRemove = new ArrayList<>();

        for (BlockPos generatorPos : generators) {
            BlockEntity blockEntity = level.getBlockEntity(generatorPos);
            if (!(blockEntity instanceof AbstractGravityGeneratorBlockEntity generator)) {
                // Generator no longer exists, mark for cleanup
                toRemove.add(generatorPos);
                continue;
            }

            if (!generator.isGeneratorActive() || !generator.canAffect(entity)) {
                continue;
            }

            // Get the effective world position of the generator (ship-aware)
            Vec3 generatorWorldPos = generator.getEffectiveWorldPosition();
            if (generatorWorldPos == null) {
                LOGGER.debug("[Registry] Could not get world position for generator at {}", generatorPos);
                continue;
            }

            double distanceSq = entityPos.distanceToSqr(generatorWorldPos);

            if (distanceSq < closestDistanceSq && generator.isInField(entity)) {
                GravityDirection gravityDir = generator.computeGravityDirection();
                if (gravityDir != null) {
                    bestResult = new GravityFieldResult(
                            gravityDir,
                            generator.getRotationParameters(),
                            generator.getGravityPriority()
                    );
                    closestDistanceSq = distanceSq;
                }
            }
        }

        // Clean up stale entries
        for (BlockPos pos : toRemove) {
            generators.remove(pos);
        }

        return bestResult;
    }
}
