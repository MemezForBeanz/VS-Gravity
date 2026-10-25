package net.memezforbeanz.gravityapi;

import org.apache.commons.lang3.Validate;
import org.joml.Quaternionf;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3f;

import net.memezforbeanz.gravityapi.api.GravityDirection;
import net.memezforbeanz.gravityapi.compat.vs2.VS2Helper;

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
    /** Set while animating between ship-anchored directions: start and end are then relative to the ship (world-to-player times ship-to-world) and follow its render rotation. */
    @Nullable
    private GravityDirection shipFrame = null;
    
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
        foldVisualTilt(currentAnimatedGravityRotation, entity);
        
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
        shipFrame = null;
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
        return interpolated(timeMs);
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
        
        Vec3 rotationCenterOffset = shipFrame != null
            ? shipFrame.vecPlayerToWorld(relativeRotationCenter)
            : RotationUtil.vecPlayerToWorld(relativeRotationCenter, newGravity);
        
        Vec3 eyeOffsetFromRotationCenter = localEyeOffset.subtract(relativeRotationCenter);
        Vec3 rotatedEyeOffsetFromRotationCenter =
            QuaternionUtil.rotate(eyeOffsetFromRotationCenter, gravityRotForEntity);
        
        return rotationCenterOffset.add(rotatedEyeOffsetFromRotationCenter);
    }
    
    /** Smootherstep: zero velocity and acceleration at both ends. */
    private static float mapProgress(float delta) {
        delta = Mth.clamp(delta, 0, 1);
        return delta * delta * delta * (delta * (delta * 6 - 15) + 10);
    }
    
    /** Fold a field free fall's visual tilt into the start rotation (and clear it), so the animation starts from what is on screen. */
    private static void foldVisualTilt(Quaternionf worldToPlayer, Entity entity) {
        Quaternionf tilt = net.memezforbeanz.gravityapi.api.GravityChangerAPI.consumeVisualTilt(entity);
        if (tilt != null) {
            worldToPlayer.premul(tilt.conjugate());
        }
    }

    public boolean isInAnimation() {
        return inAnimation;
    }

    /** Smooth rotation animation for arbitrary (VS ship) gravity directions. */
    public void startArbitraryRotationAnimation(
        net.memezforbeanz.gravityapi.api.GravityDirection newGravity,
        net.memezforbeanz.gravityapi.api.GravityDirection prevGravity,
        long durationTimeMs, Entity entity, long timeMs
    ) {
        if (durationTimeMs == 0 || newGravity.equals(prevGravity)) {
            return;
        }

        // Retargets arrive every tick on a rotating ship: short, but long enough to blend
        long actualDuration = Math.max(100, Math.min(durationTimeMs, 300));

        update(timeMs);

        // Chain from what is on screen, mid-animation included
        Quaternionf currentRotation = getCurrentGravityRotation(prevGravity, timeMs);
        foldVisualTilt(currentRotation, entity);
        Quaternionf newRotation = VS2Helper.renderWorldToPlayerRotation(newGravity);

        this.relativeRotationCenter = Vec3.ZERO;
        begin(currentRotation, newRotation, newGravity, timeMs, actualDuration);
    }

    /** The flip between two ship-anchored faces: from what is on screen ({@code oldView} times the current rotation, tilt folded in), re-based on the view the flip turned to. */
    public void startShipFaceAnimation(
        GravityDirection newGravity, GravityDirection prevGravity, Quaternionf oldView,
        long durationTimeMs, Entity entity, long timeMs, Vec3 relativeRotationCenter
    ) {
        if (durationTimeMs == 0) {
            inAnimation = false;
            return;
        }
        update(timeMs);
        Quaternionf current = getCurrentGravityRotation(prevGravity, timeMs);
        foldVisualTilt(current, entity);
        // Keep the camera (view * gravity rotation) under the new view
        Quaternionf camera = new Quaternionf(oldView).mul(current);
        Quaternionf newView = QuaternionUtil.getViewRotation(entity.getXRot(), entity.getYRot());
        Quaternionf start = newView.conjugate().mul(camera);
        this.relativeRotationCenter = relativeRotationCenter;
        begin(start, VS2Helper.renderWorldToPlayerRotation(newGravity), newGravity, timeMs, durationTimeMs);
    }

    /** Animate between two world-to-player rotations taken now; ship-relative when anchored. */
    private void begin(Quaternionf start, Quaternionf end, GravityDirection newGravity, long timeMs, long durationTimeMs) {
        Quaterniond shipToWorld = newGravity.isShipAnchored() ? VS2Helper.anchoredShipToWorld(newGravity, true) : null;
        if (shipToWorld != null) {
            Quaternionf shipToWorldF = toFloat(shipToWorld);
            start = new Quaternionf(start).mul(shipToWorldF);
            end = new Quaternionf(end).mul(shipToWorldF);
            shipFrame = newGravity;
        } else {
            shipFrame = null;
        }
        inAnimation = true;
        startGravityRotation = start;
        endGravityRotation = end;
        startTimeMs = timeMs;
        endTimeMs = timeMs + durationTimeMs;
    }

    /** The animated world-to-player rotation at {@code timeMs}. */
    private Quaternionf interpolated(long timeMs) {
        double delta = (double) (timeMs - startTimeMs) / (endTimeMs - startTimeMs);
        Quaternionf rotation = RotationUtil.interpolate(startGravityRotation, endGravityRotation, mapProgress((float) delta));
        if (shipFrame != null) {
            Quaterniond shipToWorld = VS2Helper.anchoredShipToWorld(shipFrame, true);
            if (shipToWorld != null) {
                return new Quaternionf(rotation).mul(toFloat(shipToWorld).conjugate());
            }
        }
        return rotation;
    }

    /** Whether the running animation follows a ship. */
    public boolean isShipRelative() {
        return inAnimation && shipFrame != null;
    }

    private static Quaternionf toFloat(Quaterniond q) {
        return new Quaternionf((float) q.x, (float) q.y, (float) q.z, (float) q.w);
    }

    /** The current gravity rotation for arbitrary (VS ship) gravity. */
    public Quaternionf getCurrentGravityRotation(net.memezforbeanz.gravityapi.api.GravityDirection currentGravity, long timeMs) {
        update(timeMs);

        if (!inAnimation) {
            // Ship-anchored gravity follows the ship's render transform per frame
            return VS2Helper.renderWorldToPlayerRotation(currentGravity);
        }
        return interpolated(timeMs);
    }
}
