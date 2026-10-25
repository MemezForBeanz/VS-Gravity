package net.memezforbeanz.gravityapi.rotation;

import net.minecraft.nbt.CompoundTag;

public interface RotationPending {
    CompoundTag gravityapi$pendingRotations();
    void gravityapi$pendingRotations(CompoundTag tag);
}
