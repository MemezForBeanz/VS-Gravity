package net.memezforbeanz.gravityapi.init;

import net.memezforbeanz.gravityapi.GravityAPI;
import net.memezforbeanz.gravityapi.mob_effect.GravityDirectionMobEffect;
import net.memezforbeanz.gravityapi.mob_effect.GravityInvertMobEffect;
import net.memezforbeanz.gravityapi.mob_effect.GravityStrengthMobEffect;

import net.minecraft.core.Direction;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.alchemy.Potion;
import dev.architectury.registry.registries.DeferredRegister;
import net.minecraft.core.registries.Registries;
import dev.architectury.registry.registries.RegistrySupplier;

public class GravityMobEffects 
{
	public static final DeferredRegister<MobEffect> EFFECTS = DeferredRegister.create(GravityAPI.MODID, Registries.MOB_EFFECT);
	public static final DeferredRegister<Potion> POTIONS = DeferredRegister.create(GravityAPI.MODID, Registries.POTION);

    public static final RegistrySupplier<GravityDirectionMobEffect> DOWN = EFFECTS.register("down", () -> new GravityDirectionMobEffect(Direction.DOWN));
    public static final RegistrySupplier<GravityDirectionMobEffect> UP = EFFECTS.register("up", () -> new GravityDirectionMobEffect(Direction.UP));
    public static final RegistrySupplier<GravityDirectionMobEffect> NORTH = EFFECTS.register("north", () -> new GravityDirectionMobEffect(Direction.NORTH));
    public static final RegistrySupplier<GravityDirectionMobEffect> SOUTH = EFFECTS.register("south", () -> new GravityDirectionMobEffect(Direction.SOUTH));
    public static final RegistrySupplier<GravityDirectionMobEffect> WEST = EFFECTS.register("west", () -> new GravityDirectionMobEffect(Direction.WEST));
    public static final RegistrySupplier<GravityDirectionMobEffect> EAST = EFFECTS.register("east", () -> new GravityDirectionMobEffect(Direction.EAST));
    
    public static final RegistrySupplier<GravityInvertMobEffect> INVERT = EFFECTS.register("invert", () -> new GravityInvertMobEffect());
    
    public static final RegistrySupplier<GravityStrengthMobEffect> INCREASE = EFFECTS.register("increase", () -> new GravityStrengthMobEffect(0x98D982, 1.2, 1));
    public static final RegistrySupplier<GravityStrengthMobEffect> DECREASE = EFFECTS.register("decrease", () -> new GravityStrengthMobEffect(0x98D982, 0.7, 1));
    
    // it turns gravity into levitation but does not change player orientation
    public static final RegistrySupplier<GravityStrengthMobEffect> REVERSE = EFFECTS.register("reverse", () -> new GravityStrengthMobEffect(0x98D982, 1.0, -1));
    
    public static final RegistrySupplier<Potion> DOWN_POTION = POTIONS.register("gravity_down_0", () -> new Potion(
            new MobEffectInstance(
                DOWN.get(), 9600, 1
            )
    ));
    
    public static final RegistrySupplier<Potion> UP_POTION = POTIONS.register("gravity_up_0", () -> new Potion(
            new MobEffectInstance(
            		UP.get(), 9600, 1
            )
    ));
    
    public static final RegistrySupplier<Potion> NORTH_POTION = POTIONS.register("gravity_north_0", () -> new Potion(
            new MobEffectInstance(
            		NORTH.get(), 9600, 1
            )
    ));
    
    public static final RegistrySupplier<Potion> SOUTH_POTION = POTIONS.register("gravity_south_0", () -> new Potion(
            new MobEffectInstance(
            		SOUTH.get(), 9600, 1
            )
    ));

    public static final RegistrySupplier<Potion> WEST_POTION = POTIONS.register("gravity_west_0", () -> new Potion(
            new MobEffectInstance(
            		WEST.get(), 9600, 1
            )
    ));
    
    public static final RegistrySupplier<Potion> EAST_POTION = POTIONS.register("gravity_east_0", () -> new Potion(
            new MobEffectInstance(
            		EAST.get(), 9600, 1
            )
    ));
    
    public static final RegistrySupplier<Potion> STRENGTH_DECR_POTION_0 = POTIONS.register("gravity_decr_0", () -> new Potion(
        new MobEffectInstance(
            DECREASE.get(), 9600, 0
        )
    ));
    
    public static final RegistrySupplier<Potion> STRENGTH_DECR_POTION_1 = POTIONS.register("gravity_decr_1", () -> new Potion(
        new MobEffectInstance(
            DECREASE.get(), 9600, 1
        )
    ));
    
    public static final RegistrySupplier<Potion> STRENGTH_INCR_POTION_0 = POTIONS.register("gravity_incr_0", () -> new Potion(
        new MobEffectInstance(
            INCREASE.get(), 9600, 0
        )
    ));
    
    public static final RegistrySupplier<Potion> STRENGTH_INCR_POTION_1 = POTIONS.register("gravity_incr_1", () -> new Potion(
        new MobEffectInstance(
            INCREASE.get(), 9600, 1
        )
    ));
    
    public static final RegistrySupplier<Potion> STRENGTH_REVERSE_POTION_0 = POTIONS.register("gravity_reverse_0", () -> new Potion(
        new MobEffectInstance(
            REVERSE.get(), 9600, 0
        )
    ));
    
    public static final RegistrySupplier<Potion> STRENGTH_REVERSE_POTION_1 = POTIONS.register("gravity_reverse_1", () -> new Potion(
        new MobEffectInstance(
            REVERSE.get(), 9600, 1
        )
    ));
}
