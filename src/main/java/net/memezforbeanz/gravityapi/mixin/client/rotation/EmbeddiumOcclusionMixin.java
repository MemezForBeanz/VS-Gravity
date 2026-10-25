package net.memezforbeanz.gravityapi.mixin.client.rotation;

import net.memezforbeanz.gravityapi.rotation.RotationCulling;
import net.minecraft.core.*;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockOcclusionCache", remap = false)
public abstract class EmbeddiumOcclusionMixin {
    @Inject(method = "shouldDrawSide", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void gravityapi$cull(BlockState state, BlockGetter level, BlockPos pos, Direction face, CallbackInfoReturnable<Boolean> cir) {
        BlockPos neighbour = pos.relative(face);
        if (RotationCulling.affected(level, pos, neighbour)) cir.setReturnValue(RotationCulling.visible(state, level, pos, face, neighbour));
    }
}
