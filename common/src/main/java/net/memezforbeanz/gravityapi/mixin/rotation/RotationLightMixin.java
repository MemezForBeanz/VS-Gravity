package net.memezforbeanz.gravityapi.mixin.rotation;

import net.memezforbeanz.gravityapi.rotation.RotationLookup;
import net.minecraft.core.*;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.shapes.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LightEngine.class)
public abstract class RotationLightMixin {
    @Inject(method = "getOcclusionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Lnet/minecraft/world/phys/shapes/VoxelShape;", at = @At("HEAD"), cancellable = true)
    private static void gravityapi$lightFace(BlockGetter level, BlockPos pos, BlockState state, Direction face, CallbackInfoReturnable<VoxelShape> cir) {
        if (state.canOcclude() && RotationLookup.get(level, pos) != null) cir.setReturnValue(state.getFaceOcclusionShape(level, pos, face));
    }
    @Inject(method = "getLightBlockInto", at = @At("HEAD"), cancellable = true)
    private static void gravityapi$light(BlockGetter level, BlockState a, BlockPos pa, BlockState b, BlockPos pb,
            Direction direction, int fallback, CallbackInfoReturnable<Integer> cir) {
        boolean ra = RotationLookup.get(level, pa) != null, rb = RotationLookup.get(level, pb) != null;
        if (!ra && !rb) return;
        VoxelShape sa = a.canOcclude() && (ra || a.useShapeForLightOcclusion()) ? a.getOcclusionShape(level, pa) : Shapes.empty();
        VoxelShape sb = b.canOcclude() && (rb || b.useShapeForLightOcclusion()) ? b.getOcclusionShape(level, pb) : Shapes.empty();
        cir.setReturnValue(Shapes.mergedFaceOccludes(sa, sb, direction) ? 16 : fallback);
    }
}
