package net.memezforbeanz.gravityapi.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.memezforbeanz.gravityapi.api.ProjectileGravity;

import net.minecraft.world.entity.projectile.LlamaSpit;
import net.minecraft.world.phys.Vec3;

@Mixin(LlamaSpit.class)
public abstract class LlamaSpitMixin {

    // The only Vec3.add(DDD) in tick is the gravity step, add(0, -0.06, 0)
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;add(DDD)Lnet/minecraft/world/phys/Vec3;"))
    private Vec3 gravityapi$fieldGravity(Vec3 velocity, double x, double y, double z, Operation<Vec3> original) {
        Vec3 gravity = ProjectileGravity.acceleration((LlamaSpit) (Object) this, ProjectileGravity.LLAMA_SPIT_GRAVITY);
        return original.call(velocity, gravity.x, gravity.y, gravity.z);
    }
}
