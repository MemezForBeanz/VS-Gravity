package net.memezforbeanz.gravityapi.mixin.client.rotation;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.memezforbeanz.gravityapi.rotation.RotationLookup;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BlockEntityRenderDispatcher.class)
public abstract class RotationBlockEntityRenderMixin {
    @WrapMethod(method = "render")
    private <E extends BlockEntity> void gravityapi$render(E entity, float partial, PoseStack stack, MultiBufferSource buffers, Operation<Void> original) {
        var r = RotationLookup.get(entity.getLevel(), entity.getBlockPos());
        if (r == null) { original.call(entity, partial, stack, buffers); return; }
        stack.pushPose();
        try {
            stack.translate(.5, .5, .5); stack.mulPose(r.quaternion()); stack.translate(-.5, -.5, -.5);
            original.call(entity, partial, stack, buffers);
        } finally { stack.popPose(); }
    }
}
