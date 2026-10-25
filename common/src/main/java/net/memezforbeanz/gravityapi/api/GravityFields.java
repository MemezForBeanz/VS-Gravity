package net.memezforbeanz.gravityapi.api;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;

import org.jetbrains.annotations.Nullable;

import net.memezforbeanz.gravityapi.config.GravityConfig;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Gravity fields as functions of position, for things that cross a field rather than stand in
 * it: projectiles feel the pull wherever they are each tick, and mobs aim by simulating a shot
 * through the same samplers. Field blocks register on both sides while loaded.
 */
public final class GravityFields {

    /** A field's gravity at a point. */
    @FunctionalInterface
    public interface Sampler {
        /**
         * The field's gravitational acceleration at {@code worldPos} in g (a pull direction scaled
         * by the strength, 1.0 = overworld), or null outside the field.
         */
        @Nullable
        Vec3 accelerationAt(Vec3 worldPos);
    }

    private record Entry(Sampler sampler, DoubleSupplier priority) {
    }

    private static final Map<Level, Map<Object, Entry>> FIELDS = Collections.synchronizedMap(new WeakHashMap<>());

    private GravityFields() {
    }

    /** Register (or replace) the field {@code key}; where fields overlap the highest priority wins. */
    public static void register(Level level, Object key, Sampler sampler, DoubleSupplier priority) {
        FIELDS.computeIfAbsent(level, l -> new ConcurrentHashMap<>()).put(key, new Entry(sampler, priority));
    }

    public static void unregister(Level level, Object key) {
        Map<Object, Entry> fields = FIELDS.get(level);
        if (fields != null) {
            fields.remove(key);
        }
    }

    /** Whether any field is registered in the level. */
    public static boolean hasFields(Level level) {
        Map<Object, Entry> fields = FIELDS.get(level);
        return fields != null && !fields.isEmpty();
    }

    /** The winning field's acceleration at {@code pos} in g, or null outside every field. */
    @Nullable
    public static Vec3 fieldAcceleration(Level level, Vec3 pos) {
        Map<Object, Entry> fields = FIELDS.get(level);
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        Vec3 best = null;
        double bestPriority = Double.NEGATIVE_INFINITY;
        for (Entry entry : fields.values()) {
            double priority = entry.priority().getAsDouble();
            if (priority <= bestPriority) {
                continue;
            }
            Vec3 acceleration = entry.sampler().accelerationAt(pos);
            if (acceleration != null) {
                best = acceleration;
                bestPriority = priority;
            }
        }
        return best;
    }

    /** The acceleration at {@code pos} in g: the winning field's, or world down outside fields. */
    public static Vec3 accelerationAt(Level level, Vec3 pos) {
        Vec3 field = fieldAcceleration(level, pos);
        return field != null ? field : new Vec3(0, -GravityConfig.gravityStrengthMultiplier.get(), 0);
    }
}
