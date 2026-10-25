package net.memezforbeanz.gravityapi.mixin.rotation;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.memezforbeanz.gravityapi.rotation.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LevelChunk.class)
public abstract class RotationChunkMixin implements RotationChunk {
    @Unique private RotationStorage gravityapi$storage;
    public RotationStorage gravityapi$rotations() { return gravityapi$storage; }
    public void gravityapi$rotations(RotationStorage storage) { gravityapi$storage = storage; }
    @WrapOperation(method = "setBlockState", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunkSection;setBlockState(IIILnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState gravityapi$write(LevelChunkSection section, int x, int y, int z, BlockState state, Operation<BlockState> original,
            BlockPos pos, BlockState requested, boolean moving) {
        BlockState previous = original.call(section, x, y, z, state);
        if (gravityapi$storage == null || previous == state) return previous;
        RotationScope scope = RotationScope.current();
        if (scope != null && scope.pos().equals(pos) && scope.level() == ((LevelChunk) (Object) this).getLevel())
            gravityapi$storage.set(pos, scope.rotation(), true);
        else if (!gravityapi$storage.isEmpty() && previous.getBlock() != state.getBlock()
                && !gravityapi$storage.acceptExpected(pos, state.getBlock())) gravityapi$storage.set(pos, null, true);
        return previous;
    }
}
