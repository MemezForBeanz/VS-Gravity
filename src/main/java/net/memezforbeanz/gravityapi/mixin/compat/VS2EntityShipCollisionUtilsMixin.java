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
 * Disables VS2's world-space entity-vs-ship polygon collision for entities under arbitrary (ship)
 * gravity, because this mod resolves their ship collision itself in ship-local space (see
 * {@code EntityMixin#collideOnShip}).
 *
 * <p>Without this, TWO collision solvers fight over the same entity every tick: VS2's
 * {@code collideWithShips} (a WrapOperation around the {@code collide} call inside
 * {@code Entity.move}) adjusts the movement against the tilted ship's world-space polygons BEFORE
 * our ship-local sweep sees it. Its solver pushes the world-aligned AABB out of the tilted deck it
 * slightly penetrates, injecting large along-deck movement components (observed: +0.74 blocks/tick)
 * that our solver then can't attribute to gravity - the net effect is the entity accelerating and
 * sliding off the ship. Skipping VS2's adjustment (returning the movement unchanged) also prevents
 * it from registering {@code lastShipStoodOn}, so VS2's entity dragging stops teleporting the
 * entity out from under our resolution as well.
 */
@Pseudo
@Mixin(targets = "org.valkyrienskies.mod.common.util.EntityShipCollisionUtils", remap = false)
public class VS2EntityShipCollisionUtilsMixin {

    @Inject(method = "adjustEntityMovementForShipCollisions", at = @At("HEAD"), cancellable = true, remap = false)
    private void gravityapi$skipShipCollisionForArbitraryGravity(
        Entity entity, Vec3 movement, AABB entityBoundingBox, Level world,
        CallbackInfoReturnable<Vec3> cir
    ) {
        // entity is nullable on VS2's side.
        // STRICT check: only skip VS2's collision when ship gravity physics is genuinely active.
        // The loose isArbitraryGravity() is true for anyone merely near a ship, and skipping VS2
        // for them removes their only ship collision (vibration / forcefield push at the hull).
        if (entity != null && GravityChangerAPI.isShipGravityPhysics(entity)) {
            if (entity instanceof Player && ShipCollisionDebug.shouldLog(entity)) {
                ShipCollisionDebug.log("[vs2skip] {} skipped VS2 ship polygon collision, movement={}",
                        entity.level().isClientSide() ? "C" : "S", movement);
            }
            cir.setReturnValue(movement);
        }
    }
}
