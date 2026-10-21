package net.memezforbeanz.gravityapi;

import org.apache.commons.lang3.Validate;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.memezforbeanz.gravityapi.util.QuaternionUtil;
import net.memezforbeanz.gravityapi.util.RotationUtil;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

public class RotationAnimation {
    private boolean inAnimation = false;
    private Quaternionf startGravityRotation;
    private Quaternionf endGravityRotation;
    private Vec3 relativeRotationCenter = Vec3.ZERO;
    
    private long startTimeMs;
    private long endTimeMs;
    
    public void startRotationAnimation(
        Direction newGravity, Direction prevGravity,
        long durationTimeMs, Entity entity, long timeMs,
        boolean rotateView, Vec3 relativeRotationCenter
    ) {
        if (durationTimeMs == 0) {
            inAnimation = false;
            return;
        }
        
        Validate.notNull(entity);
        
        Vec3 newLookingDirection = getNewLookingDirection(newGravity, prevGravity, entity, rotateView);
        
        Quaternionf oldViewRotation = QuaternionUtil.getViewRotation(entity.getXRot(), entity.getYRot());
        
        update(timeMs);
        Quaternionf currentAnimatedGravityRotation = getCurrentGravityRotation(prevGravity, timeMs);
        
        // camera rotation = view rotation(pitch and yaw) * gravity rotation(animated)
        Quaternionf currentAnimatedCameraRotation = new Quaternionf().set(oldViewRotation).mul(currentAnimatedGravityRotation);
        
        Quaternionf newEndGravityRotation = RotationUtil.getWorldRotationQuaternion(newGravity);
        
        Vec2 newYawAndPitch = RotationUtil.vecToRot(
            RotationUtil.vecWorldToPlayer(newLookingDirection, newGravity)
        );
        float newPitch = newYawAndPitch.y;
        float newYaw = newYawAndPitch.x;
        float deltaYaw = newYaw - entity.getYRot();
        float deltaPitch = newPitch - entity.getXRot();
        entity.setYRot(entity.getYRot() + deltaYaw);
        entity.setXRot(entity.getXRot() + deltaPitch);
        entity.yRotO += deltaYaw;
        entity.xRotO += deltaPitch;
        if (entity instanceof LivingEntity livingEntity) {
            livingEntity.yBodyRot += deltaYaw;
            livingEntity.yBodyRotO += deltaYaw;
            livingEntity.yHeadRot += deltaYaw;
            livingEntity.yHeadRotO += deltaYaw;
        }
        
        Quaternionf newViewRotation = QuaternionUtil.getViewRotation(entity.getXRot(), entity.getYRot());
        
        // gravity rotation = (view rotation^-1) * camera rotation
        Quaternionf animationStartGravityRotation = new Quaternionf().set(newViewRotation).conjugate().mul(currentAnimatedCameraRotation);
        
        this.relativeRotationCenter = relativeRotationCenter;
        inAnimation = true;
        startGravityRotation = animationStartGravityRotation;
        endGravityRotation = newEndGravityRotation;
        startTimeMs = timeMs;
        endTimeMs = timeMs + durationTimeMs;
    }
    
    private Vec3 getNewLookingDirection(
        Direction newGravity, Direction prevGravity, Entity player,
        boolean rotateView
    ) {
        Vec3 oldLookingDirection = RotationUtil.vecPlayerToWorld(
            RotationUtil.rotToVec(player.getYRot(), player.getXRot()),
            prevGravity
        );
    
        if (!rotateView) {
            return oldLookingDirection;
        }
        
        if (newGravity == prevGravity.getOpposite()) {
            return oldLookingDirection.scale(-1);
        }
        
        Quaternionf deltaRotation = QuaternionUtil.getRotationBetween(
            Vec3.atLowerCornerOf(prevGravity.getNormal()),
            Vec3.atLowerCornerOf(newGravity.getNormal())
        );
        
        Vector3f lookingDirection = new Vector3f((float) oldLookingDirection.x, (float) oldLookingDirection.y, (float) oldLookingDirection.z);
        lookingDirection.rotate(deltaRotation);
        Vec3 newLookingDirection = new Vec3(lookingDirection);
        return newLookingDirection;
    }
    
