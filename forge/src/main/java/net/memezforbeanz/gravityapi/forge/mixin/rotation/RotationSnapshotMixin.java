package net.memezforbeanz.gravityapi.forge.mixin.rotation;

import net.memezforbeanz.gravityapi.placement.CubeRotation;
import net.memezforbeanz.gravityapi.rotation.RotationLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraftforge.common.util.BlockSnapshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Canceled Forge placements restore snapshots; the stored rotation must roll back with them. */
@Mixin(value = BlockSnapshot.class, remap = false)
public abstract class RotationSnapshotMixin {
    @Unique private CubeRotation gravityapi$rotation;

    @Inject(method = "create(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/core/BlockPos;I)Lnet/minecraftforge/common/util/BlockSnapshot;", at = @At("RETURN"))
    private static void gravityapi$capture(ResourceKey<Level> dim, LevelAccessor level, BlockPos pos, int flag, CallbackInfoReturnable<BlockSnapshot> cir) {
        ((RotationSnapshotMixin) (Object) cir.getReturnValue()).gravityapi$rotation = RotationLookup.get(level, pos);
    }

    @Inject(method = "restoreToLocation", at = @At("RETURN"))
    private void gravityapi$restore(LevelAccessor level, BlockPos pos, boolean force, boolean notify, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && level instanceof Level world) RotationLookup.set(world, pos, gravityapi$rotation);
    }
}
