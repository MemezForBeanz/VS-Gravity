package net.memezforbeanz.gravityapi.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.api.GravityDirection;
import net.memezforbeanz.gravityapi.compat.vs2.VS2Helper;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.valkyrienskies.mod.common.util.EntityDragger;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Makes VS2's entity dragger correct under ship gravity:
 * <ul>
 *     <li>impulses: VS2 pushes in world space; velocity here is in the gravity frame;</li>
 *     <li>yaw: VS2 turns entities by a world-yaw delta, wrong under ship gravity. Ship-anchored
 *     frames are locked to the ship, so VS2's change is undone; other ship gravity turns the
 *     facing by the ship's actual rotation.</li>
 * </ul>
 */
@Mixin(value = EntityDragger.class, remap = false)
public abstract class VS2EntityDraggerMixin {

    /** Rotations captured before VS2 drags; per thread (the integrated server and client drag concurrently). */
    @Unique
    private static final ThreadLocal<Map<Entity, float[]>> gravityapi$rotationsBeforeDrag =
            ThreadLocal.withInitial(IdentityHashMap::new);

    /**
     * Skip the "boarded a different ship" impulse for ship-gravity entities: our collision resolves
     * the deck inside the same collide call, so VS2 saw a fresh boarding every tick and fed each
     * uneven step back as a velocity kick.
     */
    @WrapOperation(
            method = "dragEntitiesWithShips",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;push(DDD)V", ordinal = 0, remap = true)
    )
    private void gravityapi$skipBoardingImpulse(Entity entity, double x, double y, double z, Operation<Void> original) {
        if (GravityChangerAPI.isShipGravityPhysics(entity)) {
            return;
        }
        gravityapi$pushInGravityFrame(entity, x, y, z, original);
    }

    /** Leaving the ship: hand the ship's motion over as velocity, in the gravity frame. */
    @WrapOperation(
            method = "dragEntitiesWithShips",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;push(DDD)V", ordinal = 1, remap = true)
    )
    private void gravityapi$leavingImpulse(Entity entity, double x, double y, double z, Operation<Void> original) {
        gravityapi$pushInGravityFrame(entity, x, y, z, original);
    }

    @Unique
    private static void gravityapi$pushInGravityFrame(Entity entity, double x, double y, double z, Operation<Void> original) {
        GravityDirection gravity = GravityChangerAPI.getGravityDirectionVec(entity);
        if (gravity.equals(GravityDirection.DOWN)) {
            original.call(entity, x, y, z);
            return;
        }
        Vec3 local = gravity.vecWorldToPlayer(new Vec3(x, y, z));
        original.call(entity, local.x, local.y, local.z);
    }

    @Inject(method = "dragEntitiesWithShips", at = @At("HEAD"))
    private void gravityapi$captureRotations(Iterable<Entity> entities, boolean preTick, CallbackInfo ci) {
        Map<Entity, float[]> rotations = gravityapi$rotationsBeforeDrag.get();
        rotations.clear();
        for (Entity entity : entities) {
            if (GravityChangerAPI.isShipGravityPhysics(entity)) {
                LivingEntity living = entity instanceof LivingEntity l ? l : null;
                rotations.put(entity, new float[]{entity.getYRot(), entity.getYHeadRot(),
                        living != null ? living.yBodyRot : 0.0F, entity.yRotO,
                        living != null ? living.yHeadRotO : 0.0F, living != null ? living.yBodyRotO : 0.0F});
            }
        }
    }

    @Inject(method = "dragEntitiesWithShips", at = @At("TAIL"))
    private void gravityapi$correctYaw(Iterable<Entity> entities, boolean preTick, CallbackInfo ci) {
        Map<Entity, float[]> rotations = gravityapi$rotationsBeforeDrag.get();
        for (Map.Entry<Entity, float[]> entry : rotations.entrySet()) {
            Entity entity = entry.getKey();
            float[] before = entry.getValue();
            if (GravityChangerAPI.getGravityDirectionVec(entity).isShipAnchored()) {
                // The frame is locked to the ship, so the local yaw already turns with it: undo VS2's drag
                entity.setYRot(before[0]);
                entity.setYHeadRot(before[1]);
                entity.yRotO = before[3];
                if (entity instanceof LivingEntity living) {
                    living.yBodyRot = before[2];
                    living.yHeadRotO = before[4];
                    living.yBodyRotO = before[5];
                }
                continue;
            }
            float vsAddedYaw = Mth.wrapDegrees(entity.getYRot() - before[0]);
            float correctYaw = gravityapi$shipYawDelta(entity, before[0]);
            float fix = correctYaw - vsAddedYaw;
            if (Math.abs(fix) < 1.0E-4F) {
                continue;
            }
            entity.setYRot(entity.getYRot() + fix);
            entity.setYHeadRot(entity.getYHeadRot() + fix);
            if (entity instanceof LivingEntity living) {
                living.yBodyRot += fix;
            }
        }
        rotations.clear();
    }

    /** Local-yaw change keeping the entity's facing relative to its ship across this tick's ship rotation. */
    @Unique
    private static float gravityapi$shipYawDelta(Entity entity, float yaw) {
        Quaterniond delta = VS2Helper.getShipRotationDeltaThisTick(entity);
        if (delta == null) {
            return 0.0F;
        }
        Vec3 oldGravity = GravityChangerAPI.getGravityDirectionVec(entity).getVector();
        Vector3d rotatedGravity = delta.transform(new Vector3d(oldGravity.x, oldGravity.y, oldGravity.z));
        Quaternionf oldPlayerToWorld = GravityDirection.shipRelative(oldGravity).getPlayerToWorldRotation();
        Quaternionf newWorldToPlayer = GravityDirection.shipRelative(
                new Vec3(rotatedGravity.x, rotatedGravity.y, rotatedGravity.z)).getWorldToPlayerRotation();

        double yawRad = Math.toRadians(yaw);
        Vector3f facing = new Vector3f((float) -Math.sin(yawRad), 0.0F, (float) Math.cos(yawRad));
        facing.rotate(oldPlayerToWorld);
        Vector3d worldFacing = delta.transform(new Vector3d(facing.x, facing.y, facing.z));
        Vector3f newLocal = new Vector3f((float) worldFacing.x, (float) worldFacing.y, (float) worldFacing.z)
                .rotate(newWorldToPlayer);
        if (newLocal.x * newLocal.x + newLocal.z * newLocal.z < 1.0E-8F) {
            return 0.0F;
        }
        float newYaw = (float) Math.toDegrees(Math.atan2(-newLocal.x, newLocal.z));
        return Mth.wrapDegrees(newYaw - yaw);
    }
}
