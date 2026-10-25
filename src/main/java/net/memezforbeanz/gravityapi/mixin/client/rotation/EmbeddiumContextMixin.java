package net.memezforbeanz.gravityapi.mixin.client.rotation;

import net.memezforbeanz.gravityapi.rotation.RotatedModel;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderContext", remap = false)
public abstract class EmbeddiumContextMixin {
    @Shadow @Final private BlockAndTintGetter localSlice;
    @Shadow @Final private BlockPos.MutableBlockPos pos;
    @Shadow private BakedModel model;
    @Inject(method = "update", at = @At("RETURN"), require = 0, remap = false)
    private void gravityapi$model(CallbackInfo ci) { model = RotatedModel.at(model, localSlice, pos); }
}
