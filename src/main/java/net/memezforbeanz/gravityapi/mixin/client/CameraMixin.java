package net.memezforbeanz.gravityapi.mixin.client;

import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.memezforbeanz.gravityapi.RotationAnimation;
import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.api.GravityDirection;
import net.memezforbeanz.gravityapi.client.GravityCameraState;
import net.memezforbeanz.gravityapi.util.QuaternionUtil;

import net.minecraft.client.Camera;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;

@Mixin(value = Camera.class, priority = 1001)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPosition(double x, double y, double z);
    
    @Shadow
    private Entity entity;
    
    @Shadow
    @Final
    private Quaternionf rotation;
    
    @Shadow
    private float eyeHeightOld;
    
    @Shadow
    private float eyeHeight;

    @WrapOperation(
        method = "setup",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;setPosition(DDD)V",
            ordinal = 0
        )
    )
    private void wrapOperation_update_setPos_0(
        Camera camera, double x, double y, double z,
        Operation<Void> original, BlockGetter area, Entity focusedEntity,
        boolean thirdPerson, boolean inverseView, float tickDelta
    ) {
        GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec(focusedEntity);
        RotationAnimation animation = GravityChangerAPI.getRotationAnimation(focusedEntity);
        boolean animating = animation != null && animation.isInAnimation();

        // If gravity is exactly down and nothing is easing back, use default behavior
        if (gravityDirection.equals(GravityDirection.DOWN) && !animating) {
            original.call(this, x, y, z);
            return;
        }

        // For any non-down gravity (including VS ships), use quaternion-based eye position
        double entityX = Mth.lerp((double) tickDelta, focusedEntity.xo, focusedEntity.getX());
        double entityY = Mth.lerp((double) tickDelta, focusedEntity.yo, focusedEntity.getY());
        double entityZ = Mth.lerp((double) tickDelta, focusedEntity.zo, focusedEntity.getZ());
        double currentCameraY = Mth.lerp(tickDelta, this.eyeHeightOld, this.eyeHeight);

        // The eye offset uses the frame's smoothed rotation, the animation compensating the rotation-centre jump
        Quaternionf frameRotation = GravityCameraState.hasCachedRotation()
                ? GravityCameraState.getLastSmoothedRotation()
                : new Quaternionf(gravityDirection.getWorldToPlayerRotation());
        Vec3 localEyeOffset = new Vec3(0, currentCameraY, 0);
        Vec3 eyeOffset;
        if (animating) {
            eyeOffset = animation.getEyeOffset(frameRotation, localEyeOffset,
                    GravityChangerAPI.getGravityDirection(focusedEntity));
        } else {
            eyeOffset = QuaternionUtil.rotate(localEyeOffset, new Quaternionf(frameRotation).conjugate());
        }
        original.call(this, entityX + eyeOffset.x(), entityY + eyeOffset.y(), entityZ + eyeOffset.z());
    }
    
    @Inject(
        method = "Lnet/minecraft/client/Camera;setRotation(FF)V",
        at = @At(
            value = "INVOKE",
            target = "Lorg/joml/Quaternionf;rotationYXZ(FFF)Lorg/joml/Quaternionf;",
            shift = At.Shift.AFTER,
            remap = false
        )
    )
    private void inject_setRotation(CallbackInfo ci) {
        if (this.entity != null) {
            GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec(this.entity);

            // Normal gravity returns early, unless a field free fall is tilting the view
            if (gravityDirection.equals(GravityDirection.DOWN)
                    && GravityChangerAPI.getVisualTilt(this.entity, 1.0f) == null) {
                return;
            }

            // The smoothed rotation GameRendererMixin computed this frame
            Quaternionf smoothedGravityRot;
            if (GravityCameraState.hasCachedRotation()) {
                smoothedGravityRot = GravityCameraState.getLastSmoothedRotation();
            } else {
                long timeMs = this.entity.level().getGameTime() * 50;
                smoothedGravityRot = GravityCameraState.getSmoothedGravityRotation(this.entity, timeMs);
            }

            Quaternionf gravityRotation = new Quaternionf(smoothedGravityRot);
            gravityRotation.conjugate();
            gravityRotation.mul(this.rotation);
            this.rotation.set(gravityRotation.x(), gravityRotation.y(), gravityRotation.z(), gravityRotation.w());
        }
    }
}
