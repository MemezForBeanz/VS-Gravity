package net.memezforbeanz.gravityapi.mixin.client.rotation;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.mojang.blaze3d.vertex.*;
import net.memezforbeanz.gravityapi.rotation.RotatedModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.*;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockRenderDispatcher.class)
public abstract class RotationBlockRenderMixin {
    @org.spongepowered.asm.mixin.injection.Inject(method = "onResourceManagerReload", at = @At("HEAD"))
    private void gravityapi$reload(net.minecraft.server.packs.resources.ResourceManager resources,
            org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) { RotatedModel.clear(); }
    @WrapOperation(method = {"renderBatched(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;Lnet/minecraftforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V",
            "renderBreakingTexture(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraftforge/client/model/data/ModelData;)V"}, remap = false, at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;JILnet/minecraftforge/client/model/data/ModelData;Lnet/minecraft/client/renderer/RenderType;)V", remap = false))
    private void gravityapi$model(ModelBlockRenderer renderer, BlockAndTintGetter level, BakedModel model, BlockState state,
            BlockPos pos, PoseStack stack, VertexConsumer consumer, boolean sides, RandomSource random, long seed, int overlay,
            ModelData data, RenderType type, Operation<Void> original) {
        original.call(renderer, level, RotatedModel.at(model, level, pos), state, pos, stack, consumer, sides, random, seed, overlay, data, type);
    }
}
