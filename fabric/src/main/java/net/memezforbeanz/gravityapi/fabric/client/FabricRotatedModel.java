package net.memezforbeanz.gravityapi.fabric.client;

import net.fabricmc.fabric.api.renderer.v1.model.ForwardingBakedModel;
import net.fabricmc.fabric.api.renderer.v1.render.RenderContext;
import net.memezforbeanz.gravityapi.placement.CubeRotation;
import net.memezforbeanz.gravityapi.rotation.RotatedModel;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.function.Supplier;

/** Fabric wrapper: vanilla quads go through RotatedModel's cache, Renderer API quads through a quad transform. */
public final class FabricRotatedModel extends ForwardingBakedModel implements RotatedModel.Rotated {
    private final CubeRotation rotation;
    private final RenderContext.QuadTransform transform;

    public FabricRotatedModel(BakedModel model, CubeRotation rotation) {
        this.wrapped = model;
        this.rotation = rotation;
        Matrix4f matrix = RotatedModel.matrix(rotation);
        this.transform = quad -> {
            Vector3f v = new Vector3f();
            for (int i = 0; i < 4; i++) {
                quad.pos(i, matrix.transformPosition(quad.copyPos(i, v)));
                if (quad.hasNormal(i)) {
                    quad.normal(i, matrix.transformDirection(v.set(quad.normalX(i), quad.normalY(i), quad.normalZ(i))).normalize());
                }
            }
            Direction cull = quad.cullFace();
            if (cull != null) quad.cullFace(rotation.apply(cull));
            Direction nominal = quad.nominalFace();
            if (nominal != null) quad.nominalFace(rotation.apply(nominal));
            return true;
        };
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
        return RotatedModel.transform(wrapped.getQuads(state, RotatedModel.source(rotation, side), random), wrapped, rotation);
    }

    @Override
    public void emitBlockQuads(BlockAndTintGetter level, BlockState state, BlockPos pos, Supplier<RandomSource> random, RenderContext context) {
        context.pushTransform(transform);
        try {
            super.emitBlockQuads(level, state, pos, random, context);
        } finally {
            context.popTransform();
        }
    }
}
