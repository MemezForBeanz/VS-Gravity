package net.memezforbeanz.gravityapi.client;

import net.memezforbeanz.gravityapi.RotationAnimation;
import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.api.GravityDirection;
import net.memezforbeanz.gravityapi.compat.vs2.VS2Helper;
import net.minecraft.world.entity.Entity;
import org.joml.Quaternionf;
import net.minecraft.util.Mth;

/** The smoothed camera gravity rotation, shared by GameRendererMixin and CameraMixin so they never disagree. */
public class GravityCameraState {
    
    private static Quaternionf lastSmoothedRotation = null;
    private static GravityDirection lastGravityDirection = null;
    private static long lastUpdateTime = 0;

    // Low-pass time constants (the smoothed rotation closes ~63% of the gap per tau), framerate independent
    private static final float TRACK_TAU_MS = 60.0f;
    private static final float RETURN_TO_IDENTITY_TAU_MS = 150.0f;

    /** Above this frame gap (pause, freeze, world switch) snap instead of easing. */
    private static final long SNAP_DT_MS = 250;
    
    /** The smoothed gravity rotation for rendering this frame (computed once per frame). */
    public static Quaternionf getSmoothedGravityRotation(Entity entity, long timeMs) {
        if (entity == null) {
            return new Quaternionf();
        }
        
        GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec(entity);

        if (timeMs == lastUpdateTime && lastSmoothedRotation != null) {
            return new Quaternionf(lastSmoothedRotation);
        }
        long dtMs = lastUpdateTime == 0 ? Long.MAX_VALUE : timeMs - lastUpdateTime;
        lastUpdateTime = timeMs;

        RotationAnimation animation = GravityChangerAPI.getRotationAnimation(entity);
        boolean animating = animation != null && animation.isInAnimation();

        // Gravity down and no animation: ease back to identity. While an animation runs (a flip back
        // to DOWN), track it instead so the camera follows the model's sweep
        Quaternionf tilt = GravityChangerAPI.getVisualTilt(entity, partialTick(entity, timeMs));
        if (gravityDirection.equals(GravityDirection.DOWN) && !animating && tilt == null) {
            if (lastSmoothedRotation == null) {
                return new Quaternionf();
            }
            Quaternionf smoothed = new Quaternionf(lastSmoothedRotation)
                    .slerp(new Quaternionf(), smoothingAlpha(dtMs, RETURN_TO_IDENTITY_TAU_MS));

            if (isCloseToIdentity(smoothed)) {
                reset();
                return new Quaternionf();
            }

            lastSmoothedRotation = new Quaternionf(smoothed);
            lastGravityDirection = null;
            return smoothed;
        }

        Quaternionf targetRotation = getTargetRotation(entity, gravityDirection, timeMs);
        if (tilt != null) {
            // Free-fall tilt: tilt^-1 * worldToPlayer
            targetRotation = new Quaternionf(tilt).conjugate().mul(targetRotation);
        }

        Quaternionf smoothedRotation;
        if (lastSmoothedRotation == null
                || (gravityDirection.isShipAnchored() && (!animating || animation.isShipRelative()))) {
            // On a ship the target already follows the ship's render transform per frame; filtering
            // would only make the view trail a spinning ship
            smoothedRotation = new Quaternionf(targetRotation);
        } else {
            // The animation provides the path for big flips; the filter only irons out retargets and jitter
            smoothedRotation = new Quaternionf(lastSmoothedRotation)
                    .slerp(targetRotation, smoothingAlpha(dtMs, TRACK_TAU_MS));
        }

        lastSmoothedRotation = new Quaternionf(smoothedRotation);
        lastGravityDirection = gravityDirection;

        return smoothedRotation;
    }

    /** The frame's position within the current tick, recovered from the render time. */
    private static float partialTick(Entity entity, long timeMs) {
        return Mth.clamp((timeMs - entity.level().getGameTime() * 50) / 50.0f, 0.0f, 1.0f);
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
    
    private static boolean isCloseToIdentity(Quaternionf q) {
        float threshold = 0.01f;
        return Math.abs(q.x) < threshold &&
               Math.abs(q.y) < threshold &&
               Math.abs(q.z) < threshold &&
               Math.abs(q.w - 1.0f) < threshold;
    }

    private static Quaternionf getTargetRotation(Entity entity, GravityDirection gravityDirection, long timeMs) {
        RotationAnimation animation = GravityChangerAPI.getRotationAnimation(entity);
        
        if (animation != null && animation.isInAnimation()) {
            return animation.getCurrentGravityRotation(gravityDirection, timeMs);
        } else {
            // No animation: the direction's rotation (anchored: from the ship's render transform)
            return VS2Helper.renderWorldToPlayerRotation(gravityDirection);
        }
    }
    
    /** Reset the camera state. */
    public static void reset() {
        lastSmoothedRotation = null;
        lastGravityDirection = null;
        lastUpdateTime = 0;
    }
    
    public static boolean hasCachedRotation() {
        return lastSmoothedRotation != null;
    }
    
    /** The last smoothed rotation, without updating. */
    public static Quaternionf getLastSmoothedRotation() {
        return lastSmoothedRotation != null ? new Quaternionf(lastSmoothedRotation) : new Quaternionf();
    }
}

