package net.memezforbeanz.gravityapi.mixin.client.rotation;

import net.memezforbeanz.gravityapi.rotation.RotationView;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.*;
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.world.WorldSlice", remap = false)
public abstract class EmbeddiumWorldSliceMixin implements RotationView {
    @Shadow @Final public ClientLevel world;
    public Level gravityapi$rotationLevel() { return world; }
}
