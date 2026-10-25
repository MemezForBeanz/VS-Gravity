package net.memezforbeanz.gravityapi.api;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Quaterniond;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;

/** A gravity direction: a cardinal Direction or an arbitrary vector (ship-relative gravity). */
public class GravityDirection {
    
    /**
     * Standard world gravity (down)
     */
    public static final GravityDirection DOWN = new GravityDirection(Direction.DOWN);
    public static final GravityDirection UP = new GravityDirection(Direction.UP);
    public static final GravityDirection NORTH = new GravityDirection(Direction.NORTH);
    public static final GravityDirection SOUTH = new GravityDirection(Direction.SOUTH);
    public static final GravityDirection EAST = new GravityDirection(Direction.EAST);
    public static final GravityDirection WEST = new GravityDirection(Direction.WEST);
    
    /** The gravity vector (normalized, pointing the way gravity pulls); (0, -1, 0) for DOWN */
    private final Vec3 vector;
    
    /**
     * The nearest cardinal direction (for backward compatibility)
     */
    private final Direction nearestDirection;
    
    /**
     * Whether this is exactly a cardinal direction (not arbitrary)
     */
    private final boolean isCardinal;
    
    /**
     * Ship anchor (never serialized or synced): the VS2 ship this direction is fixed to and the
     * direction in the ship's frame, so it is re-resolved from the ship's transform instead of
     * lagging a moving ship as a world snapshot.
     */
    @Nullable
    private WeakReference<Object> anchorShip;
    private long anchorShipId;
    @Nullable
    private Vec3 shipLocalVector;

    private Quaternionf rotationQuaternion;
    
    private Quaternionf inverseRotationQuaternion;
    
    /**
     * Create a GravityDirection from a cardinal Direction
     */
    public GravityDirection(Direction direction) {
        this.vector = Vec3.atLowerCornerOf(direction.getNormal());
        this.nearestDirection = direction;
        this.isCardinal = true;
        computeQuaternions(true);
    }

    /**
     * Create a GravityDirection from an arbitrary vector
     * @param gravityVector The direction of gravity pull (will be normalized)
     */
    public GravityDirection(Vec3 gravityVector) {
        this(gravityVector, false);
    }

    private GravityDirection(Vec3 gravityVector, boolean forceArbitrary) {
        this.vector = gravityVector.normalize();
        this.nearestDirection = Direction.getNearest(
            (float) this.vector.x,
            (float) this.vector.y,
            (float) this.vector.z
        );
        Vec3 cardinalVec = Vec3.atLowerCornerOf(nearestDirection.getNormal());
        boolean vectorIsCardinal = this.vector.distanceToSqr(cardinalVec) < 0.001;
        this.isCardinal = vectorIsCardinal && !forceArbitrary;
        // The quaternion basis follows the vector: an axis-aligned ship-relative direction still uses the cardinal bases
        computeQuaternions(vectorIsCardinal);
    }

    /**
     * A ship-relative direction: never cardinal, even when axis-aligned (a parked ship), so it stays
     * in the ship physics pipeline (the cardinal collision mixins can't see ship blocks).
     */
    public static GravityDirection shipRelative(Vec3 vector) {
        if (vector == null) {
            return DOWN;
        }
        return new GravityDirection(vector, true);
    }
    
    /**
     * A ship-relative direction anchored to a VS2 ship: {@code shipLocal} in the ship's frame,
     * {@code shipToWorld} the ship's current rotation. Its player frame is locked to the ship (the
     * ship-space basis of {@code shipLocal}, cardinal for a face), so the facing turns with the ship.
     */
    public static GravityDirection shipAnchored(Quaterniond shipToWorld, Object ship, long shipId, Vec3 shipLocal) {
        Vec3 local = snapToFace(shipLocal.normalize());
        Vector3d world = shipToWorld.transform(new Vector3d(local.x, local.y, local.z));
        GravityDirection direction = new GravityDirection(new Vec3(world.x, world.y, world.z), true);
        direction.anchorShip = new WeakReference<>(ship);
        direction.anchorShipId = shipId;
        direction.shipLocalVector = local;
        direction.rotationQuaternion = shipLockedWorldToPlayer(local, shipToWorld);
        direction.inverseRotationQuaternion = new Quaternionf(direction.rotationQuaternion).conjugate();
        return direction;
    }

