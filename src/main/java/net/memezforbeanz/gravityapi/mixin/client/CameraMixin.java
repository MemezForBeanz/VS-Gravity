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
import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.api.GravityDirection;
import net.memezforbeanz.gravityapi.client.GravityCameraState;

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

        // If gravity is exactly down, use default behavior
        if (gravityDirection.equals(GravityDirection.DOWN)) {
            original.call(this, x, y, z);
            return;
        }
        
        // For any non-down gravity (including VS ships), use quaternion-based eye position
        double entityX = Mth.lerp((double) tickDelta, focusedEntity.xo, focusedEntity.getX());
        double entityY = Mth.lerp((double) tickDelta, focusedEntity.yo, focusedEntity.getY());
        double entityZ = Mth.lerp((double) tickDelta, focusedEntity.zo, focusedEntity.getZ());
        double currentCameraY = Mth.lerp(tickDelta, this.eyeHeightOld, this.eyeHeight);

        // Use the exact quaternion rotation for eye offset
        Vec3 eyeOffset = gravityDirection.vecPlayerToWorld(new Vec3(0, currentCameraY, 0));
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

            // Skip if gravity is exactly down
            if (gravityDirection.equals(GravityDirection.DOWN)) {
                return;
            }

            // Get the smoothed gravity rotation from shared state
            // Use getLastSmoothedRotation to get the already-computed rotation
            // (GameRendererMixin should have already updated it this frame)
            Quaternionf smoothedGravityRot;
            if (GravityCameraState.hasCachedRotation()) {
                smoothedGravityRot = GravityCameraState.getLastSmoothedRotation();
            } else {
                // Fallback - compute it now
                long timeMs = this.entity.level().getGameTime() * 50;
                smoothedGravityRot = GravityCameraState.getSmoothedGravityRotation(this.entity, timeMs);
            }

            // Apply the rotation
            Quaternionf gravityRotation = new Quaternionf(smoothedGravityRot);
            gravityRotation.conjugate();
            gravityRotation.mul(this.rotation);
            this.rotation.set(gravityRotation.x(), gravityRotation.y(), gravityRotation.z(), gravityRotation.w());
        }
    }
}
