package net.memezforbeanz.gravityapi.mixin;

import net.memezforbeanz.gravityapi.placement.PlacementFrame;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class PlacementLivingEntityMixin {
    @Inject(method = "getViewYRot", at = @At("HEAD"), cancellable = true)
    private void gravityapi$viewYaw(float partialTick, CallbackInfoReturnable<Float> cir) {
        PlacementFrame frame = PlacementFrame.inputs(this);
        if (frame != null) cir.setReturnValue(frame.inputYaw());
    }
}
