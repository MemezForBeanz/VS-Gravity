package net.memezforbeanz.gravityapi.rotation;

import net.memezforbeanz.gravityapi.placement.CubeRotation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import java.util.function.Supplier;

/** Prospective rotation for validation and neighbour notifications during the placement write. */
public record RotationScope(Level level, BlockPos pos, CubeRotation rotation) {
    private static final ThreadLocal<RotationScope> CURRENT = new ThreadLocal<>();
    private static final java.util.concurrent.atomic.AtomicInteger ACTIVE = new java.util.concurrent.atomic.AtomicInteger();
    public static RotationScope current() { return ACTIVE.get() != 0 ? CURRENT.get() : null; }
    public static <T> T with(Level level, BlockPos pos, CubeRotation r, Supplier<T> action) {
        RotationScope previous = CURRENT.get(); CURRENT.set(new RotationScope(level, pos.immutable(), r)); ACTIVE.incrementAndGet();
        try { return action.get(); } finally { ACTIVE.decrementAndGet(); if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
}
