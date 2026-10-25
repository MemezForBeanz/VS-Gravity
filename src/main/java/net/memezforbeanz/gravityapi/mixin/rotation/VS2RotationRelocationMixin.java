package net.memezforbeanz.gravityapi.mixin.rotation;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.memezforbeanz.gravityapi.placement.CubeRotation;
import net.memezforbeanz.gravityapi.rotation.*;
import net.minecraft.core.*;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "org.valkyrienskies.mod.util.RelocationUtilKt", remap = false)
public abstract class VS2RotationRelocationMixin {
    @Inject(method = "relocateBlock(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/core/BlockPos;ZLorg/valkyrienskies/core/api/ships/ServerShip;Lnet/minecraft/world/level/block/Rotation;)V", at = @At("HEAD"), require = 0)
    private static void gravityapi$capture(LevelChunk fromChunk, BlockPos from, LevelChunk toChunk, BlockPos to, boolean update,
            @Coerce Object ship, Rotation rotation, CallbackInfo ci, @Share("rotation") LocalRef<CubeRotation> stored) {
        stored.set(RotationLookup.get(fromChunk.getLevel(), from));
    }
    @WrapOperation(method = "relocateBlock(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/core/BlockPos;ZLorg/valkyrienskies/core/api/ships/ServerShip;Lnet/minecraft/world/level/block/Rotation;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;rotate(Lnet/minecraft/world/level/block/Rotation;)Lnet/minecraft/world/level/block/state/BlockState;", remap = true), require = 0)
    private static BlockState gravityapi$keepLocal(BlockState state, Rotation rotation, Operation<BlockState> original,
            @Share("rotation") LocalRef<CubeRotation> stored) {
        return stored.get() == null ? original.call(state, rotation) : state;
    }
    @WrapOperation(method = "relocateBlock(Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/core/BlockPos;ZLorg/valkyrienskies/core/api/ships/ServerShip;Lnet/minecraft/world/level/block/Rotation;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;", remap = true), require = 0)
    private static BlockState gravityapi$carry(LevelChunk chunk, BlockPos pos, BlockState state, boolean moving, Operation<BlockState> original,
            LevelChunk fromChunk, BlockPos from, LevelChunk toChunk, BlockPos to, boolean update, @Coerce Object ship, Rotation rotation,
            @Share("rotation") LocalRef<CubeRotation> stored) {
        if (stored.get() == null || chunk != toChunk || !pos.equals(to)) return original.call(chunk, pos, state, moving);
        CubeRotation turn = new CubeRotation(rotation.rotate(Direction.EAST), Direction.UP, rotation.rotate(Direction.SOUTH));
        CubeRotation result = turn.compose(stored.get());
        return RotationScope.with(chunk.getLevel(), pos, result, () -> original.call(chunk, pos, state, moving));
    }
}
