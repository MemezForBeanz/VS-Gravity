package net.memezforbeanz.gravityapi.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.memezforbeanz.gravityapi.api.GravityChangerAPI;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ThrownPotion;

@Mixin(ThrownPotion.class)
public class ThrownPotionMixin {
    @ModifyReturnValue(method = "getGravity", at = @At("RETURN"))
    private float multiplyGravity(float original) {
        return original * (float) GravityChangerAPI.getGravityStrength(((Entity) (Object) this));
    }
}
