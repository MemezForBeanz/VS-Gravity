package net.memezforbeanz.gravityapi.mixin;

import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.util.RotationUtil;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

@Mixin(AreaEffectCloud.class)
public abstract class AreaEffectCloudMixin extends Entity {
    
    
    @Shadow
    public abstract boolean isWaiting();
    
    @Shadow
    public abstract float getRadius();
    
    @Shadow
    public abstract ParticleOptions getParticle();
    
    @Shadow
    public abstract int getColor();
    
    @Shadow
    private int duration;
    @Shadow
    private int waitTime;
    
    @Shadow
    protected abstract void setWaiting(boolean waiting);
    
    @Shadow
    private float radiusPerTick;
    
    @Shadow
    public abstract void setRadius(float radius);
    
    @Shadow
    @Final
    private Map<Entity, Integer> victims;
    @Shadow
    private Potion potion;
    @Shadow
    @Final
    private List<MobEffectInstance> effects;
    @Shadow
    private int reapplicationDelay;
    
    @Shadow
    @Nullable
    public abstract LivingEntity getOwner();
    
    @Shadow
    private float radiusOnUse;
    @Shadow
    private int durationOnUse;

    public AreaEffectCloudMixin(EntityType<?> type, Level world) {
        super(type, world);
    }

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addAlwaysVisibleParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"
        )
    )
    private void modify_move_multiply_0(Level instance, ParticleOptions particle, double x, double y, double z, double dx, double dy, double dz, Operation<Void> original) {
        boolean bl = this.isWaiting();
        float f = this.getRadius();
        
        float g;
        if (bl) {
            g = 0.2F;
        }
        else {
            g = f;
        }
        
        float h = this.random.nextFloat() * 6.2831855F;
        float k = Mth.sqrt(this.random.nextFloat()) * g;
        
        double d = this.getX();
        double e = this.getY();
        double l = this.getZ();
        Vec3 modify = RotationUtil.vecWorldToPlayer(d, e, l, GravityChangerAPI.getGravityDirection(this));
        d = modify.x + (double) (Mth.cos(h) * k);
        e = modify.y;
        l = modify.z + (double) (Mth.sin(h) * k);
        modify = RotationUtil.vecPlayerToWorld(d, e, l, GravityChangerAPI.getGravityDirection(this));

        original.call(instance, particle, modify.x, modify.y, modify.z, dx, dy, dz);
    }
    
    
}
