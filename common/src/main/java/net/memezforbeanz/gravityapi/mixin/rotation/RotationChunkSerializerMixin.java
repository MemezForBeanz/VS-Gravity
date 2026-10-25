package net.memezforbeanz.gravityapi.mixin.rotation;

import net.memezforbeanz.gravityapi.rotation.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkSerializer.class)
public abstract class RotationChunkSerializerMixin {
    @Inject(method = "read", at = @At("RETURN"))
    private static void gravityapi$read(ServerLevel level, PoiManager poi, ChunkPos pos, CompoundTag tag, CallbackInfoReturnable<ProtoChunk> cir) {
        CompoundTag source = tag.contains("gravityapi:rotations", 10) ? tag : tag.getCompound("ForgeCaps");
        if (!source.contains("gravityapi:rotations", 10)) return;
        CompoundTag rotations = source.getCompound("gravityapi:rotations");
        ProtoChunk proto = cir.getReturnValue();
        if (proto instanceof ImposterProtoChunk imposter) {
            ((RotationChunk) imposter.getWrapped()).gravityapi$rotations().deserializeNBT(rotations);
        } else ((RotationPending) proto).gravityapi$pendingRotations(rotations);
    }
    @Inject(method = "write", at = @At("RETURN"))
    private static void gravityapi$write(ServerLevel level, ChunkAccess chunk, CallbackInfoReturnable<CompoundTag> cir) {
        if (chunk instanceof ImposterProtoChunk imposter) chunk = imposter.getWrapped();
        CompoundTag tag = null;
        if (chunk instanceof LevelChunk full) tag = ((RotationChunk) full).gravityapi$rotations().serializeNBT();
        else if (chunk instanceof RotationPending pending) tag = pending.gravityapi$pendingRotations();
        if (tag != null) cir.getReturnValue().put("gravityapi:rotations", tag);
    }
}
