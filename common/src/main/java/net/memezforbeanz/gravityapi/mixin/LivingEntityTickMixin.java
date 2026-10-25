package net.memezforbeanz.gravityapi.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.memezforbeanz.gravityapi.event.CommonEvents;
import net.minecraft.world.entity.LivingEntity;

/** Replaces Forge's LivingTickEvent, which fires at the start of LivingEntity#tick. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityTickMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void gravityapi$livingTick(CallbackInfo ci) {
        CommonEvents.onLivingTick((LivingEntity) (Object) this);
    }
}
