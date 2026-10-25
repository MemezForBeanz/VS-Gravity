package net.memezforbeanz.gravityapi.mixin.rotation;
import net.memezforbeanz.gravityapi.rotation.RotationCulling;
import net.minecraft.core.*;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(Block.class)
public abstract class RotationBlockCullingMixin {
    @Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true)
    private static void gravityapi$cull(BlockState state, BlockGetter level, BlockPos pos, Direction face, BlockPos neighbour, CallbackInfoReturnable<Boolean> cir) {
        if (RotationCulling.affected(level, pos, neighbour)) cir.setReturnValue(RotationCulling.visible(state, level, pos, face, neighbour));
    }
}
