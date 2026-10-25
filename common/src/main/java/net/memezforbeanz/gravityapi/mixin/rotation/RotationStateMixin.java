package net.memezforbeanz.gravityapi.mixin.rotation;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.memezforbeanz.gravityapi.placement.CubeRotation;
import net.memezforbeanz.gravityapi.rotation.*;
import net.minecraft.core.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;
import org.spongepowered.asm.mixin.*;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class RotationStateMixin {
    @Shadow protected abstract BlockState asState();
    @Unique private static final ThreadLocal<Integer> gravityapi$shapeDepth = ThreadLocal.withInitial(() -> 0);
    /** Cheap test so unrotated shape queries skip the wrapper and its allocations. */
    @Unique private static boolean gravityapi$rotated(BlockGetter level, BlockPos pos) {
        return LocalNeighbourView.of(level) != null || RotationLookup.get(level, pos) != null;
    }
    @Unique private VoxelShape gravityapi$shape(BlockGetter level, BlockPos pos, java.util.function.BiFunction<BlockGetter, BlockPos, VoxelShape> original) {
        LocalNeighbourView view = LocalNeighbourView.of(level);
        if (view != null) return RotationShapes.rotate(gravityapi$shape(view.level(), view.world(pos), original), view.rotation().inverse());
        CubeRotation r = RotationLookup.get(level, pos);
        if (r == null || gravityapi$shapeDepth.get() != 0) return original.apply(level, pos);
        gravityapi$shapeDepth.set(1);
        try { return RotationShapes.rotate(original.apply(level, pos), r); } finally { gravityapi$shapeDepth.remove(); }
    }
    @WrapMethod(method = {"getShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            "getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
            "getVisualShape"})
    private VoxelShape gravityapi$contextShape(BlockGetter level, BlockPos pos, CollisionContext context, Operation<VoxelShape> original) {
        if (!gravityapi$rotated(level, pos)) return original.call(level, pos, context);
        return gravityapi$shape(level, pos, (getter, cell) -> original.call(getter, cell, context));
    }
    @WrapMethod(method = {"getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;", "getInteractionShape", "getOcclusionShape", "getBlockSupportShape"})
    private VoxelShape gravityapi$simpleShape(BlockGetter level, BlockPos pos, Operation<VoxelShape> original) {
        if (!gravityapi$rotated(level, pos)) return original.call(level, pos);
        return gravityapi$shape(level, pos, (getter, cell) -> original.call(getter, cell));
    }
    @WrapMethod(method = "getFaceOcclusionShape")
    private VoxelShape gravityapi$face(BlockGetter level, BlockPos pos, Direction face, Operation<VoxelShape> original) {
        CubeRotation r = RotationLookup.get(level, pos);
        return r == null ? original.call(level, pos, face) : asState().getOcclusionShape(level, pos).getFaceShape(face);
    }
    @WrapMethod(method = "isFaceSturdy(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Direction;Lnet/minecraft/world/level/block/SupportType;)Z")
    private boolean gravityapi$sturdy(BlockGetter level, BlockPos pos, Direction face, SupportType support, Operation<Boolean> original) {
        LocalNeighbourView view = LocalNeighbourView.of(level);
        if (view != null) return asState().isFaceSturdy(view.level(), view.world(pos), view.rotation().apply(face), support);
        CubeRotation r = RotationLookup.get(level, pos);
        if (r == null) return original.call(level, pos, face, support);
        // SupportType asks for the already rotated support shape, bypassing the per-state face cache.
        return support.isSupporting(asState(), level, pos, face);
    }
    @WrapMethod(method = "canSurvive")
    private boolean gravityapi$survive(LevelReader level, BlockPos pos, Operation<Boolean> original) {
        CubeRotation r = RotationLookup.get(level, pos);
        if (r == null) return original.call(level, pos);
        try { return original.call(new LocalNeighbourView(level, pos.immutable(), r).proxy(), pos); }
        catch (ClassCastException concreteLevelRequired) { return original.call(level, pos); }
    }
    @WrapMethod(method = "updateShape")
    private BlockState gravityapi$update(Direction direction, BlockState neighbour, LevelAccessor level, BlockPos pos, BlockPos neighbourPos, Operation<BlockState> original) {
        var block = asState().getBlock();
        // These hooks use direction only to detect loss of their supporting face. Arbitrary
        // connection logic needs a fully transformed mutable world, which we cannot provide.
        if (block instanceof net.minecraft.world.level.block.BellBlock || block instanceof net.minecraft.world.level.block.TorchBlock
                || block instanceof net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock
                || block instanceof net.minecraft.world.level.block.LadderBlock) {
            CubeRotation r = RotationLookup.get(level, pos);
            if (r != null) direction = r.inverse(direction);
        }
        return original.call(direction, neighbour, level, pos, neighbourPos);
    }
    @WrapMethod(method = {"getSignal", "getDirectSignal"})
    private int gravityapi$signal(BlockGetter level, BlockPos pos, Direction direction, Operation<Integer> original) {
        CubeRotation r = RotationLookup.get(level, pos);
        return original.call(level, pos, r == null ? direction : r.inverse(direction));
    }
    @WrapMethod(method = "use")
    private InteractionResult gravityapi$use(Level level, Player player, InteractionHand hand, BlockHitResult hit, Operation<InteractionResult> original) {
        CubeRotation r = RotationLookup.get(level, hit.getBlockPos());
        return original.call(level, player, hand, r == null ? hit : RotationInteraction.local(hit, r));
    }
    @WrapMethod(method = "canBeReplaced(Lnet/minecraft/world/item/context/BlockPlaceContext;)Z")
    private boolean gravityapi$replace(BlockPlaceContext context, Operation<Boolean> original) {
        CubeRotation r = RotationLookup.get(context.getLevel(), context.getClickedPos());
        return original.call(r == null || RotationInteraction.isLocal(context) ? context : RotationInteraction.local(context, r));
    }
}