    /** World-to-player rotation of a ship-local direction under the ship rotation {@code shipToWorld}. */
    public static Quaternionf shipLockedWorldToPlayer(Vec3 shipLocal, Quaterniond shipToWorld) {
        Quaternionf shipToPlayer = new GravityDirection(shipLocal, false).getWorldToPlayerRotation();
        Quaternionf worldToShip = new Quaternionf((float) shipToWorld.x, (float) shipToWorld.y,
                (float) shipToWorld.z, (float) shipToWorld.w).conjugate();
        return shipToPlayer.mul(worldToShip);
    }

    /** Snap a direction within round-off of a block face to that face. */
    private static Vec3 snapToFace(Vec3 v) {
        Direction nearest = Direction.getNearest(v.x, v.y, v.z);
        Vec3 face = Vec3.atLowerCornerOf(nearest.getNormal());
        return v.distanceToSqr(face) < 1.0E-3 ? face : v;
    }

    /** Whether this direction is fixed to a ship's frame (see {@link #shipAnchored}). */
    public boolean isShipAnchored() {
        return shipLocalVector != null;
    }

    /** The anchoring ship object, or null if unanchored or the ship was unloaded. */
    @Nullable
    public Object getAnchorShip() {
        return anchorShip != null ? anchorShip.get() : null;
    }

    /** The anchoring ship's id (meaningful only when {@link #isShipAnchored()}). */
    public long getAnchorShipId() {
        return anchorShipId;
    }

    /** The direction in the anchoring ship's frame, or null if unanchored. */
    @Nullable
    public Vec3 getShipLocalVector() {
        return shipLocalVector;
    }

    /** The same ship anchor under the ship's current rotation; as is if the ship is gone. */
    public GravityDirection withShipRotation(Quaterniond shipToWorld) {
        Object ship = getAnchorShip();
        if (shipLocalVector == null || ship == null) {
            return this;
        }
        return shipAnchored(shipToWorld, ship, anchorShipId, shipLocalVector);
    }

    /** Whether both point the same way in the same ship's frame (any world difference is the ship moving). */
    public boolean isSameShipFrameAs(@Nullable GravityDirection other) {
        return other != null && shipLocalVector != null && other.shipLocalVector != null
                && anchorShipId == other.anchorShipId
                && shipLocalVector.distanceToSqr(other.shipLocalVector) < 1.0E-6;
    }

    /**
     * Create a GravityDirection from x, y, z components
     */
    public GravityDirection(double x, double y, double z) {
        this(new Vec3(x, y, z));
    }
    
    /**
     * Create from a Direction enum
     */
    public static GravityDirection fromDirection(Direction direction) {
        return switch (direction) {
            case DOWN -> DOWN;
            case UP -> UP;
            case NORTH -> NORTH;
            case SOUTH -> SOUTH;
            case EAST -> EAST;
            case WEST -> WEST;
        };
    }
    
    /**
     * Create from a gravity vector (pointing in direction of pull)
     */
    public static GravityDirection fromVector(Vec3 vector) {
        if (vector == null) {
            return DOWN;
        }
        return new GravityDirection(vector);
    }
    
