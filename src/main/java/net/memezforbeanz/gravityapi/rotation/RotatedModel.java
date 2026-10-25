package net.memezforbeanz.gravityapi.rotation;

import com.google.common.cache.*;
import com.mojang.math.Transformation;
import net.memezforbeanz.gravityapi.placement.CubeRotation;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.model.*;
import net.minecraftforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import java.util.*;

/** Cache geometry after querying the source: random, model data and render layers remain authoritative. */
public final class RotatedModel extends BakedModelWrapper<BakedModel> {
    private static final Cache<BakedModel, RotatedModel[]> MODELS = CacheBuilder.newBuilder().maximumSize(1024).build();
    private static final Cache<BakedQuad, BakedQuad[]> QUADS = CacheBuilder.newBuilder().weakKeys().maximumSize(65536).build();
    private final CubeRotation rotation;
    private final IQuadTransformer transformer;
    private RotatedModel(BakedModel model, CubeRotation r) {
        super(model); rotation = r;
        transformer = QuadTransformers.applying(new Transformation(new Matrix4f().translation(.5f,.5f,.5f)
                .rotate(r.quaternion()).translate(-.5f,-.5f,-.5f)));
    }
    public static BakedModel at(BakedModel model, BlockGetter level, BlockPos pos) {
        CubeRotation r = RotationLookup.get(level, pos);
        if (r == null || r.isIdentity() || model instanceof RotatedModel) return model;
        synchronized (MODELS) {
            RotatedModel[] models = MODELS.getIfPresent(model);
            if (models == null) { models = new RotatedModel[24]; MODELS.put(model, models); }
            if (models[r.index()] == null) models[r.index()] = new RotatedModel(model, r);
            return models[r.index()];
        }
    }
    private List<BakedQuad> transform(List<BakedQuad> quads) {
        if (quads.isEmpty()) return quads;
        List<BakedQuad> result = new ArrayList<>(quads.size());
        for (BakedQuad quad : quads) {
            if (!(originalModel instanceof net.minecraft.client.resources.model.SimpleBakedModel)) {
                BakedQuad rotated = new BakedQuad(quad.getVertices().clone(), quad.getTintIndex(), rotation.apply(quad.getDirection()),
                        quad.getSprite(), quad.isShade(), quad.hasAmbientOcclusion());
                transformer.processInPlace(rotated); canonicalOrder(rotated); result.add(rotated); continue;
            }
            synchronized (QUADS) {
                BakedQuad[] variants = QUADS.getIfPresent(quad);
                if (variants == null) { variants = new BakedQuad[24]; QUADS.put(quad, variants); }
                BakedQuad rotated = variants[rotation.index()];
                if (rotated == null) {
                    rotated = new BakedQuad(quad.getVertices().clone(), quad.getTintIndex(), rotation.apply(quad.getDirection()),
                            quad.getSprite(), quad.isShade(), quad.hasAmbientOcclusion());
                    transformer.processInPlace(rotated); canonicalOrder(rotated); variants[rotation.index()] = rotated;
                }
                result.add(rotated);
            }
        }
        return result;
    }
    @Override public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
        return transform(originalModel.getQuads(state, side == null ? null : rotation.inverse(side), random));
    }
    @Override public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random, ModelData data, RenderType type) {
        return transform(originalModel.getQuads(state, side == null ? null : rotation.inverse(side), random, data, type));
    }
    /** Vanilla AO assigns corner light by vertex slot, assuming FaceBakery's order for the face. */
    private static void canonicalOrder(BakedQuad quad) {
        int[] v = quad.getVertices();
        int stride = v.length / 4;
        float[] bounds = {Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < 4; i++) {
            float x = Float.intBitsToFloat(v[i * stride]), y = Float.intBitsToFloat(v[i * stride + 1]), z = Float.intBitsToFloat(v[i * stride + 2]);
            // Indexed by Direction 3D data value, as FaceInfo.Constants is.
            bounds[0] = Math.min(bounds[0], y); bounds[1] = Math.max(bounds[1], y);
            bounds[2] = Math.min(bounds[2], z); bounds[3] = Math.max(bounds[3], z);
            bounds[4] = Math.min(bounds[4], x); bounds[5] = Math.max(bounds[5], x);
        }
        var corner = net.minecraft.client.renderer.FaceInfo.fromFacing(quad.getDirection()).getVertexInfo(0);
        float cx = bounds[corner.xFace], cy = bounds[corner.yFace], cz = bounds[corner.zFace];
        int first = 0; double best = Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            double dx = Float.intBitsToFloat(v[i * stride]) - cx, dy = Float.intBitsToFloat(v[i * stride + 1]) - cy, dz = Float.intBitsToFloat(v[i * stride + 2]) - cz;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < best) { best = d; first = i; }
        }
        if (first == 0) return;
        // Proper rotations keep the winding, so a cyclic shift restores the canonical order.
        int[] copy = v.clone();
        for (int i = 0; i < 4; i++) System.arraycopy(copy, ((i + first) % 4) * stride, v, i * stride, stride);
    }
    public static void clear() { MODELS.invalidateAll(); QUADS.invalidateAll(); }
}