    /**
     * It returns the rotation that applies to world for rendering.
     * To get the rotation that applies entity, conjugate it.
     */
    public Quaternionf getCurrentGravityRotation(Direction currentGravity, long timeMs) {
        
        update(timeMs);
        
        if (!inAnimation) {
            return RotationUtil.getWorldRotationQuaternion(currentGravity);
        }
        
        double delta = (double) (timeMs - startTimeMs) / (endTimeMs - startTimeMs);
        
        return RotationUtil.interpolate(
            startGravityRotation, endGravityRotation,
            mapProgress((float) delta)
        );
    }
    
    public void update(long timeMs) {
        if (timeMs > endTimeMs) {
            inAnimation = false;
        }
    }
    
    /**
     * When doing gravity flipping, the rotation center is the player bounding box center.
     * But the player feet pos changes abruptly. So we need special calculation to eye offset.
     *
     * Note when rotateView is false, it will cause non-smooth eye offset change
     */
    public Vec3 getEyeOffset(
        Quaternionf gravityRot, Vec3 localEyeOffset, Direction newGravity
    ) {
        Quaternionf gravityRotForEntity = new Quaternionf(gravityRot).conjugate();
        
        if (!inAnimation || relativeRotationCenter.equals(Vec3.ZERO)) {
            return QuaternionUtil.rotate(localEyeOffset, gravityRotForEntity);
        }
        
        Vec3 rotationCenterOffset = RotationUtil.vecPlayerToWorld(relativeRotationCenter, newGravity);
        
        Vec3 eyeOffsetFromRotationCenter = localEyeOffset.subtract(relativeRotationCenter);
        Vec3 rotatedEyeOffsetFromRotationCenter =
            QuaternionUtil.rotate(eyeOffsetFromRotationCenter, gravityRotForEntity);
        
        return rotationCenterOffset.add(rotatedEyeOffsetFromRotationCenter);
    }
    
    /**
     * Smootherstep (zero velocity AND zero acceleration at both ends): the rotation eases
     * in gently, sweeps, and settles without the abrupt onset smoothstep has — the Mario
     * Galaxy feel for gravity flips.
     */
    private static float mapProgress(float delta) {
        delta = Mth.clamp(delta, 0, 1);
        return delta * delta * delta * (delta * (delta * 6 - 15) + 10);
    }
    
    public boolean isInAnimation() {
        return inAnimation;
    }

    /**
     * Start a smooth rotation animation for arbitrary gravity directions (VS ship support).
     * This handles smooth transitions when the ship rotates but stays within the same cardinal direction.
     */
    public void startArbitraryRotationAnimation(
        net.memezforbeanz.gravityapi.api.GravityDirection newGravity,
        net.memezforbeanz.gravityapi.api.GravityDirection prevGravity,
        long durationTimeMs, Entity entity, long timeMs
    ) {
        if (durationTimeMs == 0 || newGravity.equals(prevGravity)) {
            return;
        }

        // Retargets arrive every tick while a ship rotates continuously; keep them short
        // but not instant so consecutive segments blend into one motion
        long actualDuration = Math.max(100, Math.min(durationTimeMs, 300));

        update(timeMs);

        // Chain from whatever rotation is currently on screen (mid-animation included) so
        // per-tick retargeting never snaps back to the raw previous frame
        Quaternionf currentRotation = getCurrentGravityRotation(prevGravity, timeMs);
        Quaternionf newRotation = new Quaternionf(newGravity.getWorldToPlayerRotation());

        this.relativeRotationCenter = Vec3.ZERO;
        inAnimation = true;
        startGravityRotation = currentRotation;
        endGravityRotation = newRotation;
        startTimeMs = timeMs;
        endTimeMs = timeMs + actualDuration;
    }

    /**
     * Get the current gravity rotation for arbitrary gravity directions.
     * This supports smooth interpolation for VS ship rotations.
     */
    public Quaternionf getCurrentGravityRotation(net.memezforbeanz.gravityapi.api.GravityDirection currentGravity, long timeMs) {
        update(timeMs);

        if (!inAnimation) {
            // Ship-anchored gravity follows the ship's render transform per frame
            return net.memezforbeanz.gravityapi.compat.vs2.VS2Helper.renderWorldToPlayerRotation(currentGravity);
        }

        double delta = (double) (timeMs - startTimeMs) / (endTimeMs - startTimeMs);

        return RotationUtil.interpolate(
            startGravityRotation, endGravityRotation,
            mapProgress((float) delta)
        );
    }
}
