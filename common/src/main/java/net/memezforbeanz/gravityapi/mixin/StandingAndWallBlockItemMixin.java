package net.memezforbeanz.gravityapi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.memezforbeanz.gravityapi.placement.*;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(StandingAndWallBlockItem.class)
public abstract class StandingAndWallBlockItemMixin {
    @Shadow @Final protected Block wallBlock;
    @Shadow @Final private Direction attachmentDirection;
    @Shadow protected abstract boolean canPlace(LevelReader level, BlockState state, net.minecraft.core.BlockPos pos);

    @WrapMethod(method = "getPlacementState")
    private BlockState gravityapi$variant(BlockPlaceContext context, Operation<BlockState> original) {
        PlacementFrame frame = PlacementFrame.current();
        if (frame == null) return original.call(context);
        Block standing = ((BlockItem) (Object) this).getBlock();
        if (standing.defaultBlockState().is(PlacementAdapters.BLACKLIST)
                || wallBlock.defaultBlockState().is(PlacementAdapters.BLACKLIST)) return original.call(context);
        frame.resolve(context);
        PlacementContext grid = new PlacementContext(context, frame, false);
        java.util.function.Supplier<BlockState> nativePlacement = () -> {
            BlockState state = frame.withInputs(false, () -> original.call(grid));
            return state != null && state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
        };
        if (frame.rotation == null || frame.rotation.isIdentity() || PlacementAdapters.nativeOnly(standing.defaultBlockState(), context)
                || PlacementAdapters.nativeOnly(wallBlock.defaultBlockState(), context)) {
            return nativePlacement.get();
        }
        if (standing instanceof net.memezforbeanz.gravityapi.api.IGravityPlacementAware aware) {
            PlacementContext local = new PlacementContext(context, frame, true);
            BlockState proposed = frame.withInputs(true, () -> standing.getStateForPlacement(local));
            BlockState mapped = proposed == null ? null : frame.withInputs(true,
                    () -> aware.mapGravityPlacement(grid, frame.rotation, proposed));
            if (gravityapi$valid(context, mapped)) return mapped;
            return nativePlacement.get();
        }
        if (net.memezforbeanz.gravityapi.config.GravityServerConfig.rotationLayer() && frame.rotation.y() != Direction.UP) {
            // Vanilla's own standing/wall choice, run in the local frame: survival and collision see
            // the prospective rotation, so a local wall click yields a wall variant on that face.
            PlacementContext local = new PlacementContext(context, frame, true);
            BlockState proposal = net.memezforbeanz.gravityapi.rotation.RotationScope.with(context.getLevel(), context.getClickedPos(),
                    frame.rotation, () -> frame.withInputs(true, () -> original.call(local)));
            BlockState layered = net.memezforbeanz.gravityapi.rotation.RotationPlacement.layer(proposal, context, frame);
            if (layered != null && net.memezforbeanz.gravityapi.rotation.RotationScope.with(context.getLevel(), context.getClickedPos(), frame.layerRotation,
                    () -> gravityapi$valid(context, layered))) return layered;
            frame.layerRotation = null;
            return nativePlacement.get();
        }
        if (attachmentDirection != Direction.DOWN) return nativePlacement.get();
        Direction up = frame.rotation.y();
        BlockState candidate = null;
        if (up == Direction.UP) {
            candidate = frame.withInputs(false, () -> standing.getStateForPlacement(grid));
        } else if (up.getAxis().isHorizontal()) {
            // A wall variant's outward normal is player-up; its support is the opposite face.
            PlacementContext wall = new PlacementContext(context, frame, false) {
                @Override public Direction[] getNearestLookingDirections() { return new Direction[] {up.getOpposite()}; }
            };
            candidate = frame.withInputs(false, () -> wallBlock.getStateForPlacement(wall));
        }
        if (gravityapi$valid(context, candidate)) return candidate;
        return nativePlacement.get();
    }

    private boolean gravityapi$valid(BlockPlaceContext context, BlockState state) {
        return state != null && state.canSurvive(context.getLevel(), context.getClickedPos())
                && canPlace(context.getLevel(), state, context.getClickedPos())
                && context.getLevel().isUnobstructed(state, context.getClickedPos(), CollisionContext.empty());
    }
}