    /**
     * Compute the world/player rotations: player space has gravity at (0, -1, 0), world space at
     * {@code vector}.
     */
    private void computeQuaternions(boolean useCardinalBases) {
        Vec3 defaultDown = new Vec3(0, -1, 0);

        if (useCardinalBases) {
            // Cardinal directions use the same bases as RotationUtil's switch tables (the shortest
            // rotation differs in roll for some), so movement and the ship pipeline agree per axis
            this.rotationQuaternion = cardinalWorldToPlayer(this.nearestDirection);
            this.inverseRotationQuaternion = new Quaternionf(rotationQuaternion).conjugate();
        } else if (this.vector.distanceToSqr(defaultDown) < 0.0001) {
            // Already pointing down, identity rotation
            this.rotationQuaternion = new Quaternionf();
            this.inverseRotationQuaternion = new Quaternionf();
        } else if (this.vector.distanceToSqr(new Vec3(0, 1, 0)) < 0.0001) {
            // Up: 180° about Z
            Quaternionf playerToWorld = new Quaternionf().fromAxisAngleDeg(0, 0, 1, 180);
            this.inverseRotationQuaternion = playerToWorld;
            this.rotationQuaternion = new Quaternionf(playerToWorld).conjugate();
        } else {
            // General case: the shortest rotation from (0, -1, 0) to the vector
            Vec3 axis = defaultDown.cross(this.vector).normalize();
            double dot = defaultDown.dot(this.vector);
            double angle = Math.acos(Math.max(-1, Math.min(1, dot)));
            
            Quaternionf playerToWorld = new Quaternionf().fromAxisAngleRad(
                (float) axis.x, (float) axis.y, (float) axis.z, (float) angle
            );
            this.inverseRotationQuaternion = playerToWorld;
            this.rotationQuaternion = new Quaternionf(playerToWorld).conjugate();
        }
    }

    /**
     * World-to-player rotation for a cardinal direction, matching {@code RotationUtil.vecWorldToPlayer}.
     * Duplicated rather than referenced to avoid a class-initialization cycle.
     */
    private static Quaternionf cardinalWorldToPlayer(Direction direction) {
        return switch (direction) {
            case DOWN -> new Quaternionf();
            // (x,y,z) -> (-x,-y,z)
            case UP -> quatFromColumns(-1, 0, 0, 0, -1, 0, 0, 0, 1);
            // (x,y,z) -> (x,z,-y)
            case NORTH -> quatFromColumns(1, 0, 0, 0, 0, -1, 0, 1, 0);
            // (x,y,z) -> (-x,-z,-y)
            case SOUTH -> quatFromColumns(-1, 0, 0, 0, 0, -1, 0, -1, 0);
            // (x,y,z) -> (-z,x,-y)
            case WEST -> quatFromColumns(0, 1, 0, 0, 0, -1, -1, 0, 0);
            // (x,y,z) -> (z,-x,-y)
            case EAST -> quatFromColumns(0, -1, 0, 0, 0, -1, 1, 0, 0);
        };
    }

    /** Arguments are column-major: (c0x,c0y,c0z, c1x,c1y,c1z, c2x,c2y,c2z). */
    private static Quaternionf quatFromColumns(float m00, float m01, float m02,
                                               float m10, float m11, float m12,
                                               float m20, float m21, float m22) {
        return new Quaternionf().setFromNormalized(
                new Matrix3f(m00, m01, m02, m10, m11, m12, m20, m21, m22));
    }
    
    /**
     * Get the gravity vector (normalized, points in direction of pull)
     */
    public Vec3 getVector() {
        return vector;
    }
    
    /**
     * Get the nearest cardinal direction
     */
    public Direction getNearestDirection() {
        return nearestDirection;
    }
    
    /**
     * Check if this is exactly a cardinal direction
     */
    public boolean isCardinal() {
        return isCardinal;
    }
    
    /**
     * Get the rotation quaternion that transforms from world space to player space
     * (rotates the world so gravity points down in player's reference frame)
     */
    public Quaternionf getWorldToPlayerRotation() {
        return new Quaternionf(rotationQuaternion);
    }
    
