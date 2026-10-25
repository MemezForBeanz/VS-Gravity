package net.memezforbeanz.gravityapi.fabric.mixin.client;

import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.client.InWallOverlay;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

/** Vanilla picks the in-wall overlay block in getViewBlockingState. */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {
    @Inject(method = "getViewBlockingState", at = @At("HEAD"), cancellable = true)
    private static void inject_getInWallBlockState(Player player, CallbackInfoReturnable<BlockState> cir) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(player);
        if (gravityDirection == Direction.DOWN) return;
        Pair<BlockState, BlockPos> overlay = InWallOverlay.find(player, gravityDirection);
        cir.setReturnValue(overlay == null ? null : overlay.getLeft());
    }
}
