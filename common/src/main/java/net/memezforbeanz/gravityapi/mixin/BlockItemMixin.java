package net.memezforbeanz.gravityapi.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.memezforbeanz.gravityapi.placement.*;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
    @Shadow protected abstract boolean canPlace(BlockPlaceContext context, BlockState state);

    @WrapMethod(method = "getPlacementState")
    private BlockState gravityapi$validatedFallback(BlockPlaceContext context, Operation<BlockState> original) {
        PlacementFrame frame = PlacementFrame.current();
        if (frame != null) {
            frame.fallback = null;
            frame.rejectedByCanPlace = false;
        }
        BlockState result = original.call(context);
        if (result == null && frame != null && frame.rejectedByCanPlace && frame.fallback != null) {
            BlockState fallback = frame.fallback;
            frame.fallback = null;
            frame.layerRotation = null;
            if (fallback.canSurvive(context.getLevel(), context.getClickedPos()) && canPlace(context, fallback)) return fallback;
        }
        return result;
    }

    @WrapOperation(method = "getPlacementState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/BlockItem;canPlace(Lnet/minecraft/world/item/context/BlockPlaceContext;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean gravityapi$recordValidation(BlockItem item, BlockPlaceContext context, BlockState state,
                                                Operation<Boolean> original) {
        PlacementFrame frame = PlacementFrame.current();
        boolean valid = frame != null && frame.layerRotation != null
                ? net.memezforbeanz.gravityapi.rotation.RotationScope.with(context.getLevel(), context.getClickedPos(), frame.layerRotation,
                    () -> original.call(item, context, state)) : original.call(item, context, state);
        if (frame != null && !valid && frame.fallback != null) frame.rejectedByCanPlace = true;
        return valid;
    }

    @WrapOperation(method = "place", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/BlockItem;placeBlock(Lnet/minecraft/world/item/context/BlockPlaceContext;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean gravityapi$writeRotation(BlockItem item, BlockPlaceContext context, BlockState state, Operation<Boolean> original) {
        PlacementFrame frame = PlacementFrame.current();
        if (frame == null || frame.layerRotation == null) return original.call(item, context, state);
        return net.memezforbeanz.gravityapi.rotation.RotationScope.with(context.getLevel(), context.getClickedPos(), frame.layerRotation,
                () -> original.call(item, context, state));
    }

    @WrapMethod(method = "place")
    private InteractionResult gravityapi$placementScope(BlockPlaceContext context, Operation<InteractionResult> original) {
        PlacementFrame previous = PlacementFrame.current();
        try {
            PlacementFrame.begin(context);
            return original.call(context);
        } finally {
            try {
                PlacementFrame frame = PlacementFrame.current();
                if (frame != null && context.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player
                        && ((frame.rotation != null && !frame.rotation.isIdentity())
                            || net.memezforbeanz.gravityapi.rotation.RotationLookup.get(context.getLevel(), context.getClickedPos()) != null))
                    net.memezforbeanz.gravityapi.rotation.RotationSync.correctPrediction(player, context.getClickedPos());
            } finally { PlacementFrame.restore(previous); }
        }
    }

    @WrapOperation(method = "getPlacementState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/Block;getStateForPlacement(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState gravityapi$selectState(Block block, BlockPlaceContext context, Operation<BlockState> original) {
        var stored = net.memezforbeanz.gravityapi.rotation.RotationLookup.get(context.getLevel(), context.getClickedPos());
        if (stored != null && context.getLevel().getBlockState(context.getClickedPos()).is(block)
                && block instanceof net.minecraft.world.level.block.SlabBlock) {
            return original.call(block, net.memezforbeanz.gravityapi.rotation.RotationInteraction.local(context, stored));
        }
        return PlacementFrame.current() == null ? original.call(block, context) : PlacementAdapters.select(block, context, input -> original.call(block, input));
    }
}
