package net.memezforbeanz.gravityapi.platform;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import org.jetbrains.annotations.Nullable;

/** Loader-specific behaviour that Forge patches into vanilla classes. */
public final class PlatformHooks {
    private PlatformHooks() {}

    /** Step height including Forge's step height attribute. */
    @ExpectPlatform
    public static float stepHeight(Entity entity) {
        throw new AssertionError();
    }

    /** A block's own path type override (Forge's IForgeBlockState#getBlockPathType), or null for the vanilla rules. */
    @ExpectPlatform
    public static @Nullable BlockPathTypes blockPathType(BlockState state, BlockGetter level, BlockPos pos, @Nullable Mob mob) {
        throw new AssertionError();
    }

    /** A fluid's own path type override (Forge's IForgeFluidState#getBlockPathType), or null for the vanilla rules. */
    @ExpectPlatform
    public static @Nullable BlockPathTypes fluidPathType(FluidState state, BlockGetter level, BlockPos pos, @Nullable Mob mob, boolean canFluidLog) {
        throw new AssertionError();
    }
}
