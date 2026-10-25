package net.memezforbeanz.gravityapi.mixin;

import net.memezforbeanz.gravityapi.placement.PlacementFrame;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Scoped read-only views for placement implementations that bypass BlockPlaceContext. */
@Mixin(Entity.class)
public abstract class PlacementPlayerMixin {
    @Inject(method = "getYRot", at = @At("HEAD"), cancellable = true)
    private void gravityapi$yaw(CallbackInfoReturnable<Float> cir) {
        PlacementFrame frame = PlacementFrame.inputs(this);
        if (frame != null) cir.setReturnValue(frame.inputYaw());
    }

    @Inject(method = "getXRot", at = @At("HEAD"), cancellable = true)
    private void gravityapi$pitch(CallbackInfoReturnable<Float> cir) {
        PlacementFrame frame = PlacementFrame.inputs(this);
        if (frame != null) cir.setReturnValue(frame.inputPitch());
    }

    @Inject(method = "getViewYRot", at = @At("HEAD"), cancellable = true)
    private void gravityapi$viewYaw(float partialTick, CallbackInfoReturnable<Float> cir) {
        PlacementFrame frame = PlacementFrame.inputs(this);
        if (frame != null) cir.setReturnValue(frame.inputYaw());
    }

    @Inject(method = "getViewXRot", at = @At("HEAD"), cancellable = true)
    private void gravityapi$viewPitch(float partialTick, CallbackInfoReturnable<Float> cir) {
        PlacementFrame frame = PlacementFrame.inputs(this);
        if (frame != null) cir.setReturnValue(frame.inputPitch());
    }

    @Inject(method = "getViewVector", at = @At("HEAD"), cancellable = true)
    private void gravityapi$view(float partialTick, CallbackInfoReturnable<Vec3> cir) {
        PlacementFrame frame = PlacementFrame.inputs(this);
        if (frame != null) cir.setReturnValue(frame.inputLook());
    }

    @Inject(method = "getLookAngle", at = @At("HEAD"), cancellable = true)
    private void gravityapi$look(CallbackInfoReturnable<Vec3> cir) {
        PlacementFrame frame = PlacementFrame.inputs(this);
        if (frame != null) cir.setReturnValue(frame.inputLook());
    }

    @Inject(method = "getDirection", at = @At("HEAD"), cancellable = true)
    private void gravityapi$horizontal(CallbackInfoReturnable<Direction> cir) {
        PlacementFrame frame = PlacementFrame.inputs(this);
        if (frame != null) cir.setReturnValue(frame.inputHorizontal());
    }
}
