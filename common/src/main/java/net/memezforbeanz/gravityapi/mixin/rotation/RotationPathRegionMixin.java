package net.memezforbeanz.gravityapi.mixin.rotation;
import net.memezforbeanz.gravityapi.rotation.RotationView;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.*;
@Mixin(net.minecraft.world.level.PathNavigationRegion.class)
public abstract class RotationPathRegionMixin implements RotationView {
    @Shadow @Final protected Level level;
    public Level gravityapi$rotationLevel() { return level; }
}
