package net.memezforbeanz.gravityapi.mixin.compat;

import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.minecraftforge.eventbus.api.Event;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stands Cosmic Horizons' planet gravity down for entities under a gravity strength
 * override. Cosmos has no gravity API: each entity kind gets an MCreator procedure that,
 * once its dimension/water/elytra gates pass, reads the dimension's gravity and either
 * sets {@code noGravity} (0 g) or adds a world-Y velocity correction. We cancel right
 * where it reads the gravity value — i.e. only when Cosmos would actually act — and clear
 * the {@code noGravity} flag it may have set before the entity entered the field, so the
 * override's strength is the only gravity felt.
 * <p>
 * Only applied when Cosmic Horizons ({@code cosmos}) is loaded; see GravityMixinPlugin.
 */
@Pseudo
@Mixin(targets = {
        "net.lointain.cosmos.procedures.LivingEntityProcedure",
        "net.lointain.cosmos.procedures.TNTITEMMINECARTProcedure",
        "net.lointain.cosmos.procedures.AbstractArrowProcedure",
        "net.lointain.cosmos.procedures.BoatProcedure",
        "net.lointain.cosmos.procedures.ThrowableProjectileProcedure",
        "net.lointain.cosmos.procedures.FishingHookProcedure",
        "net.lointain.cosmos.procedures.ThrownPotionProcedure",
        "net.lointain.cosmos.procedures.ThrownExperienceBottleProcedure"
}, remap = false)
public abstract class CosmosGravityProcedureMixin {

    @Inject(
            method = "execute(Lnet/minecraftforge/eventbus/api/Event;Lnet/minecraft/world/level/LevelAccessor;Lnet/minecraft/world/entity/Entity;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/lointain/cosmos/procedures/GravityDataProviderProcedure;execute(Lnet/minecraft/world/level/LevelAccessor;Ljava/lang/String;)D"),
            cancellable = true,
            remap = false,
            require = 0
    )
    private static void gravityapi$yieldToStrengthOverride(Event event, LevelAccessor world, Entity entity,
                                                            CallbackInfo ci) {
        if (entity != null && GravityChangerAPI.isGravityStrengthOverridden(entity)) {
            if (entity.isNoGravity()) {
                entity.setNoGravity(false);
            }
            ci.cancel();
        }
    }
}
