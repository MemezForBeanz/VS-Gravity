package net.memezforbeanz.gravityapi.api;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Represents a gravity direction that can be either a cardinal Direction
 * or an arbitrary Vec3 vector for smooth ship-relative gravity.
 * 
 * This allows the gravity system to support any angle, not just the 6 cardinal directions.
 */
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
    
    /**
     * The gravity vector (normalized, points in direction of gravity pull)
     * For DOWN, this is (0, -1, 0)
     */
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
     * Cached rotation quaternion from DOWN to this gravity direction
     */
    private Quaternionf rotationQuaternion;
    
    /**
     * Cached inverse rotation quaternion
     */
    private Quaternionf inverseRotationQuaternion;
    
    /**
     * Create a GravityDirection from a cardinal Direction
     */
    public GravityDirection(Direction direction) {
        this.vector = Vec3.atLowerCornerOf(direction.getNormal());
        this.nearestDirection = direction;
        this.isCardinal = true;
        computeQuaternions();
    }
    
    /**
     * Create a GravityDirection from an arbitrary vector
     * @param gravityVector The direction of gravity pull (will be normalized)
     */
    public GravityDirection(Vec3 gravityVector) {
        this.vector = gravityVector.normalize();
        this.nearestDirection = Direction.getNearest(
            (float) this.vector.x, 
            (float) this.vector.y, 
            (float) this.vector.z
        );
        // Check if this is close enough to a cardinal to be considered cardinal
        Vec3 cardinalVec = Vec3.atLowerCornerOf(nearestDirection.getNormal());
        this.isCardinal = this.vector.distanceToSqr(cardinalVec) < 0.001;
        computeQuaternions();
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
     * Compute the rotation quaternions for this gravity direction.
     *
     * The rotation transforms coordinates between world space and player space:
     * - In player space, gravity always points down (0, -1, 0)
     * - In world space, gravity points in the direction of this.vector
     *
     * playerToWorldRotation: rotates (0, -1, 0) to this.vector
     * worldToPlayerRotation: rotates this.vector to (0, -1, 0)
     */
    private void computeQuaternions() {
        Vec3 defaultDown = new Vec3(0, -1, 0);
        
        if (this.vector.distanceToSqr(defaultDown) < 0.0001) {
            // Already pointing down, identity rotation
            this.rotationQuaternion = new Quaternionf();
            this.inverseRotationQuaternion = new Quaternionf();
        } else if (this.vector.distanceToSqr(new Vec3(0, 1, 0)) < 0.0001) {
            // Pointing up (opposite of down), rotate 180 around Z
            // This rotation takes (0, -1, 0) to (0, 1, 0)
            Quaternionf playerToWorld = new Quaternionf().fromAxisAngleDeg(0, 0, 1, 180);
            this.inverseRotationQuaternion = playerToWorld;
            this.rotationQuaternion = new Quaternionf(playerToWorld).conjugate();
        } else {
            // General case: find rotation from (0, -1, 0) to this.vector
            // This is the playerToWorld rotation
            Vec3 axis = defaultDown.cross(this.vector).normalize();
            double dot = defaultDown.dot(this.vector);
            double angle = Math.acos(Math.max(-1, Math.min(1, dot)));
            
            // playerToWorld: rotates default down to actual gravity direction
            Quaternionf playerToWorld = new Quaternionf().fromAxisAngleRad(
                (float) axis.x, (float) axis.y, (float) axis.z, (float) angle
            );
            this.inverseRotationQuaternion = playerToWorld;
            // worldToPlayer is the inverse
            this.rotationQuaternion = new Quaternionf(playerToWorld).conjugate();
        }

        // Debug: verify the quaternions work correctly
        // Apply worldToPlayer to the gravity vector - should give (0, -1, 0)
        Vector3f testVec = new Vector3f((float) this.vector.x, (float) this.vector.y, (float) this.vector.z);
        testVec.rotate(this.rotationQuaternion);
        // testVec should now be approximately (0, -1, 0)
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
        
        // Handle zero vector
        if (x == 0 && y == 0 && z == 0) {
            return DOWN;
        }
        
        return new GravityDirection(x, y, z);
    }
    
    /**
     * Interpolate between two gravity directions
     */
    public static GravityDirection lerp(GravityDirection from, GravityDirection to, float progress) {
        if (progress <= 0) return from;
        if (progress >= 1) return to;
        
        // Slerp the quaternions for smooth rotation
        Quaternionf fromQ = from.getWorldToPlayerRotation();
        Quaternionf toQ = to.getWorldToPlayerRotation();
        Quaternionf result = new Quaternionf().set(fromQ).slerp(toQ, progress);
        
        // Convert back to a gravity vector
        Vector3f down = new Vector3f(0, -1, 0);
        Quaternionf inverseResult = new Quaternionf(result).conjugate();
        down.rotate(inverseResult);
        
        return new GravityDirection(down.x, down.y, down.z);
    }
    
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof GravityDirection other)) return false;
        return this.vector.distanceToSqr(other.vector) < 0.0001;
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

