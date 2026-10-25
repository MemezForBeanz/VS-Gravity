package net.memezforbeanz.gravityapi.mixin.compat;

import net.memezforbeanz.gravityapi.util.TeleportRescue;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mutant Monsters' mutant enderman (and its clones) find a landing spot by dropping in world Y
 * over world blocks, like vanilla endermen: under other gravity, on ships and in fields it lands
 * along gravity instead (see {@link TeleportRescue#teleportNear}), at once, skipping the
 * teleport animation.
 * <p>
 * Only applied when Mutant Monsters is loaded (see GravityMixinPlugin).
 */
@Pseudo
@Mixin(targets = "fuzs.mutantmonsters.world.entity.mutant.MutantEnderman", remap = false)
public abstract class MutantEndermanMixin {

    @Inject(method = "teleportToPosition(DDD)Z", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void gravityapi$teleportAlongGravity(double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (TeleportRescue.needsGravityTeleport(self)) {
            cir.setReturnValue(TeleportRescue.teleportNear(self, x, y, z));
        }
    }
}
