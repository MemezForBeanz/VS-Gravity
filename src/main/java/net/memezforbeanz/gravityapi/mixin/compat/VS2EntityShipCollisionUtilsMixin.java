package net.memezforbeanz.gravityapi.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.compat.vs2.ShipCollisionDebug;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Disables VS2's world-space entity-vs-ship collision for entities with ship gravity physics:
 * their ship collision runs in ship space (see {@code EntityMixin#collideOnShip}), and two solvers
 * fought over the same movement, sliding entities off the deck.
 */
@Pseudo
@Mixin(targets = "org.valkyrienskies.mod.common.util.EntityShipCollisionUtils", remap = false)
public class VS2EntityShipCollisionUtilsMixin {

    @Inject(method = "adjustEntityMovementForShipCollisions", at = @At("HEAD"), cancellable = true, remap = false)
    private void gravityapi$skipShipCollisionForArbitraryGravity(
        Entity entity, Vec3 movement, AABB entityBoundingBox, Level world,
        CallbackInfoReturnable<Vec3> cir
    ) {
        // entity is nullable on VS2's side. Strict check: entities merely near a ship keep VS2's
        // collision, their only ship collision
        if (entity != null && GravityChangerAPI.isShipGravityPhysics(entity)) {
            if (entity instanceof Player && ShipCollisionDebug.shouldLog(entity)) {
                ShipCollisionDebug.log("[vs2skip] {} skipped VS2 ship polygon collision, movement={}",
                        entity.level().isClientSide() ? "C" : "S", movement);
            }
            cir.setReturnValue(movement);
        }
    }
}
