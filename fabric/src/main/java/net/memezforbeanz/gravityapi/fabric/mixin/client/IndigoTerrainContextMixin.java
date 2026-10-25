package net.memezforbeanz.gravityapi.fabric.mixin.client;

import net.memezforbeanz.gravityapi.rotation.RotatedModel;
import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Indigo meshes Renderer API (non-vanilla-adapter) models itself, bypassing renderBatched. */
@Pseudo
@Mixin(targets = "net.fabricmc.fabric.impl.client.indigo.renderer.render.TerrainRenderContext", remap = false)
public abstract class IndigoTerrainContextMixin {
    @Unique private RenderChunkRegion gravityapi$region;

    @ModifyVariable(method = "prepare", at = @At("HEAD"), argsOnly = true)
    private RenderChunkRegion gravityapi$region(RenderChunkRegion region) {
        return gravityapi$region = region;
    }

    @ModifyVariable(method = "tessellateBlock", at = @At("HEAD"), argsOnly = true)
    private BakedModel gravityapi$model(BakedModel model, BlockState state, BlockPos pos) {
        return gravityapi$region == null ? model : RotatedModel.at(model, gravityapi$region, pos);
    }
}
