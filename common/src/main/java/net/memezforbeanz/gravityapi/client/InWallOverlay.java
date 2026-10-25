package net.memezforbeanz.gravityapi.client;

import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import net.memezforbeanz.gravityapi.util.RotationUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** The in-wall screen overlay's block check, sampled around the eyes in the player's gravity frame. */
public final class InWallOverlay {
    private InWallOverlay() {}

    /** The view-blocking block and its position, or null if there is none. */
    public static @Nullable Pair<BlockState, BlockPos> find(Player player, Direction gravityDirection) {
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        Vec3 eyePos = player.getEyePosition();
        Vector3f multipliers = RotationUtil.vecPlayerToWorld(player.getBbWidth() * 0.8F, 0.1F, player.getBbWidth() * 0.8F, gravityDirection);
        for (int i = 0; i < 8; ++i) {
            double d = eyePos.x + (double) (((float) ((i >> 0) % 2) - 0.5F) * multipliers.x());
            double e = eyePos.y + (double) (((float) ((i >> 1) % 2) - 0.5F) * multipliers.y());
            double f = eyePos.z + (double) (((float) ((i >> 2) % 2) - 0.5F) * multipliers.z());
            mutable.set(d, e, f);
            BlockState blockState = player.level().getBlockState(mutable);
            if (blockState.getRenderShape() != RenderShape.INVISIBLE && blockState.isViewBlocking(player.level(), mutable)) {
                return Pair.of(blockState, mutable.immutable());
            }
        }
        return null;
    }
}
