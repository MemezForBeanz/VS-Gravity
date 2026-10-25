package net.memezforbeanz.gravityapi.mixin.client.rotation;
import net.memezforbeanz.gravityapi.rotation.RotationView;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.*;
@Mixin(net.minecraft.client.renderer.chunk.RenderChunkRegion.class)
public abstract class RotationRenderRegionMixin implements RotationView {
    @Shadow @Final protected Level level;
    public Level gravityapi$rotationLevel() { return level; }
}
