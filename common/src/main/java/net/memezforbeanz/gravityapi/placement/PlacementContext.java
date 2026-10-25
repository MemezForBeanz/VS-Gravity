package net.memezforbeanz.gravityapi.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.Comparator;

/** Local selectors see local face/hit inputs; the cell, replacement and validation stay physical. */
public class PlacementContext extends BlockPlaceContext {
    private BlockPlaceContext source;
    private final PlacementFrame frame;
    private final boolean local;

    public PlacementContext(BlockPlaceContext source, PlacementFrame frame, boolean local) {
        super(source);
        this.source = source;
        this.frame = frame;
        this.local = local;
    }

    @Override public BlockPos getClickedPos() { return source == null ? super.getClickedPos() : source.getClickedPos(); }
    @Override public Direction getClickedFace() {
        if (source == null) return super.getClickedFace();
        return local ? frame.rotation.inverse(source.getClickedFace()) : source.getClickedFace();
    }
    @Override public boolean canPlace() { return source == null ? super.canPlace() : source.canPlace(); }
    @Override public boolean replacingClickedOnBlock() {
        return source == null ? super.replacingClickedOnBlock() : source.replacingClickedOnBlock();
    }
    @Override public Vec3 getClickLocation() {
        if (source == null) return super.getClickLocation();
        if (!local) return source.getClickLocation();
        Vec3 center = Vec3.atCenterOf(source.getClickedPos());
        return center.add(frame.rotation.inverse(source.getClickLocation().subtract(center)));
    }

    private Vec3 look() { return local ? Vec3.directionFromRotation(frame.pitch, frame.yaw) : frame.gridLook; }
    @Override public Direction getNearestLookingDirection() {
        if (frame == null) return super.getNearestLookingDirection();
        Vec3 v = look();
        return Direction.getNearest(v.x, v.y, v.z);
    }
    @Override public Direction getNearestLookingVerticalDirection() {
        if (frame == null) return super.getNearestLookingVerticalDirection();
        return look().y >= 0 ? Direction.UP : Direction.DOWN;
    }
    @Override public Direction getHorizontalDirection() {
        if (frame == null) return super.getHorizontalDirection();
        if (local) return Direction.fromYRot(frame.yaw);
        Vec3 v = frame.gridHeading;
        return Math.abs(v.x) > Math.abs(v.z) ? (v.x > 0 ? Direction.EAST : Direction.WEST)
                : (v.z > 0 ? Direction.SOUTH : Direction.NORTH);
    }
    @Override public float getRotation() {
        if (frame == null) return super.getRotation();
        if (local) return frame.yaw;
        Vec3 v = frame.gridHeading;
        return (float) Math.toDegrees(Math.atan2(-v.x, v.z));
    }
    @Override public Direction[] getNearestLookingDirections() {
        if (frame == null) return super.getNearestLookingDirections();
        Vec3 v = look();
        Direction[] directions = Direction.values().clone();
        Arrays.sort(directions, Comparator.comparingDouble((Direction d) ->
                -v.dot(Vec3.atLowerCornerOf(d.getNormal()))).thenComparingInt(Direction::ordinal));
        if (!replacingClickedOnBlock()) {
            Direction first = source.getClickedFace().getOpposite();
            if (local) first = frame.rotation.inverse(first);
            int index = Arrays.asList(directions).indexOf(first);
            System.arraycopy(directions, 0, directions, 1, index);
            directions[0] = first;
        }
        return directions;
    }
}
