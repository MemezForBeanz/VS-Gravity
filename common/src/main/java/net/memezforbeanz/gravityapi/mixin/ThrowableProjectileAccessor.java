package net.memezforbeanz.gravityapi.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.world.entity.projectile.ThrowableProjectile;

@Mixin(ThrowableProjectile.class)
public interface ThrowableProjectileAccessor {
    @Invoker("getGravity")
    float gravityapi$getGravity();
}
