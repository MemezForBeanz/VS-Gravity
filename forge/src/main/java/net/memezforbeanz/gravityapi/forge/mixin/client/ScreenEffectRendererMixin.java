package net.memezforbeanz.gravityapi.forge.mixin.client;

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

/** Forge routes the in-wall overlay through its own getOverlayBlock. */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {
    @Inject(method = "getOverlayBlock", at = @At("HEAD"), cancellable = true, remap = false)
    private static void inject_getInWallBlockState(Player player, CallbackInfoReturnable<Pair<BlockState, BlockPos>> cir) {
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(player);
        if (gravityDirection == Direction.DOWN) return;
        cir.setReturnValue(InWallOverlay.find(player, gravityDirection));
    }
}
