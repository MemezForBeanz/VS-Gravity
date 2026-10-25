package net.memezforbeanz.gravityapi.mixin.rotation;
import net.memezforbeanz.gravityapi.rotation.RotationLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.*;
@Mixin(Level.class)
public abstract class RotationLevelMixin implements RotationLevel {
    @Unique private volatile boolean gravityapi$hasRotations;
    public boolean gravityapi$hasRotations() { return gravityapi$hasRotations; }
    public void gravityapi$markRotations() { gravityapi$hasRotations = true; }
}
