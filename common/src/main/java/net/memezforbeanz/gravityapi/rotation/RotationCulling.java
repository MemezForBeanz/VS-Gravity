package net.memezforbeanz.gravityapi.rotation;

import net.minecraft.core.*;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.*;

public final class RotationCulling {
    private RotationCulling() {}
    public static boolean affected(BlockGetter level, BlockPos pos, BlockPos neighbour) {
        return RotationLookup.get(level, pos) != null || RotationLookup.get(level, neighbour) != null;
    }
    public static boolean visible(BlockState state, BlockGetter level, BlockPos pos, Direction face, BlockPos neighbour) {
        BlockState other = level.getBlockState(neighbour);
        var ownRotation = RotationLookup.get(level, pos);
        var otherRotation = RotationLookup.get(level, neighbour);
        if (java.util.Objects.equals(ownRotation, otherRotation)
                && state.skipRendering(other, ownRotation == null ? face : ownRotation.inverse(face))) return false;
        if (!other.canOcclude()) return true;
        VoxelShape own = state.getFaceOcclusionShape(level, pos, face);
        if (own.isEmpty()) return true;
        VoxelShape theirs = other.getFaceOcclusionShape(level, neighbour, face.getOpposite());
        return Shapes.joinIsNotEmpty(own, theirs, BooleanOp.ONLY_FIRST);
    }
}
