package net.memezforbeanz.gravityapi.mixin;


import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.api.GravityDirection;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.control.LookControl;
import net.minecraft.world.entity.Mob;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import java.util.Optional;

@Mixin(LookControl.class)
public abstract class LookControlMixin {
    @Redirect(
        method = "Lnet/minecraft/world/entity/ai/control/LookControl;getWantedY(Lnet/minecraft/world/entity/Entity;)D",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getEyeY()D",
            ordinal = 0
        )
    )
    private static double redirect_getLookingHeightForgetEyeY_0(Entity entity) {
        GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec(entity);
        if (gravityDirection.equals(GravityDirection.DOWN)) {
            return entity.getEyeY();
        }
        
        return entity.getEyePosition().y;
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/entity/ai/control/LookControl;setLookAt(Lnet/minecraft/world/entity/Entity;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getX()D",
            ordinal = 0
        )
    )
    private double redirect_lookAt_getX_0_0(Entity entity) {
        GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec(entity);
        if (gravityDirection.equals(GravityDirection.DOWN)) {
            return entity.getX();
        }
        
        return entity.getEyePosition().x;
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/entity/ai/control/LookControl;setLookAt(Lnet/minecraft/world/entity/Entity;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getZ()D",
            ordinal = 0
        )
    )
    private double redirect_lookAt_getZ_0_0(Entity entity) {
        GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec(entity);
        if (gravityDirection.equals(GravityDirection.DOWN)) {
            return entity.getZ();
        }
        
        return entity.getEyePosition().z;
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/entity/ai/control/LookControl;setLookAt(Lnet/minecraft/world/entity/Entity;FF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getX()D",
            ordinal = 0
        )
    )
    private double redirect_lookAt_getX_0_1(Entity entity) {
        GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec(entity);
        if (gravityDirection.equals(GravityDirection.DOWN)) {
            return entity.getX();
        }
        
        return entity.getEyePosition().x;
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/entity/ai/control/LookControl;setLookAt(Lnet/minecraft/world/entity/Entity;FF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getZ()D",
            ordinal = 0
        )
    )
    private double redirect_lookAt_getZ_0_1(Entity entity) {
        GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec(entity);
        if (gravityDirection.equals(GravityDirection.DOWN)) {
            return entity.getZ();
        }
        
        return entity.getEyePosition().z;
    }

    @Shadow @Final protected Mob mob;
    @Shadow protected double wantedX;
    @Shadow protected double wantedY;
    @Shadow protected double wantedZ;

    /** Head yaw toward the wanted point in the mob's gravity frame (vanilla reads world X/Z). */
    @Inject(method = "getYRotD", at = @At("HEAD"), cancellable = true)
    private void gravityapi$localYaw(CallbackInfoReturnable<Optional<Float>> cir) {
        Vec3 local = gravityapi$localWanted();
        if (local != null) {
            cir.setReturnValue(Math.abs(local.x) > 1.0E-5F || Math.abs(local.z) > 1.0E-5F
                ? Optional.of((float) (Mth.atan2(local.z, local.x) * Mth.RAD_TO_DEG) - 90.0F)
                : Optional.empty());
        }
    }

    /** Head pitch toward the wanted point in the mob's gravity frame. */
    @Inject(method = "getXRotD", at = @At("HEAD"), cancellable = true)
    private void gravityapi$localPitch(CallbackInfoReturnable<Optional<Float>> cir) {
        Vec3 local = gravityapi$localWanted();
        if (local != null) {
            double horizontal = Math.sqrt(local.x * local.x + local.z * local.z);
            cir.setReturnValue(Math.abs(local.y) > 1.0E-5F || horizontal > 1.0E-5F
                ? Optional.of((float) -(Mth.atan2(local.y, horizontal) * Mth.RAD_TO_DEG))
                : Optional.empty());
        }
    }

    /** The wanted point from the mob's eyes in its gravity frame, or null under plain DOWN gravity. */
    @Unique
    private Vec3 gravityapi$localWanted() {
        GravityDirection gravity = GravityChangerAPI.getGravityDirectionVec(this.mob);
        if (gravity.equals(GravityDirection.DOWN)) {
            return null;
        }
        return gravity.vecWorldToPlayer(new Vec3(wantedX, wantedY, wantedZ).subtract(this.mob.getEyePosition()));
    }
}
