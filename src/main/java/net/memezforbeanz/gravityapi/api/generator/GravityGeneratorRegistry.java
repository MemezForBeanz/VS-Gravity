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
 * Registry of active gravity generators. Each {@link AbstractGravityGeneratorBlockEntity}
 * registers while active; entities query it every tick. Radius, priority, rotation parameters
 * and direction come from the generator.
 */
public class GravityGeneratorRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("GravityGeneratorRegistry");

    /** Active generator positions per level (client and server levels are separate objects). */
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
     * The gravity field affecting an entity, or null; the closest generator wins where fields overlap.
     * Called from the entity's gravity update.
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

        List<BlockPos> toRemove = new ArrayList<>();

        for (BlockPos generatorPos : generators) {
            BlockEntity blockEntity = level.getBlockEntity(generatorPos);
            if (!(blockEntity instanceof AbstractGravityGeneratorBlockEntity generator)) {
                toRemove.add(generatorPos);
                continue;
            }

            if (!generator.isGeneratorActive() || !generator.canAffect(entity)) {
                continue;
            }

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

        for (BlockPos pos : toRemove) {
            generators.remove(pos);
        }

        return bestResult;
    }
}
