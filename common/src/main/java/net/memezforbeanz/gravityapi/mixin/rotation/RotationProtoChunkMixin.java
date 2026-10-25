package net.memezforbeanz.gravityapi.mixin.rotation;

import net.memezforbeanz.gravityapi.rotation.RotationPending;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.*;

@Mixin(ProtoChunk.class)
public abstract class RotationProtoChunkMixin implements RotationPending {
    @Unique private CompoundTag gravityapi$pending;
    public CompoundTag gravityapi$pendingRotations() { return gravityapi$pending; }
    public void gravityapi$pendingRotations(CompoundTag tag) { gravityapi$pending = tag; }
}
