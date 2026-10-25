package net.memezforbeanz.gravityapi.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.memezforbeanz.gravityapi.api.ProjectileGravity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

@Mixin(ThrowableProjectile.class)
public abstract class ThrowableProjectileMixin {

    @Shadow
    protected abstract float getGravity();

    @Inject(method = "<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;)V", at = @At("TAIL"))
    private void gravityapi$spawnAtEyes(EntityType<? extends ThrowableProjectile> type, LivingEntity shooter, Level level, CallbackInfo ci) {
        ProjectileGravity.placeAtEyes((ThrowableProjectile) (Object) this, shooter);
    }

    // The only setDeltaMovement(DDD) in tick is the gravity step, (x, y - getGravity(), z)
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/projectile/ThrowableProjectile;setDeltaMovement(DDD)V"))
    private void gravityapi$fieldGravity(ThrowableProjectile self, double x, double y, double z, Operation<Void> original) {
        double base = this.getGravity();
        Vec3 gravity = ProjectileGravity.acceleration(self, base);
        original.call(self, x + gravity.x, y + base + gravity.y, z + gravity.z);
    }
}
