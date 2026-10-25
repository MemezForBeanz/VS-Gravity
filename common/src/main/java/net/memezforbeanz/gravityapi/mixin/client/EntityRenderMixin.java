package net.memezforbeanz.gravityapi.mixin.client;


import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.util.RotationUtil;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;

@Mixin(EntityRenderer.class)
public abstract class EntityRenderMixin {
    @ModifyExpressionValue(
        method = "renderNameTag",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/EntityRenderDispatcher;cameraOrientation()Lorg/joml/Quaternionf;",
            ordinal = 0
        )
    )
    private Quaternionf modifyExpressionValue_renderLabelIfPresent_getRotation_0(Quaternionf originalRotation, Entity entity) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(entity);
        if (gravityDirection == Direction.DOWN) {
            return originalRotation;
        }
        Quaternionf quaternion = new Quaternionf(RotationUtil.getCameraRotationQuaternion(gravityDirection));
        quaternion.conjugate();
        quaternion.mul(originalRotation);
        return quaternion;
    }
}
