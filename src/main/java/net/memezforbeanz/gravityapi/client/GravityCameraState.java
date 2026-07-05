package net.memezforbeanz.gravityapi.client;

import net.memezforbeanz.gravityapi.RotationAnimation;
import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.api.GravityDirection;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;

/**
 * Shared state for camera gravity rotation.
 * This ensures both GameRendererMixin and CameraMixin use the same smoothed rotation
 * to prevent camera fighting and jitter.
 */
public class GravityCameraState {
    
    private static Quaternionf lastSmoothedRotation = null;
    private static GravityDirection lastGravityDirection = null;
    private static long lastUpdateTime = 0;
    
    // Smoothing factor - higher = snappier, lower = smoother but more lag
    private static final float SMOOTHING_FACTOR = 0.35f;
    
    // Threshold for considering gravity directions "the same" (prevents micro-jitter)
    private static final double DIRECTION_THRESHOLD = 0.001;
    
    /**
     * Get the smoothed gravity rotation for rendering.
     * This method should be called once per frame and the result cached.
     * 
     * @param entity The entity to get gravity for
     * @param timeMs Current time in milliseconds
     * @return The smoothed gravity rotation quaternion
     */
    public static Quaternionf getSmoothedGravityRotation(Entity entity, long timeMs) {
        if (entity == null) {
            return new Quaternionf();
        }
        
        GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec(entity);
        
        // If gravity is down, smoothly transition back to identity rotation
        if (gravityDirection.equals(GravityDirection.DOWN)) {
            if (lastSmoothedRotation == null) {
                return new Quaternionf();
            }
            // Smoothly transition back to identity
            Quaternionf identity = new Quaternionf();
            Quaternionf smoothed = new Quaternionf(lastSmoothedRotation).slerp(identity, 0.2f);

            // Check if we're close enough to identity to reset completely
            if (isCloseToIdentity(smoothed)) {
                reset();
                return new Quaternionf();
            }

            lastSmoothedRotation = new Quaternionf(smoothed);
            lastGravityDirection = null;
            lastUpdateTime = timeMs;
            return smoothed;
        }
        
        // Get the target rotation
        Quaternionf targetRotation = getTargetRotation(entity, gravityDirection, timeMs);
        
        // Check if this is a new frame (avoid multiple smoothing per frame)
        if (timeMs == lastUpdateTime && lastSmoothedRotation != null) {
            return new Quaternionf(lastSmoothedRotation);
        }
        lastUpdateTime = timeMs;
        
        // Apply smoothing
        Quaternionf smoothedRotation;
        if (lastSmoothedRotation == null || hasGravityDirectionChangedSignificantly(gravityDirection)) {
            // First frame or significant change - start fresh but still smooth
            if (lastSmoothedRotation == null) {
                smoothedRotation = new Quaternionf(targetRotation);
            } else {
                // Significant change - use faster smoothing to catch up
                smoothedRotation = new Quaternionf(lastSmoothedRotation).slerp(targetRotation, 0.5f);
            }
        } else {
            // Normal smoothing
            smoothedRotation = new Quaternionf(lastSmoothedRotation).slerp(targetRotation, SMOOTHING_FACTOR);
        }
        
        lastSmoothedRotation = new Quaternionf(smoothedRotation);
        lastGravityDirection = gravityDirection;
        
        return smoothedRotation;
    }
    
    /**
     * Check if a quaternion is close enough to identity to be considered "no rotation"
     */
    private static boolean isCloseToIdentity(Quaternionf q) {
        // Identity quaternion is (0, 0, 0, 1)
        float threshold = 0.01f;
        return Math.abs(q.x) < threshold &&
               Math.abs(q.y) < threshold &&
               Math.abs(q.z) < threshold &&
               Math.abs(q.w - 1.0f) < threshold;
    }

    /**
     * Get the target (un-smoothed) gravity rotation.
     */
    private static Quaternionf getTargetRotation(Entity entity, GravityDirection gravityDirection, long timeMs) {
        RotationAnimation animation = GravityChangerAPI.getRotationAnimation(entity);
        
        if (animation != null && animation.isInAnimation()) {
            // During animation, use the animation's interpolated rotation
            return animation.getCurrentGravityRotation(gravityDirection, timeMs);
        } else {
            // No animation - use the gravity direction's quaternion directly
            return gravityDirection.getWorldToPlayerRotation();
        }
    }
    
    /**
     * Check if the gravity direction has changed significantly.
     */
    private static boolean hasGravityDirectionChangedSignificantly(GravityDirection newDirection) {
        if (lastGravityDirection == null) {
            return true;
        }
        
        // Compare the vectors
        double distSq = lastGravityDirection.getVector().distanceToSqr(newDirection.getVector());
        return distSq > DIRECTION_THRESHOLD;
    }
    
    /**
     * Reset the camera state (e.g., when returning to normal gravity).
     */
    public static void reset() {
        lastSmoothedRotation = null;
        lastGravityDirection = null;
        lastUpdateTime = 0;
    }
    
    /**
     * Check if we have a cached rotation.
     */
    public static boolean hasCachedRotation() {
        return lastSmoothedRotation != null;
    }
    
    /**
     * Get the last smoothed rotation without updating.
     * Use this for secondary systems that need the same rotation.
     */
    public static Quaternionf getLastSmoothedRotation() {
        return lastSmoothedRotation != null ? new Quaternionf(lastSmoothedRotation) : new Quaternionf();
    }
}

