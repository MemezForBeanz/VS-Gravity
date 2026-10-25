package net.memezforbeanz.gravityapi.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.memezforbeanz.gravityapi.api.GravityFields;
import net.memezforbeanz.gravityapi.api.ProjectileGravity;
import net.memezforbeanz.gravityapi.util.PendingGravityAim;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Fireballs and skulls fly at constant velocity, as in vanilla; inside a gravity field the field
 * bends their heading (and their push along it) without changing their speed. Mobs firing them
 * aim through that bending.
 */
@Mixin(AbstractHurtingProjectile.class)
public abstract class AbstractHurtingProjectileMixin implements PendingGravityAim {

    @Shadow
    public double xPower;
    @Shadow
    public double yPower;
    @Shadow
    public double zPower;

    @Shadow
    protected abstract float getInertia();

    /**
     * Set for a mob's shot: aimed when added to the level, after blazes and ghasts have moved it to its
     * spawn point and before its spawn packet (which carries the push) goes out; the first tick is the
     * fallback for other spawn paths.
     */
    @Unique
    private boolean gravityapi$aimPending = false;

    @Inject(method = "<init>(Lnet/minecraft/world/entity/EntityType;Lnet/minecraft/world/entity/LivingEntity;DDDLnet/minecraft/world/level/Level;)V",
            at = @At("TAIL"))
    private void gravityapi$markAim(EntityType<? extends AbstractHurtingProjectile> type, LivingEntity shooter,
                                    double x, double y, double z, Level level, CallbackInfo ci) {
        gravityapi$aimPending = !level.isClientSide() && shooter instanceof Mob;
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void gravityapi$aimOnFirstTick(CallbackInfo ci) {
        gravityapi$aimNow();
    }

    @Override
    public void gravityapi$aimNow() {
        if (!gravityapi$aimPending) {
            return;
        }
        gravityapi$aimPending = false;
        AbstractHurtingProjectile self = (AbstractHurtingProjectile) (Object) this;
        if (!(self.getOwner() instanceof Mob mob)) {
            return;
        }
        double power = Math.sqrt(xPower * xPower + yPower * yPower + zPower * zPower);
        Vec3 direction = ProjectileGravity.aimSteeredShot(self, mob, power, getInertia());
        if (direction != null) {
            xPower = direction.x * power;
            yPower = direction.y * power;
            zPower = direction.z * power;
        }
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void gravityapi$fieldSteering(CallbackInfo ci) {
        AbstractHurtingProjectile self = (AbstractHurtingProjectile) (Object) this;
        if (self.isRemoved()) {
            return;
        }
        Vec3 field = GravityFields.fieldAcceleration(self.level(), self.position());
        if (field == null) {
            return;
        }
        Vec3 velocity = ProjectileGravity.steer(self.getDeltaMovement(), field);
        self.setDeltaMovement(velocity);
        double power = Math.sqrt(xPower * xPower + yPower * yPower + zPower * zPower);
        if (power > 0 && velocity.lengthSqr() > 1.0E-12) {
            Vec3 push = velocity.normalize().scale(power);
            xPower = push.x;
            yPower = push.y;
            zPower = push.z;
        }
    }
}
