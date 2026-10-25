package net.memezforbeanz.gravityapi.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.api.GravityDirection;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.control.BodyRotationControl;
import net.minecraft.world.phys.Vec3;

@Mixin(BodyRotationControl.class)
public abstract class BodyRotationControlMixin {

    @Shadow
    @Final
    private Mob mob;

    /** Moving means moving across the mob's floor, in its gravity frame (vanilla reads world X/Z). */
    @Inject(method = "isMoving", at = @At("HEAD"), cancellable = true)
    private void gravityapi$movingInFrame(CallbackInfoReturnable<Boolean> cir) {
        GravityDirection gravity = GravityChangerAPI.getGravityDirectionVec(this.mob);
        if (gravity.equals(GravityDirection.DOWN)) {
            return;
        }
        Vec3 local = gravity.vecWorldToPlayer(this.mob.position().subtract(this.mob.xo, this.mob.yo, this.mob.zo));
        cir.setReturnValue(local.x * local.x + local.z * local.z > 2.5000003E-7F);
    }
}
