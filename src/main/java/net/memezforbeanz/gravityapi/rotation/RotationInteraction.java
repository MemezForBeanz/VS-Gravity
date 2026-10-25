package net.memezforbeanz.gravityapi.rotation;

import net.memezforbeanz.gravityapi.placement.CubeRotation;
import net.minecraft.world.phys.*;
import net.minecraft.world.item.context.*;

public final class RotationInteraction {
    private RotationInteraction() {}
    public static BlockHitResult local(BlockHitResult hit, CubeRotation r) {
        Vec3 centre = Vec3.atCenterOf(hit.getBlockPos());
        return new BlockHitResult(r.inverse(hit.getLocation().subtract(centre)).add(centre), r.inverse(hit.getDirection()), hit.getBlockPos(), hit.isInside());
    }
    public static boolean isLocal(BlockPlaceContext context) { return context instanceof LocalContext; }
    public static BlockPlaceContext local(BlockPlaceContext context, CubeRotation r) { return new LocalContext(context, r); }
    private static final class LocalContext extends BlockPlaceContext {
        private BlockPlaceContext source;
        private LocalContext(BlockPlaceContext source, CubeRotation r) {
            super(source.getLevel(), source.getPlayer(), source.getHand(), source.getItemInHand(),
                    local(new BlockHitResult(source.getClickLocation(), source.getClickedFace(), source.getClickedPos(), source.isInside()), r));
            this.source = source;
            this.replaceClicked = source.replacingClickedOnBlock();
        }
        @Override public net.minecraft.core.BlockPos getClickedPos() { return source == null ? super.getClickedPos() : source.getClickedPos(); }
        @Override public boolean replacingClickedOnBlock() { return source == null ? super.replacingClickedOnBlock() : source.replacingClickedOnBlock(); }
        @Override public boolean canPlace() { return source == null ? super.canPlace() : source.canPlace(); }
    }
}
