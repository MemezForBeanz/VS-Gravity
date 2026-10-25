package net.memezforbeanz.gravityapi.mixin;


import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.api.GravityDirection;
import net.memezforbeanz.gravityapi.api.GravityPathNavigation;
import net.memezforbeanz.gravityapi.util.RotationUtil;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.Level;

@Mixin(Mob.class)
public abstract class MobMixin {
	@Shadow
	protected PathNavigation navigation;
    
    @Unique
    private int gravityapi$ticksSinceNonDownGravity = 0;

    @Inject(at = @At("HEAD"), method = "tick", cancellable = true)
    private void tick(CallbackInfo ci)
    {
    	Mob mob = Mob.class.cast(this);
        GravityDirection gravity = GravityChangerAPI.getGravityDirectionVec(mob);
        // A ship-locked frame needs ship-space paths even when it points world-down
    	if(gravity.getNearestDirection() != Direction.DOWN || gravity.isShipAnchored() || !gravity.isCardinal())
    	{
    		this.gravityapi$ticksSinceNonDownGravity = 0;
			if(!(this.navigation instanceof GravityPathNavigation))
			{
				if(this.navigation instanceof GroundPathNavigation ground)
				{
					GravityPathNavigation replacement = new GravityPathNavigation(mob, mob.level());
					// Set by goals at construction (FloatGoal) on the original navigation
					replacement.setCanFloat(ground.canFloat());
					replacement.setCanOpenDoors(ground.getNodeEvaluator().canOpenDoors());
	    			this.navigation = replacement;
				}
			}
    	}
    	else if(this.navigation instanceof GravityPathNavigation)
    	{
    		// Hysteresis: only swap back to vanilla navigation once gravity has been stably DOWN (field edges flicker)
    		if(++this.gravityapi$ticksSinceNonDownGravity >= 40)
    		{
    			this.navigation = this.createNavigation(mob.level());
    		}
    	}
    }
    
    @Shadow
    protected PathNavigation createNavigation(Level p_21480_)
    {
    	throw new IllegalStateException();
    }
    
    @WrapOperation(
        method = "doHurtTarget",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Mob;getYRot()F"
        )
    )
    private float wrapOperation_tryAttack_getYaw_0(Mob attacker, Operation<Float> original, Entity target) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(target);
        if (gravityDirection == Direction.DOWN) {
            return original.call(attacker);
        }
        
        return RotationUtil.rotWorldToPlayer(original.call(attacker), attacker.getXRot(), gravityDirection).x;
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/entity/Mob;lookAt(Lnet/minecraft/world/entity/Entity;FF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;getEyeY()D",
            ordinal = 0
        )
    )
    private double redirect_lookAtEntity_getEyeY_0(LivingEntity livingEntity) {
        GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec(livingEntity);
        if (gravityDirection.equals(GravityDirection.DOWN)) {
            return livingEntity.getEyeY();
        }
        
        return livingEntity.getEyePosition().y;
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/entity/Mob;lookAt(Lnet/minecraft/world/entity/Entity;FF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getX()D",
            ordinal = 0
        )
    )
    private double redirect_lookAtEntity_getX_0(Entity entity) {
        GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec(entity);
        if (gravityDirection.equals(GravityDirection.DOWN)) {
            return entity.getX();
        }
        
        return entity.getEyePosition().x;
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/entity/Mob;lookAt(Lnet/minecraft/world/entity/Entity;FF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getZ()D",
            ordinal = 0
        )
    )
    private double redirect_lookAtEntity_getZ_0(Entity entity) {
        GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec(entity);
        if (gravityDirection.equals(GravityDirection.DOWN)) {
            return entity.getZ();
        }
        
        return entity.getEyePosition().z;
    }

    /** Turn toward an entity in this mob's gravity frame (vanilla computes yaw and pitch from world deltas). */
    @Inject(method = "lookAt(Lnet/minecraft/world/entity/Entity;FF)V", at = @At("HEAD"), cancellable = true)
    private void gravityapi$lookAtInFrame(Entity target, float maxYaw, float maxPitch, CallbackInfo ci) {
        Mob self = (Mob) (Object) this;
        GravityDirection gravity = GravityChangerAPI.getGravityDirectionVec(self);
        if (gravity.equals(GravityDirection.DOWN)
                && GravityChangerAPI.getGravityDirectionVec(target).equals(GravityDirection.DOWN)) {
            return;
        }
        Vec3 point = target instanceof LivingEntity ? target.getEyePosition() : target.getBoundingBox().getCenter();
        Vec3 local = gravity.vecWorldToPlayer(point.subtract(self.getEyePosition()));
        double horizontal = Math.sqrt(local.x * local.x + local.z * local.z);
        float yaw = (float) (Mth.atan2(local.z, local.x) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) -(Mth.atan2(local.y, horizontal) * Mth.RAD_TO_DEG);
        self.setXRot(gravityapi$rotlerp(self.getXRot(), pitch, maxPitch));
        self.setYRot(gravityapi$rotlerp(self.getYRot(), yaw, maxYaw));
        ci.cancel();
    }

    @Unique
    private static float gravityapi$rotlerp(float from, float to, float max) {
        return from + Mth.clamp(Mth.wrapDegrees(to - from), -max, max);
    }
}
