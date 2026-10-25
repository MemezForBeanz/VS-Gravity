package net.memezforbeanz.gravityapi.forge.client;

import net.memezforbeanz.gravityapi.placement.CubeRotation;
import net.memezforbeanz.gravityapi.rotation.RotatedModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.client.model.data.ModelData;

import java.util.List;

/** Forge wrapper: forwards model data, render types and the rest of the Forge model surface to the source model. */
public final class ForgeRotatedModel extends BakedModelWrapper<BakedModel> implements RotatedModel.Rotated {
    private final CubeRotation rotation;

    public ForgeRotatedModel(BakedModel model, CubeRotation rotation) {
        super(model);
        this.rotation = rotation;
    }

    /** Installs the Forge wrapper and quad copier. Client only. */
    public static void install() {
        RotatedModel.factory = ForgeRotatedModel::new;
        RotatedModel.copier = (quad, face) -> new BakedQuad(quad.getVertices().clone(), quad.getTintIndex(), face,
                quad.getSprite(), quad.isShade(), quad.hasAmbientOcclusion());
    }

    @Override public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
        return RotatedModel.transform(originalModel.getQuads(state, RotatedModel.source(rotation, side), random), originalModel, rotation);
    }

    @Override public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data, RenderType type) {
        return RotatedModel.transform(originalModel.getQuads(state, RotatedModel.source(rotation, side), random, data, type), originalModel, rotation);
    }
}
