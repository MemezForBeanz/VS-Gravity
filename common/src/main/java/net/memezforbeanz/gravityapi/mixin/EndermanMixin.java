package net.memezforbeanz.gravityapi.mixin;


import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.util.TeleportRescue;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.player.Player;

@Mixin(EnderMan.class)
public abstract class EndermanMixin {
    /** Vanilla lands by dropping in world Y over world blocks: under other gravity, on ships and in fields, land along gravity instead. */
    @Inject(method = "Lnet/minecraft/world/entity/monster/EnderMan;teleport(DDD)Z", at = @At("HEAD"), cancellable = true)
    private void gravityapi$teleportAlongGravity(double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
        EnderMan self = (EnderMan) (Object) this;
        if (TeleportRescue.needsGravityTeleport(self)) {
            cir.setReturnValue(TeleportRescue.teleportNear(self, x, y, z));
        }
    }

    @Redirect(
        method = "Lnet/minecraft/world/entity/monster/EnderMan;isLookingAtMe(Lnet/minecraft/world/entity/player/Player;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getEyeY()D",
            ordinal = 0
        )
    )
    private double redirect_isPlayerStaring_getEyeY_0(Player playerEntity) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(playerEntity);
        if (gravityDirection == Direction.DOWN) {
            return playerEntity.getEyeY();
        }
        
        return playerEntity.getEyePosition().y;
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/entity/monster/EnderMan;isLookingAtMe(Lnet/minecraft/world/entity/player/Player;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getX()D",
            ordinal = 0
        )
    )
    private double redirect_isPlayerStaring_getX_0(Player playerEntity) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(playerEntity);
        if (gravityDirection == Direction.DOWN) {
            return playerEntity.getX();
        }
        
        return playerEntity.getEyePosition().x;
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/entity/monster/EnderMan;isLookingAtMe(Lnet/minecraft/world/entity/player/Player;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getZ()D",
            ordinal = 0
        )
    )
    private double redirect_isPlayerStaring_getZ_0(Player playerEntity) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(playerEntity);
        if (gravityDirection == Direction.DOWN) {
            return playerEntity.getZ();
        }
        
        return playerEntity.getEyePosition().z;
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/entity/monster/EnderMan;teleportTowards(Lnet/minecraft/world/entity/Entity;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getEyeY()D",
            ordinal = 0
        )
    )
    private double redirect_teleportTo_getEyeY_0(Entity entity) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(entity);
        if (gravityDirection == Direction.DOWN) {
            return entity.getEyeY();
        }
        
        return entity.getEyePosition().y;
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/entity/monster/EnderMan;teleportTowards(Lnet/minecraft/world/entity/Entity;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getX()D",
            ordinal = 0
        )
    )
    private double redirect_teleportTo_getX_0(Entity entity) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(entity);
        if (gravityDirection == Direction.DOWN) {
            return entity.getX();
        }
        
        return entity.getEyePosition().x;
    }
    
    @Redirect(
        method = "Lnet/minecraft/world/entity/monster/EnderMan;teleportTowards(Lnet/minecraft/world/entity/Entity;)Z",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;getZ()D",
            ordinal = 0
        )
    )
    private double redirect_teleportTo_getZ_0(Entity entity) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(entity);
        if (gravityDirection == Direction.DOWN) {
            return entity.getZ();
        }
        
        return entity.getEyePosition().z;
    }
}
