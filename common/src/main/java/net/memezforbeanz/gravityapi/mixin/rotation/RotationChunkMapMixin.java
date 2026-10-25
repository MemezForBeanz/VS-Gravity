package net.memezforbeanz.gravityapi.mixin.rotation;

import net.memezforbeanz.gravityapi.rotation.RotationSync;
import net.minecraft.server.level.*;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkMap.class)
public abstract class RotationChunkMapMixin {
    @Inject(method = "playerLoadedChunk", at = @At("TAIL"))
    private void gravityapi$watch(ServerPlayer player, MutableObject<ClientboundLevelChunkWithLightPacket> packet, LevelChunk chunk, CallbackInfo ci) {
        RotationSync.sendFull(chunk, player);
    }
}
