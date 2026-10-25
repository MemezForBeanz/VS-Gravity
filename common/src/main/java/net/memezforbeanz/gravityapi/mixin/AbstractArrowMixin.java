package net.memezforbeanz.gravityapi.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.memezforbeanz.gravityapi.api.ProjectileGravity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

@Mixin(AbstractArrow.class)
public abstract class AbstractArrowMixin {

    @Inject(method = "<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;)V", at = @At("TAIL"))
    private void gravityapi$spawnAtEyes(EntityType<? extends AbstractArrow> type, LivingEntity shooter, Level level, CallbackInfo ci) {
        ProjectileGravity.placeAtEyes((AbstractArrow) (Object) this, shooter);
    }

    // The only setDeltaMovement(DDD) in tick is the gravity step, (x, y - 0.05, z): gravity
    // along the field at the arrow instead
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/AbstractArrow;setDeltaMovement(DDD)V"))
    private void gravityapi$fieldGravity(AbstractArrow self, double x, double y, double z, Operation<Void> original) {
        Vec3 gravity = ProjectileGravity.acceleration(self, ProjectileGravity.ARROW_GRAVITY);
        original.call(self, x + gravity.x, y + ProjectileGravity.ARROW_GRAVITY + gravity.y, z + gravity.z);
    }
}
