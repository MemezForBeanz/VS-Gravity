package net.memezforbeanz.gravityapi.platform.fabric;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.BlockPathTypes;

public final class PlatformHooksImpl {
    public static float stepHeight(Entity entity) {
        return entity.maxUpStep();
    }

    // Fabric has no per-block path type override; the vanilla rules apply
    public static BlockPathTypes blockPathType(BlockState state, BlockGetter level, BlockPos pos, Mob mob) {
        return null;
    }

    public static BlockPathTypes fluidPathType(FluidState state, BlockGetter level, BlockPos pos, Mob mob, boolean canFluidLog) {
        return null;
    }
}
