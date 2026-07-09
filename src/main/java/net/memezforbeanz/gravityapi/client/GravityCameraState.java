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

    // Time constants for the exponential low-pass filter: the smoothed rotation closes
    // ~63% of the gap to the target per tau. Time-based so the feel is identical at any
    // framerate (per-frame slerp constants are snappy at 240fps and sluggish at 30fps).
    private static final float TRACK_TAU_MS = 60.0f;
    private static final float RETURN_TO_IDENTITY_TAU_MS = 150.0f;

    /** Above this frame gap (pause, freeze, world switch) snap instead of easing. */
    private static final long SNAP_DT_MS = 250;
    
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

        // Check if this is a new frame (avoid multiple smoothing per frame)
        if (timeMs == lastUpdateTime && lastSmoothedRotation != null) {
            return new Quaternionf(lastSmoothedRotation);
        }
        long dtMs = lastUpdateTime == 0 ? Long.MAX_VALUE : timeMs - lastUpdateTime;
        lastUpdateTime = timeMs;

        // If gravity is down, smoothly transition back to identity rotation
        if (gravityDirection.equals(GravityDirection.DOWN)) {
            if (lastSmoothedRotation == null) {
                return new Quaternionf();
            }
            Quaternionf smoothed = new Quaternionf(lastSmoothedRotation)
                    .slerp(new Quaternionf(), smoothingAlpha(dtMs, RETURN_TO_IDENTITY_TAU_MS));

            // Check if we're close enough to identity to reset completely
            if (isCloseToIdentity(smoothed)) {
                reset();
                return new Quaternionf();
            }

            lastSmoothedRotation = new Quaternionf(smoothed);
            lastGravityDirection = null;
            return smoothed;
        }

        // Get the target rotation
        Quaternionf targetRotation = getTargetRotation(entity, gravityDirection, timeMs);

        Quaternionf smoothedRotation;
        if (lastSmoothedRotation == null) {
            smoothedRotation = new Quaternionf(targetRotation);
        } else {
            // The RotationAnimation already provides the path for big flips; this filter
            // only irons out per-tick retargets and sync jitter on top of it
            smoothedRotation = new Quaternionf(lastSmoothedRotation)
                    .slerp(targetRotation, smoothingAlpha(dtMs, TRACK_TAU_MS));
        }

        lastSmoothedRotation = new Quaternionf(smoothedRotation);
        lastGravityDirection = gravityDirection;

        return smoothedRotation;
    }

    /** Framerate-independent low-pass coefficient: 1 - e^(-dt/tau), snapping on long gaps. */
    private static float smoothingAlpha(long dtMs, float tauMs) {
        if (dtMs <= 0) {
            return 0.0f;
        }
        if (dtMs > SNAP_DT_MS) {
            return 1.0f;
        }
        return 1.0f - (float) Math.exp(-dtMs / tauMs);
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

