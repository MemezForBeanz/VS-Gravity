package net.memezforbeanz.gravityapi.mixin;


import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.util.RotationUtil;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

@Mixin(FireworkRocketEntity.class)
public abstract class FireworkRocketMixin extends Entity {
    
    @Shadow
    private @Nullable LivingEntity attachedToEntity;
    
    
    public FireworkRocketMixin(EntityType<?> type, Level world) {
        super(type, world);
    }
    
    @ModifyVariable(
        method = "Lnet/minecraft/world/entity/projectile/FireworkRocketEntity;tick()V",
        at = @At(
            value = "STORE"
        )
        , ordinal = 0
    )
    public Vec3 tick(Vec3 value) {
        if (attachedToEntity != null) {
            value = RotationUtil.vecWorldToPlayer(value, GravityChangerAPI.getGravityDirection(attachedToEntity));
        }
        return value;
    }
}
