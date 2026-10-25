package net.memezforbeanz.gravityapi.mixin.client;

import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.memezforbeanz.gravityapi.RotationAnimation;
import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.api.GravityDirection;
import net.memezforbeanz.gravityapi.client.GravityCameraState;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;

/**
 * Rotates the view into the camera entity's gravity frame. Injects into renderLevel instead of replacing it,
 * so the loader's camera/render-stage events and other mods' hooks there (shaders, renderers) still run.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	@Shadow
	@Final
	private Camera mainCamera;

	@Shadow
	@Final
	Minecraft minecraft;

	/** This frame's gravity rotation, or null while vanilla's camera is left alone. */
	@Unique
	private Quaternionf gravityapi$rotation;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void gravityapi$prepare(float partialTick, long finishNanos, PoseStack viewStack, CallbackInfo ci) {
        gravityapi$rotation = null;
        Entity focusedEntity = this.mainCamera.getEntity();
        if (focusedEntity == null) return;
        GravityDirection gravityDirection = GravityChangerAPI.getGravityDirectionVec(focusedEntity);
        RotationAnimation animation = GravityChangerAPI.getRotationAnimation(focusedEntity);

        // Only override vanilla when gravity is not down (either cardinal or arbitrary)
        // A field free fall's visual tilt counts too: it starts from normal (DOWN) gravity
        boolean hasNonDownGravity = !gravityDirection.equals(GravityDirection.DOWN)
                || GravityChangerAPI.getVisualTilt(focusedEntity, partialTick) != null;
        boolean hasAnimation = animation != null && animation.isInAnimation();

        if (!hasNonDownGravity && !hasAnimation && !GravityCameraState.hasCachedRotation()) {
            // Back to normal gravity and the camera has eased to identity: hand back to vanilla
            return;
        }

        long timeMs = focusedEntity.level().getGameTime() * 50 + (long) (partialTick * 50);
        gravityapi$rotation = GravityCameraState.getSmoothedGravityRotation(focusedEntity, timeMs);

        if (hasAnimation) {
            // make sure that frustum culling updates when running rotation animation
            Minecraft.getInstance().levelRenderer.needsUpdate();
        }
    }

    // Under gravity the view bob is applied to the view matrix in the gravity frame (below), not to the projection
    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;bobHurt(Lcom/mojang/blaze3d/vertex/PoseStack;F)V"))
    private void gravityapi$bobHurt(GameRenderer renderer, PoseStack stack, float partialTick, Operation<Void> original) {
        if (gravityapi$rotation == null) original.call(renderer, stack, partialTick);
    }

    @WrapOperation(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;bobView(Lcom/mojang/blaze3d/vertex/PoseStack;F)V"))
    private void gravityapi$bobView(GameRenderer renderer, PoseStack stack, float partialTick, Operation<Void> original) {
        if (gravityapi$rotation == null) original.call(renderer, stack, partialTick);
    }

    /** After the camera's pitch/yaw (and the loader's roll), before the inverse view rotation and frustum are taken from it. */
    @Inject(method = "renderLevel", at = @At(value = "NEW", target = "org/joml/Matrix3f", ordinal = 0))
    private void gravityapi$rotateView(float partialTick, long finishNanos, PoseStack viewStack, CallbackInfo ci) {
        if (gravityapi$rotation == null) return;
        this.bobHurt(viewStack, partialTick);
        if (this.minecraft.options.bobView().get()) {
            this.bobView(viewStack, partialTick);
        }
        viewStack.mulPose(gravityapi$rotation);
    }

	@Shadow
	private void bobHurt(PoseStack p_109118_, float p_109119_) {

	}

	@Shadow
	private void bobView(PoseStack p_109139_, float p_109140_) {

	}
}