    /**
     * Get the rotation quaternion that transforms from player space to world space
     */
    public Quaternionf getPlayerToWorldRotation() {
        return new Quaternionf(inverseRotationQuaternion);
    }
    
    /**
     * Transform a vector from world space to player space
     */
    public Vec3 vecWorldToPlayer(Vec3 worldVec) {
        Vector3f v = worldVec.toVector3f();
        v.rotate(rotationQuaternion);
        return new Vec3(v);
    }
    
    /**
     * Transform a vector from player space to world space
     */
    public Vec3 vecPlayerToWorld(Vec3 playerVec) {
        Vector3f v = playerVec.toVector3f();
        v.rotate(inverseRotationQuaternion);
        return new Vec3(v);
    }
    
    /**
     * Transform a vector from world space to player space
     */
    public Vector3f vecWorldToPlayer(Vector3f worldVec) {
        Vector3f result = new Vector3f(worldVec);
        result.rotate(rotationQuaternion);
        return result;
    }
    
    /**
     * Transform a vector from player space to world space
     */
    public Vector3f vecPlayerToWorld(Vector3f playerVec) {
        Vector3f result = new Vector3f(playerVec);
        result.rotate(inverseRotationQuaternion);
        return result;
    }
    
    /**
     * Serialize to NBT
     */
    public CompoundTag toNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("x", vector.x);
        tag.putDouble("y", vector.y);
        tag.putDouble("z", vector.z);
        tag.putBoolean("cardinal", isCardinal);
        if (isCardinal) {
            tag.putString("direction", nearestDirection.getName());
        }
        return tag;
    }
    
    /**
     * Deserialize from NBT
     */
    public static GravityDirection fromNBT(CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return DOWN;
        }
        
        if (tag.getBoolean("cardinal") && tag.contains("direction")) {
            Direction dir = Direction.byName(tag.getString("direction"));
            if (dir != null) {
                return fromDirection(dir);
            }
        }
        
        double x = tag.getDouble("x");
        double y = tag.getDouble("y");
        double z = tag.getDouble("z");
        
        if (x == 0 && y == 0 && z == 0) {
            return DOWN;
        }

        // Preserve non-cardinal through round-trips (an axis-aligned ship-relative direction)
        return shipRelative(new Vec3(x, y, z));
    }
    
    /**
     * Interpolate between two gravity directions
     */
    public static GravityDirection lerp(GravityDirection from, GravityDirection to, float progress) {
        if (progress <= 0) return from;
        if (progress >= 1) return to;
        
        Quaternionf fromQ = from.getWorldToPlayerRotation();
        Quaternionf toQ = to.getWorldToPlayerRotation();
        Quaternionf result = new Quaternionf().set(fromQ).slerp(toQ, progress);
        
        Vector3f down = new Vector3f(0, -1, 0);
        Quaternionf inverseResult = new Quaternionf(result).conjugate();
        down.rotate(inverseResult);
        
        return new GravityDirection(down.x, down.y, down.z);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof GravityDirection other)) return false;
        // The whole frame, not just the down vector: a ship face pointing world-down but turned
        // about it (a planetoid spun round its vertical axis) must not pass for plain DOWN, or
        // every "normal gravity" shortcut skips its frame conversion
        Quaternionf a = this.rotationQuaternion;
        Quaternionf b = other.rotationQuaternion;
        return this.vector.distanceToSqr(other.vector) < 0.0001
                && Math.abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w) > 0.99999f;
    }
    
    @Override
    public int hashCode() {
        // Use nearest direction for hash to maintain compatibility
        return nearestDirection.hashCode();
    }
    
    @Override
    public String toString() {
        if (isCardinal) {
            return "GravityDirection[" + nearestDirection.getName() + "]";
        } else {
            return String.format("GravityDirection[%.3f, %.3f, %.3f -> %s]", 
                vector.x, vector.y, vector.z, nearestDirection.getName());
        }
    }
}

