package net.memezforbeanz.gravityapi.rotation;

import net.memezforbeanz.gravityapi.placement.CubeRotation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

public final class RotationLookup {
    private RotationLookup() {}
    public static Level level(BlockGetter getter) {
        return getter instanceof Level level ? level : getter instanceof RotationView view ? view.gravityapi$rotationLevel() : null;
    }
    public static RotationStorage storage(Level level, BlockPos pos) {
        if (level == null) return null;
        // getChunkNow never blocks; the server's getChunk joins the main thread when called off it.
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null && level.getChunkSource() instanceof net.minecraft.server.level.ServerChunkCache server
                && !level.getServer().isSameThread()) {
            // Off the main thread getChunkNow is always null; the lighting accessor is thread-safe.
            var light = server.getChunkForLighting(pos.getX() >> 4, pos.getZ() >> 4);
            if (light instanceof net.minecraft.world.level.chunk.ImposterProtoChunk imposter) light = imposter.getWrapped();
            if (light instanceof LevelChunk full) chunk = full;
        }
        return chunk != null ? ((RotationChunk) chunk).gravityapi$rotations() : null;
    }
    public static CubeRotation get(BlockGetter getter, BlockPos pos) {
        Level level = level(getter);
        if (level == null) return null;
        RotationScope scope = RotationScope.current();
        if (scope != null && scope.level() == level && scope.pos().equals(pos))
            return scope.rotation() == null || scope.rotation().isIdentity() ? null : scope.rotation();
        if (!((RotationLevel) level).gravityapi$hasRotations()) return null;
        RotationStorage storage = storage(level, pos);
        return storage == null || storage.isEmpty() ? null : storage.get(pos);
    }
    public static void set(Level level, BlockPos pos, CubeRotation rotation) {
        RotationStorage storage = storage(level, pos);
        if (storage != null) storage.set(pos, rotation, true);
    }
}
