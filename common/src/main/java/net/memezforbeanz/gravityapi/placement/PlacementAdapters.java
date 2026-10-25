package net.memezforbeanz.gravityapi.placement;

import net.memezforbeanz.gravityapi.api.IGravityPlacementAware;
import net.memezforbeanz.gravityapi.mixin.StairBlockAccessor;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EndRodBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

import java.lang.reflect.Method;
import java.util.function.Function;

/** Property dispatch is universal; only neighbour and secondary-placement semantics need exceptions. */
public final class PlacementAdapters {
    public static final TagKey<Block> BLACKLIST = TagKey.create(Registries.BLOCK,
            new ResourceLocation("gravityapi", "omni_placement_blacklist"));

    private static final ClassValue<Boolean> CUSTOM_ITEM_PLACEMENT = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            for (Class<?> c = type; c != null && c != BlockItem.class; c = c.getSuperclass()) {
                for (Method method : c.getDeclaredMethods()) {
                    // Mixin-merged methods (ours included, e.g. gravityapi$valid) are not placement writers.
                    if (method.getName().indexOf('$') >= 0 || method.isSynthetic()) continue;
                    Class<?>[] args = method.getParameterTypes();
                    // Signature checks also work after reobfuscation. Conservative: a custom item
                    // placement writer may place secondary blocks we cannot discover before running it.
                    if (args.length == 2 && args[0] == BlockPlaceContext.class && args[1] == BlockState.class
                            && method.getReturnType() == boolean.class) return true;
                    if (args.length == 1 && args[0] == BlockPlaceContext.class
                            && method.getReturnType() == net.minecraft.world.InteractionResult.class) return true;
                }
            }
            return false;
        }
    };

    private PlacementAdapters() {}

    public static boolean nativeOnly(BlockState state, BlockPlaceContext context) {
        if (state.is(BLACKLIST)) return true;
        if (state.getBlock() instanceof IGravityPlacementAware) return false;
        return PlacementProperties.secondaryPositions(state)
                || PlacementProperties.find(state, RailShape.class) != null
                || (PlacementProperties.find(state, BellAttachType.class) != null
                    && !net.memezforbeanz.gravityapi.config.GravityServerConfig.rotationLayer())
                || (context.getItemInHand().getItem() instanceof BlockItem item
                    && CUSTOM_ITEM_PLACEMENT.get(item.getClass()));
    }

    public static BlockState select(Block block, BlockPlaceContext context, Function<BlockPlaceContext, BlockState> select) {
        PlacementFrame frame = PlacementFrame.current();
        if (frame == null) return select.apply(context);
        frame.resolve(context);
        frame.fallback = null;
        frame.layerRotation = null;
        if (block.defaultBlockState().is(BLACKLIST)) return select.apply(context);
        BlockPlaceContext grid = new PlacementContext(context, frame, false);
        BlockState selectedNative = frame.withInputs(false, () -> select.apply(grid));
        BlockState nativeState = selectedNative != null && selectedNative.canSurvive(context.getLevel(), context.getClickedPos())
                ? selectedNative : null;
        CubeRotation r = frame.rotation;
        if (r == null || r.isIdentity() || nativeOnly(block.defaultBlockState(), context)) return nativeState;
        if (PlacementProperties.find(block.defaultBlockState(), SlabType.class) != null
                && context.getLevel().getBlockState(context.getClickedPos()).is(block)) return nativeState;
        BlockPlaceContext local = new PlacementContext(context, frame, true);
        BlockState proposed = frame.withInputs(true, () -> block instanceof IGravityPlacementAware ? select.apply(local)
                : net.memezforbeanz.gravityapi.rotation.RotationScope.with(context.getLevel(), context.getClickedPos(), r, () -> select.apply(local)));
        if (!(block instanceof IGravityPlacementAware) && proposed != null && nativeOnly(proposed, context)) return nativeState;
        if (!(block instanceof IGravityPlacementAware) && net.memezforbeanz.gravityapi.config.GravityServerConfig.rotationLayer()) {
            BlockState existing = context.getLevel().getBlockState(context.getClickedPos());
            CubeRotation stored = net.memezforbeanz.gravityapi.rotation.RotationLookup.get(context.getLevel(), context.getClickedPos());
            if (existing.is(block) && stored != null) {
                BlockPlaceContext replacement = net.memezforbeanz.gravityapi.rotation.RotationInteraction.local(context, stored);
                BlockState merged = frame.withInputs(true, () -> select.apply(replacement));
                if (merged != null) { frame.layerRotation = stored; return merged; }
            }
            // Bells' selector checks real supports before returning a state. A floor bell is the
            // local-frame proposal; survival below checks the transformed support instead.
            if (proposed == null && block instanceof net.minecraft.world.level.block.BellBlock) {
                proposed = block.defaultBlockState().setValue(net.minecraft.world.level.block.BellBlock.ATTACHMENT, BellAttachType.FLOOR)
                        .setValue(net.minecraft.world.level.block.BellBlock.FACING, local.getHorizontalDirection());
            }
            if (proposed != null && !net.memezforbeanz.gravityapi.rotation.RotationPlacement.exact(proposed, r)) {
                BlockState layered = net.memezforbeanz.gravityapi.rotation.RotationPlacement.layer(proposed, context, frame);
                return layered != null ? layered : nativeState;
            }
        }
        // Chest joining and end-rod reversal query real neighbours whose states are grid-relative.
        if (!(block instanceof IGravityPlacementAware) && (PlacementProperties.find(block.defaultBlockState(), ChestType.class) != null || block instanceof EndRodBlock)) return nativeState;
        BlockState mapped;
        if (block instanceof IGravityPlacementAware aware) {
            BlockState proposal = proposed;
            mapped = proposal == null ? null : frame.withInputs(true, () -> aware.mapGravityPlacement(grid, r, proposal));
        } else {
            Property<SlabType> slab = PlacementProperties.find(block.defaultBlockState(), SlabType.class);
            // Replacements are evaluated with the physical context. Never remap or lose a merge.
            if (slab != null && (context.getLevel().getBlockState(context.getClickedPos()).is(block)
                    || (nativeState != null && nativeState.getValue(slab) == SlabType.DOUBLE))) return nativeState;
            if (proposed == null && PlacementProperties.find(block.defaultBlockState(), AttachFace.class) == null) return nativeState;
            mapped = proposed == null ? null : PlacementProperties.map(proposed, nativeState, frame);
            if (PlacementProperties.find(block.defaultBlockState(), AttachFace.class) != null
                    && (mapped == null || !mapped.canSurvive(context.getLevel(), context.getClickedPos()))) {
                mapped = attachment(proposed != null ? proposed : nativeState != null ? nativeState : block.defaultBlockState(),
                        local, nativeState, frame);
            }
            if (mapped != null && block instanceof FenceGateBlock) {
                // Mirrors FenceGateBlock#getStateForPlacement for the final, grid-relative facing.
                Direction.Axis axis = mapped.getValue(FenceGateBlock.FACING).getAxis();
                net.minecraft.core.BlockPos pos = context.getClickedPos();
                net.minecraft.world.level.Level level = context.getLevel();
                boolean inWall = axis == Direction.Axis.Z
                        ? isWall(level.getBlockState(pos.west())) || isWall(level.getBlockState(pos.east()))
                        : isWall(level.getBlockState(pos.north())) || isWall(level.getBlockState(pos.south()));
                mapped = mapped.setValue(FenceGateBlock.IN_WALL, inWall);
            }
            if (mapped != null && block instanceof StairBlock) {
                mapped = mapped.setValue(StairBlock.SHAPE, StairBlockAccessor.gravityapi$getStairsShape(
                        mapped, context.getLevel(), context.getClickedPos()));
            }
        }
        if (mapped == null || mapped.is(BLACKLIST) || !mapped.canSurvive(context.getLevel(), context.getClickedPos())) return nativeState;
        // BlockItem's normal canPlace checks collision (including VS2). Its wrapper retries the
        // saved native state if those checks reject the mapped state.
        if (!mapped.equals(nativeState)) frame.fallback = nativeState;
        return mapped;
    }

    private static boolean isWall(BlockState state) {
        return state.is(net.minecraft.tags.BlockTags.WALLS);
    }

    private static BlockState attachment(BlockState base, BlockPlaceContext local, BlockState nativeState, PlacementFrame frame) {
        Property<AttachFace> face = PlacementProperties.find(base, AttachFace.class);
        Property<Direction> heading = PlacementProperties.horizontal(base);
        if (heading == null) return null;
        for (Direction direction : local.getNearestLookingDirections()) {
            AttachFace attach = direction == Direction.UP ? AttachFace.CEILING
                    : direction == Direction.DOWN ? AttachFace.FLOOR : AttachFace.WALL;
            Direction facing = direction.getAxis() == Direction.Axis.Y ? local.getHorizontalDirection() : direction.getOpposite();
            if (!face.getPossibleValues().contains(attach) || !heading.getPossibleValues().contains(facing)) continue;
            BlockState candidate = PlacementProperties.map(base.setValue(face, attach).setValue(heading, facing), nativeState, frame);
            if (candidate != null && candidate.canSurvive(local.getLevel(), local.getClickedPos())) return candidate;
        }
        return null;
    }
}
