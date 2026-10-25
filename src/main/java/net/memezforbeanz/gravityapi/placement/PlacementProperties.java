package net.memezforbeanz.gravityapi.placement;

import net.minecraft.core.Direction;
import net.minecraft.core.FrontAndTop;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.Vec3;

/** Maps orientation value types, including independently declared mod properties. */
public final class PlacementProperties {
    private PlacementProperties() {}

    public static <T extends Comparable<T>> Property<T> find(BlockState state, Class<T> type) {
        for (Property<?> property : state.getProperties()) {
            if (property.getValueClass() == type) return cast(property);
        }
        return null;
    }

    public static Property<Direction> horizontal(BlockState state) {
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return BlockStateProperties.HORIZONTAL_FACING;
        for (Property<?> property : state.getProperties()) {
            if (property.getValueClass() == Direction.class && property.getPossibleValues().stream()
                    .allMatch(value -> ((Direction) value).getAxis().isHorizontal())) return cast(property);
        }
        return null;
    }

    public static boolean secondaryPositions(BlockState state) {
        return find(state, DoubleBlockHalf.class) != null || find(state, BedPart.class) != null;
    }

    /** Null means that at least one orientation cannot be represented by this block's states. */
    public static BlockState map(BlockState proposed, BlockState nativeState, PlacementFrame frame) {
        CubeRotation r = frame.rotation;
        Property<AttachFace> attachment = find(proposed, AttachFace.class);
        Property<Direction> heading = attachment == null ? null : horizontal(proposed);
        BlockState result = proposed;
        if (attachment != null) {
            if (heading == null) return null;
            AttachFace face = proposed.getValue(attachment);
            Direction normal = r.apply(face == AttachFace.FLOOR ? Direction.UP
                    : face == AttachFace.CEILING ? Direction.DOWN : proposed.getValue(heading));
            Direction facing = normal.getAxis().isHorizontal() ? normal : r.apply(proposed.getValue(heading));
            if (!heading.getPossibleValues().contains(facing)) return null;
            AttachFace mapped = normal == Direction.UP ? AttachFace.FLOOR
                    : normal == Direction.DOWN ? AttachFace.CEILING : AttachFace.WALL;
            if (!attachment.getPossibleValues().contains(mapped)) return null;
            result = result.setValue(attachment, mapped).setValue(heading, facing);
        }
        for (Property<?> property : proposed.getProperties()) {
            if (property == attachment || property == heading) continue;
            result = mapProperty(result, proposed, nativeState, property, frame);
            if (result == null) return null;
        }
        return result;
    }

    private static <T extends Comparable<T>> BlockState mapProperty(BlockState result, BlockState proposed,
            BlockState nativeState, Property<T> property, PlacementFrame frame) {
        T value = proposed.getValue(property);
        Object mapped = value;
        CubeRotation r = frame.rotation;
        if (value instanceof Direction direction) {
            Direction target = r.apply(direction);
            boolean horizontal = property.getPossibleValues().stream()
                    .allMatch(v -> ((Direction) v).getAxis().isHorizontal());
            if (horizontal && target.getAxis() == Direction.Axis.Y) {
                // Native selection already applied the stable projected yaw and its opposite convention.
                if (nativeState != null && nativeState.hasProperty(property)) mapped = nativeState.getValue(property);
                else mapped = r.apply(direction.getClockWise());
            } else mapped = target;
        } else if (value instanceof Direction.Axis axis) {
            mapped = r.apply(Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE)).getAxis();
        } else if (value instanceof Half half) {
            if (r.y().getAxis() != Direction.Axis.Y) return null;
            mapped = r.y() == Direction.DOWN ? (half == Half.TOP ? Half.BOTTOM : Half.TOP) : half;
        } else if (value instanceof SlabType slab) {
            if (slab != SlabType.DOUBLE) {
                if (r.y().getAxis() != Direction.Axis.Y) return null;
                mapped = r.y() == Direction.DOWN ? (slab == SlabType.TOP ? SlabType.BOTTOM : SlabType.TOP) : slab;
            }
        } else if (value instanceof FrontAndTop orientation) {
            mapped = FrontAndTop.fromFrontAndTop(r.apply(orientation.front()), r.apply(orientation.top()));
        } else if (property == BlockStateProperties.ROTATION_16) {
            if (r.y().getAxis() != Direction.Axis.Y) return null;
            Vec3 facing = r.apply(Vec3.directionFromRotation(0, ((Integer) value) * 22.5F));
            double degrees = Math.toDegrees(Math.atan2(-facing.x, facing.z));
            mapped = Math.floorMod((int) Math.floor(degrees / 22.5 + 0.5), 16);
        }
        if (mapped == null || !property.getPossibleValues().contains(mapped)) return null;
        return result.setValue(property, property.getValueClass().cast(mapped));
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> Property<T> cast(Property<?> property) {
        return (Property<T>) property;
    }
}
