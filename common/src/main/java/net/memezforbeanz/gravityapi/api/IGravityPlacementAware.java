package net.memezforbeanz.gravityapi.api;

import net.memezforbeanz.gravityapi.placement.CubeRotation;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Overrides automatic property-based placement for blocks that need custom orientation semantics. */
public interface IGravityPlacementAware {
    /** Proposed state was selected with player-local face, hit and look inputs; context is the physical grid context.
     * Return null to use native placement. The result still receives vanilla placement validation. */
    @Nullable
    BlockState mapGravityPlacement(BlockPlaceContext context, CubeRotation rotation, BlockState proposed);
}
