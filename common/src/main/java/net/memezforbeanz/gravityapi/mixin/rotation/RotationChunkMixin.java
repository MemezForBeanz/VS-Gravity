package net.memezforbeanz.gravityapi.mixin.rotation;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.memezforbeanz.gravityapi.rotation.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.server.level.ServerLevel;

@Mixin(LevelChunk.class)
public abstract class RotationChunkMixin implements RotationChunk {
    @Unique private RotationStorage gravityapi$storage;
    public RotationStorage gravityapi$rotations() {
        // Never null: created on demand if a construction path (or another mod's subclass) skipped the hook
        if (gravityapi$storage == null) gravityapi$storage = new RotationStorage((LevelChunk) (Object) this);
        return gravityapi$storage;
    }
    public void gravityapi$rotations(RotationStorage storage) { gravityapi$storage = storage; }
    // The full constructor: the others delegate to it. A bare "<init>" target remaps to one constructor only.
    @Inject(method = "<init>(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/UpgradeData;Lnet/minecraft/world/ticks/LevelChunkTicks;Lnet/minecraft/world/ticks/LevelChunkTicks;J[Lnet/minecraft/world/level/chunk/LevelChunkSection;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;Lnet/minecraft/world/level/levelgen/blending/BlendingData;)V", at = @At("RETURN"))
    private void gravityapi$create(CallbackInfo ci) {
        if (gravityapi$storage == null) gravityapi$storage = new RotationStorage((LevelChunk) (Object) this);
    }
    @Inject(method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V", at = @At("RETURN"))
    private void gravityapi$promote(ServerLevel level, ProtoChunk proto, LevelChunk.PostLoadProcessor processor, CallbackInfo ci) {
        if (gravityapi$storage == null) gravityapi$storage = new RotationStorage((LevelChunk) (Object) this);
        var tag = ((RotationPending) proto).gravityapi$pendingRotations();
        if (tag != null) gravityapi$storage.deserializeNBT(tag);
    }
    @Inject(method = "setLoaded", at = @At("TAIL"))
    private void gravityapi$unload(boolean loaded, CallbackInfo ci) {
        if (!loaded && gravityapi$storage != null) gravityapi$storage.invalidate();
    }
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
