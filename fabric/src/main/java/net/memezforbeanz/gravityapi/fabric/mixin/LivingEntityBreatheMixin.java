package net.memezforbeanz.gravityapi.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;

/** Vanilla's breathing check (Forge moves it to ForgeHooks#onLivingBreathe, see MixinForgeHooks): the bubble column test uses the eye position. */
@Mixin(LivingEntity.class)
public abstract class LivingEntityBreatheMixin
{
    @Redirect(
            method = "baseTick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/core/BlockPos;containing(DDD)Lnet/minecraft/core/BlockPos;",
                    ordinal = 0
            )
    )
    private BlockPos redirect_baseTick_new_0(double x, double y, double z) {
        LivingEntity entity = (LivingEntity) (Object) this;
        Direction gravityDirection = GravityChangerAPI.getGravityDirection(entity);
        if (gravityDirection == Direction.DOWN) {
            return BlockPos.containing(x, y, z);
        }

        return BlockPos.containing(entity.getEyePosition());
    }
}
