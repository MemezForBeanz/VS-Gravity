package net.memezforbeanz.gravityapi.mixin;

import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.api.GravityDirection;
import net.memezforbeanz.gravityapi.api.ProjectileGravity;
import net.memezforbeanz.gravityapi.util.QuaternionUtil;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;

@Mixin(Projectile.class)
public abstract class ProjectileMixin {

    @Unique
    private boolean gravityapi$aiming = false;

    @Shadow
    public abstract void shoot(double x, double y, double z, float speed, float divergence);

    /**
     * Shoot along the shooter's view in its own gravity frame (ship frames and a free fall's tilt
     * included), inheriting its motion in world space.
     */
    @Inject(method = "shootFromRotation", at = @At("HEAD"), cancellable = true)
    private void gravityapi$shootInShooterFrame(Entity shooter, float pitch, float yaw, float roll, float speed,
                                                float divergence, CallbackInfo ci) {
        GravityDirection gravity = GravityChangerAPI.getGravityDirectionVec(shooter);
        Quaternionf tilt = GravityChangerAPI.getVisualTilt(shooter, 1.0f);
        if (gravity.equals(GravityDirection.DOWN) && tilt == null) {
            return;
        }
        float toRadians = (float) Math.PI / 180F;
        Vec3 look = new Vec3(-Mth.sin(yaw * toRadians) * Mth.cos(pitch * toRadians),
                -Mth.sin((pitch + roll) * toRadians),
                Mth.cos(yaw * toRadians) * Mth.cos(pitch * toRadians));
        if (tilt != null) {
            look = QuaternionUtil.rotate(look, tilt);
        }
        Vec3 direction = gravity.vecPlayerToWorld(look);
        this.shoot(direction.x, direction.y, direction.z, speed, divergence);
        Vec3 motion = shooter.getDeltaMovement();
        Vec3 inherited = gravity.vecPlayerToWorld(new Vec3(motion.x, shooter.onGround() ? 0.0D : motion.y, motion.z));
        Projectile self = (Projectile) (Object) this;
        self.setDeltaMovement(self.getDeltaMovement().add(inherited));
        ci.cancel();
    }

    /** Mobs aim through the gravity their shot flies through (see {@link ProjectileGravity#aimShot}). */
    @Inject(method = "shoot", at = @At("HEAD"), cancellable = true)
    private void gravityapi$aimWithGravity(double x, double y, double z, float speed, float divergence, CallbackInfo ci) {
        Projectile self = (Projectile) (Object) this;
        if (this.gravityapi$aiming || self.level().isClientSide()) {
            return;
        }
        Vec3 direction = ProjectileGravity.aimShot(self, new Vec3(x, y, z), speed);
        if (direction == null) {
            return;
        }
        this.gravityapi$aiming = true;
        try {
            this.shoot(direction.x, direction.y, direction.z, speed, divergence);
        } finally {
            this.gravityapi$aiming = false;
        }
        ci.cancel();
    }
}
