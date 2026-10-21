package net.memezforbeanz.gravityapi.mixin;


import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import net.memezforbeanz.gravityapi.api.GravityChangerAPI;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.Level;

@Mixin(FishingHook.class)
public abstract class FishinghookMixin extends Entity {
    
    public FishinghookMixin(EntityType<?> type, Level world) {
        super(type, world);
    }
    
    @ModifyConstant(method = "Lnet/minecraft/world/entity/projectile/FishingHook;tick()V", constant = @Constant(doubleValue = -0.03))
    private double multiplyGravity(double constant) {
        return constant * GravityChangerAPI.getGravityStrength(this);
    }
}
