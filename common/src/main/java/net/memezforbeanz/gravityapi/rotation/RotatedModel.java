package net.memezforbeanz.gravityapi.rotation;

import com.google.common.cache.*;
import net.memezforbeanz.gravityapi.placement.CubeRotation;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.block.model.ItemTransforms;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.*;
import java.util.function.BiFunction;

/** Cache geometry after querying the source: random, model data and render layers remain authoritative. */
public final class RotatedModel {
    /** Marks a wrapper made by {@link #factory}, so a rotated model is never wrapped twice. */
    public interface Rotated {}

    /** Builds the wrapper for one rotation. Forge swaps in a BakedModelWrapper that also forwards model data and render types. */
    public static BiFunction<BakedModel, CubeRotation, BakedModel> factory = Vanilla::new;
    /** Copies a quad with a new face. Forge swaps in its constructor that keeps the quad's ambient occlusion flag. */
    public static BiFunction<BakedQuad, Direction, BakedQuad> copier = (quad, face) ->
            new BakedQuad(quad.getVertices().clone(), quad.getTintIndex(), face, quad.getSprite(), quad.isShade());

    private static final Cache<BakedModel, BakedModel[]> MODELS = CacheBuilder.newBuilder().maximumSize(1024).build();
    private static final Cache<BakedQuad, BakedQuad[]> QUADS = CacheBuilder.newBuilder().weakKeys().maximumSize(65536).build();
    private static final Matrix4f[] MATRICES = new Matrix4f[24];
    // DefaultVertexFormat.BLOCK: position (3 floats), colour, uv0 (2), uv2, normal (packed bytes), padding
    private static final int NORMAL = 7;

    private RotatedModel() {}

    public static BakedModel at(BakedModel model, BlockGetter level, BlockPos pos) {
        CubeRotation r = RotationLookup.get(level, pos);
        if (r == null || r.isIdentity() || model instanceof Rotated) return model;
        synchronized (MODELS) {
            BakedModel[] models = MODELS.getIfPresent(model);
            if (models == null) { models = new BakedModel[24]; MODELS.put(model, models); }
            if (models[r.index()] == null) models[r.index()] = factory.apply(model, r);
            return models[r.index()];
        }
    }

    /** The source side to query for a rotated wrapper's side. */
    public static Direction source(CubeRotation rotation, Direction side) {
        return side == null ? null : rotation.inverse(side);
    }

    public static List<BakedQuad> transform(List<BakedQuad> quads, BakedModel original, CubeRotation rotation) {
        if (quads.isEmpty()) return quads;
        List<BakedQuad> result = new ArrayList<>(quads.size());
        for (BakedQuad quad : quads) {
            if (!(original instanceof net.minecraft.client.resources.model.SimpleBakedModel)) {
                result.add(rotate(quad, rotation)); continue;
            }
            synchronized (QUADS) {
                BakedQuad[] variants = QUADS.getIfPresent(quad);
                if (variants == null) { variants = new BakedQuad[24]; QUADS.put(quad, variants); }
                BakedQuad rotated = variants[rotation.index()];
                if (rotated == null) variants[rotation.index()] = rotated = rotate(quad, rotation);
                result.add(rotated);
            }
        }
        return result;
    }

    private static BakedQuad rotate(BakedQuad quad, CubeRotation rotation) {
        BakedQuad rotated = copier.apply(quad, rotation.apply(quad.getDirection()));
        transformVertices(rotated.getVertices(), rotation);
        canonicalOrder(rotated);
        return rotated;
    }

    /** Rotates positions about the block centre and the packed normals, as Forge's QuadTransformers.applying does. */
    private static void transformVertices(int[] v, CubeRotation rotation) {
        Matrix4f matrix = matrix(rotation);
        int stride = v.length / 4;
        Vector3f p = new Vector3f();
        for (int i = 0; i < 4; i++) {
            int o = i * stride;
            p.set(Float.intBitsToFloat(v[o]), Float.intBitsToFloat(v[o + 1]), Float.intBitsToFloat(v[o + 2]));
            matrix.transformPosition(p);
            v[o] = Float.floatToRawIntBits(p.x); v[o + 1] = Float.floatToRawIntBits(p.y); v[o + 2] = Float.floatToRawIntBits(p.z);
            if (stride <= NORMAL) continue;
            int n = v[o + NORMAL];
            if ((n & 0xFFFFFF) == 0) continue;
            p.set((byte) n / 127f, (byte) (n >> 8) / 127f, (byte) (n >> 16) / 127f);
            matrix.transformDirection(p).normalize();
            v[o + NORMAL] = (n & 0xFF000000) | (((byte) Math.round(p.x * 127)) & 0xFF)
                    | ((((byte) Math.round(p.y * 127)) & 0xFF) << 8) | ((((byte) Math.round(p.z * 127)) & 0xFF) << 16);
        }
    }

    /** Rotation about the block centre, in block model space. */
    public static Matrix4f matrix(CubeRotation r) {
        Matrix4f m = MATRICES[r.index()];
        if (m == null) {
            MATRICES[r.index()] = m = new Matrix4f().translation(.5f, .5f, .5f).rotate(r.quaternion()).translate(-.5f, -.5f, -.5f);
        }
        return m;
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

    /** Loader-neutral wrapper over the vanilla BakedModel surface. */
    public static final class Vanilla implements BakedModel, Rotated {
        private final BakedModel original;
        private final CubeRotation rotation;
        public Vanilla(BakedModel original, CubeRotation rotation) { this.original = original; this.rotation = rotation; }
        @Override public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
            return transform(original.getQuads(state, source(rotation, side), random), original, rotation);
        }
        @Override public boolean useAmbientOcclusion() { return original.useAmbientOcclusion(); }
        @Override public boolean isGui3d() { return original.isGui3d(); }
        @Override public boolean usesBlockLight() { return original.usesBlockLight(); }
        @Override public boolean isCustomRenderer() { return original.isCustomRenderer(); }
        @Override public TextureAtlasSprite getParticleIcon() { return original.getParticleIcon(); }
        @Override public ItemTransforms getTransforms() { return original.getTransforms(); }
        @Override public ItemOverrides getOverrides() { return original.getOverrides(); }
    }
}
