package net.memezforbeanz.gravityapi.rotation;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.memezforbeanz.gravityapi.placement.CubeRotation;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.*;

public final class RotationShapes {
    private static final Cache<VoxelShape, VoxelShape[]> CACHE = CacheBuilder.newBuilder().weakKeys().maximumSize(4096).build();
    private RotationShapes() {}
    public static VoxelShape rotate(VoxelShape shape, CubeRotation r) {
        if (r == null || r.isIdentity() || shape.isEmpty() || shape == Shapes.block()) return shape;
        VoxelShape[] variants;
        synchronized (CACHE) {
            variants = CACHE.getIfPresent(shape);
            if (variants == null) { variants = new VoxelShape[24]; CACHE.put(shape, variants); }
        }
        synchronized (variants) {
            if (variants[r.index()] != null) return variants[r.index()];
            VoxelShape result = Shapes.empty();
            for (var box : shape.toAabbs()) {
                Vec3 a = r.apply(new Vec3(box.minX-.5, box.minY-.5, box.minZ-.5));
                Vec3 b = r.apply(new Vec3(box.maxX-.5, box.maxY-.5, box.maxZ-.5));
                result = Shapes.or(result, Shapes.box(Math.min(a.x,b.x)+.5, Math.min(a.y,b.y)+.5, Math.min(a.z,b.z)+.5,
                        Math.max(a.x,b.x)+.5, Math.max(a.y,b.y)+.5, Math.max(a.z,b.z)+.5));
            }
            return variants[r.index()] = result.optimize();
        }
    }
}
