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
 * Makes VS2's entity dragger correct for entities under ship gravity.
 * <ul>
 *     <li><b>Impulses</b>: the dragger hands ship motion back as velocity ({@code Entity.push})
 *     in WORLD space; under changed gravity velocity lives in the gravity-local frame (that's
 *     how {@code push} is treated everywhere else in this mod), so convert it.</li>
 *     <li><b>Yaw</b>: the dragger turns entities with the ship by a world-space yaw delta
 *     (look vector projected onto the world-horizontal plane). Under ship gravity yaw is
 *     gravity-local and the camera already follows the ship's tilt, so on a tilted, turning
 *     ship that delta is wrong and the view swings. Undo VS2's yaw change and instead rotate
 *     the facing by the ship's actual rotation, re-expressed in the new gravity frame.</li>
 * </ul>
 */
@Mixin(value = EntityDragger.class, remap = false)
public abstract class VS2EntityDraggerMixin {

    /**
     * Ship-gravity entities' rotations captured before VS2 drags them this call. Per thread: in
     * singleplayer the dragger runs concurrently on the integrated server and client threads.
     */
    @Unique
    private static final ThreadLocal<Map<Entity, float[]>> gravityapi$rotationsBeforeDrag =
            ThreadLocal.withInitial(IdentityHashMap::new);

    /**
     * The "boarded a different ship" impulse ({@code addedMovementLastTick - addedMovement}),
     * skipped for ship-gravity entities. VS2 decides an entity left its ship whenever its
     * {@code collide} result differs from the requested movement (it assumes that was world
     * terrain) - but our ship-gravity collision resolves the SHIP deck inside that same call, so
     * every tick the deck stops the fall VS2 clears {@code lastShipStoodOn}, our ground-stick
     * restores it, and VS2 sees a fresh boarding. The impulse then fed every uneven ship step
     * back as a velocity kick (±0.05 alternating on a fast-turning ship): the motion stutter.
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
                float bodyRot = entity instanceof LivingEntity living ? living.yBodyRot : 0.0F;
                rotations.put(entity, new float[]{entity.getYRot(), entity.getYHeadRot(), bodyRot});
            }
        }
    }

    @Inject(method = "dragEntitiesWithShips", at = @At("TAIL"))
    private void gravityapi$correctYaw(Iterable<Entity> entities, boolean preTick, CallbackInfo ci) {
        Map<Entity, float[]> rotations = gravityapi$rotationsBeforeDrag.get();
        for (Map.Entry<Entity, float[]> entry : rotations.entrySet()) {
            Entity entity = entry.getKey();
            float[] before = entry.getValue();
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

    /**
     * Local-yaw change that keeps the entity facing the same way relative to its ship across
     * this tick's ship rotation: rotate the world-space facing with the ship and re-read it in
     * the gravity frame the ship rotation produces.
     */
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
