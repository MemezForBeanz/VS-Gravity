package net.memezforbeanz.gravityapi.placement;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.List;

/** One of the 24 proper rotations of a block grid. Enumeration order breaks ties. */
public record CubeRotation(Direction x, Direction y, Direction z) {
    private static final List<CubeRotation> ROTATIONS = new ArrayList<>();
    static {
        for (Direction x : Direction.values()) {
            for (Direction y : Direction.values()) {
                if (x.getAxis() == y.getAxis()) continue;
                Vec3 cross = vector(x).cross(vector(y));
                ROTATIONS.add(new CubeRotation(x, y, Direction.getNearest(cross.x, cross.y, cross.z)));
            }
        }
    }

    public static CubeRotation nearest(Quaternionf frame) {
        CubeRotation best = null;
        double score = -1;
        for (CubeRotation rotation : ROTATIONS) {
            Quaternionf q = new Quaternionf().setFromNormalized(new Matrix3f(
                    x(rotation.x), y(rotation.x), z(rotation.x),
                    x(rotation.y), y(rotation.y), z(rotation.y),
                    x(rotation.z), y(rotation.z), z(rotation.z)));
            double dot = Math.abs(q.dot(frame));
            if (dot > score + 1.0E-7) {
                score = dot;
                best = rotation;
            }
        }
        return best;
    }

    public int index() { return ROTATIONS.indexOf(this); }
    public static CubeRotation byIndex(int index) { return index >= 0 && index < 24 ? ROTATIONS.get(index) : null; }
    public boolean isIdentity() { return x == Direction.EAST && y == Direction.UP && z == Direction.SOUTH; }
    public CubeRotation inverse() { return new CubeRotation(inverse(Direction.EAST), inverse(Direction.UP), inverse(Direction.SOUTH)); }
    public CubeRotation compose(CubeRotation other) { return new CubeRotation(apply(other.x), apply(other.y), apply(other.z)); }
    public Quaternionf quaternion() {
        return new Quaternionf().setFromNormalized(new Matrix3f(
                x(x), y(x), z(x), x(y), y(y), z(y), x(z), y(z), z(z)));
    }

    private static float x(Direction d) { return d.getStepX(); }
    private static float y(Direction d) { return d.getStepY(); }
    private static float z(Direction d) { return d.getStepZ(); }
    private static Vec3 vector(Direction d) { return Vec3.atLowerCornerOf(d.getNormal()); }

    public Vec3 apply(Vec3 v) {
        return vector(x).scale(v.x).add(vector(y).scale(v.y)).add(vector(z).scale(v.z));
    }

    public Vec3 inverse(Vec3 v) {
        return new Vec3(v.dot(vector(x)), v.dot(vector(y)), v.dot(vector(z)));
    }

    public Direction apply(Direction d) {
        Vec3 v = apply(vector(d));
        return Direction.getNearest(v.x, v.y, v.z);
    }

    public Direction inverse(Direction d) {
        Vec3 v = inverse(vector(d));
        return Direction.getNearest(v.x, v.y, v.z);
    }
}
