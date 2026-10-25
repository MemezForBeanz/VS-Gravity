package net.memezforbeanz.gravityapi.rotation;

import net.memezforbeanz.gravityapi.config.GravityServerConfig;
import net.memezforbeanz.gravityapi.placement.*;
import net.minecraft.core.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.*;

public final class RotationPlacement {
    private RotationPlacement() {}
    /** Property families describe orientation, not arbitrary model symmetry. Be conservative about missing axes. */
    public static boolean exact(BlockState state, CubeRotation r) {
        if (r.isIdentity()) return true;
        if (PlacementProperties.find(state, BellAttachType.class) != null) return r.y() == Direction.UP;
        boolean orientation = false;
        boolean half = PlacementProperties.find(state, Half.class) != null || PlacementProperties.find(state, SlabType.class) != null;
        for (Property<?> property : state.getProperties()) {
            Object value = state.getValue(property);
            if (value instanceof Direction direction) {
                if (!property.getPossibleValues().contains(r.apply(direction))) return false;
                // Horizontal-only headings cannot encode roll without a half/attachment property.
                if (property.getPossibleValues().stream().allMatch(v -> ((Direction) v).getAxis().isHorizontal()) && r.y() != Direction.UP
                        && !(half && r.y() == Direction.DOWN)) return false;
                orientation = true;
            } else if (value instanceof Direction.Axis axis) {
                Direction.Axis mapped = r.apply(Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE)).getAxis();
                if (!property.getPossibleValues().contains(mapped)) return false;
                orientation = true;
            } else if (value instanceof Half || value instanceof SlabType) {
                if (r.y().getAxis() != Direction.Axis.Y) return false;
                orientation = true;
            } else if (value instanceof FrontAndTop front) {
                if (FrontAndTop.fromFrontAndTop(r.apply(front.front()), r.apply(front.top())) == null) return false;
                orientation = true;
            } else if (property == BlockStateProperties.ROTATION_16) {
                if (r.y() != Direction.UP) return false;
                orientation = true;
            } else if (value instanceof AttachFace) {
                // Wall/floor variants often change geometry, rather than applying a cube rotation.
                if (r.y() != Direction.UP) return false;
                orientation = true;
            }
        }
        return orientation;
    }
    public static BlockState layer(BlockState proposed, BlockPlaceContext context, PlacementFrame frame) {
        if (proposed == null || frame.rotation == null || frame.rotation.isIdentity() || !GravityServerConfig.rotationLayer()) return null;
        if (PlacementAdapters.nativeOnly(proposed, context)) return null;
        BlockState candidate = proposed;
        if (candidate.getBlock() instanceof StairBlock) candidate = candidate.setValue(StairBlock.SHAPE, StairsShape.STRAIGHT);
        Property<ChestType> chest = PlacementProperties.find(candidate, ChestType.class);
        if (chest != null) candidate = candidate.setValue(chest, ChestType.SINGLE);
        BlockState selected = candidate;
        boolean valid = RotationScope.with(context.getLevel(), context.getClickedPos(), frame.rotation,
                () -> selected.canSurvive(context.getLevel(), context.getClickedPos()));
        if (!valid) return null;
        frame.layerRotation = frame.rotation;
        return selected;
    }
}
