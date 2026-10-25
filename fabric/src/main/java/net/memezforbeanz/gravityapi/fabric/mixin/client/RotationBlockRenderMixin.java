package net.memezforbeanz.gravityapi.fabric.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.memezforbeanz.gravityapi.rotation.RotatedModel;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla chunk meshing and the breaking overlay (Indigo hands vanilla-adapter models to renderBatched too). */
@Mixin(BlockRenderDispatcher.class)
public abstract class RotationBlockRenderMixin {
    @Inject(method = "onResourceManagerReload", at = @At("HEAD"))
    private void gravityapi$reload(ResourceManager resources, CallbackInfo ci) { RotatedModel.clear(); }

    @WrapOperation(method = {"renderBatched", "renderBreakingTexture"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/block/ModelBlockRenderer;tesselateBlock(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/client/resources/model/BakedModel;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;ZLnet/minecraft/util/RandomSource;JI)V"))
    private void gravityapi$model(ModelBlockRenderer renderer, BlockAndTintGetter level, BakedModel model, BlockState state,
            BlockPos pos, PoseStack stack, VertexConsumer consumer, boolean sides, RandomSource random, long seed, int overlay,
            Operation<Void> original) {
        original.call(renderer, level, RotatedModel.at(model, level, pos), state, pos, stack, consumer, sides, random, seed, overlay);
    }
}
