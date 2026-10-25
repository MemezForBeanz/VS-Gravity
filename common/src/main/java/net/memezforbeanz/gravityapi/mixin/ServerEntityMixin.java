package net.memezforbeanz.gravityapi.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.memezforbeanz.gravityapi.event.CommonEvents;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

/** Replaces Forge's PlayerEvent.StartTracking, which fires right after this pairing. */
@Mixin(ServerEntity.class)
public abstract class ServerEntityMixin {
    @Shadow @Final private Entity entity;

    @Inject(method = "addPairing", at = @At("TAIL"))
    private void gravityapi$startTracking(ServerPlayer player, CallbackInfo ci) {
        CommonEvents.onStartTracking(this.entity, player);
    }
}
