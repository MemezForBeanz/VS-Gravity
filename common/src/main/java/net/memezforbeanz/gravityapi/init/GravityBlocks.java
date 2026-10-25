package net.memezforbeanz.gravityapi.init;

import net.memezforbeanz.gravityapi.GravityAPI;
import net.memezforbeanz.gravityapi.compat.vs2.block.GravityGeneratorBlock;
import net.memezforbeanz.gravityapi.compat.vs2.block.GravityGeneratorBlockEntity;
import net.memezforbeanz.gravityapi.plating.GravityPlatingBlock;
import net.memezforbeanz.gravityapi.plating.GravityPlatingBlockEntity;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import dev.architectury.registry.registries.DeferredRegister;
import net.minecraft.core.registries.Registries;
import dev.architectury.registry.registries.RegistrySupplier;

public class GravityBlocks
{
	public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(GravityAPI.MODID, Registries.BLOCK);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(GravityAPI.MODID, Registries.BLOCK_ENTITY_TYPE);
    
    public static final RegistrySupplier<Block> GRAVITY_PLATING = BLOCKS.register("plating", () -> new GravityPlatingBlock());
    
    public static final RegistrySupplier<BlockEntityType<GravityPlatingBlockEntity>> GRAVITY_PLATING_BLOCK_ENTITY = BLOCK_ENTITIES.register("plating", () -> BlockEntityType.Builder.of(GravityPlatingBlockEntity::new, GravityBlocks.GRAVITY_PLATING.get()).build(null));

    // Gravity Generator - creates a gravitational field that aligns to ship orientation
    public static final RegistrySupplier<Block> GRAVITY_GENERATOR = BLOCKS.register("gravity_generator", GravityGeneratorBlock::new);

    public static final RegistrySupplier<BlockEntityType<GravityGeneratorBlockEntity>> GRAVITY_GENERATOR_BLOCK_ENTITY = BLOCK_ENTITIES.register("gravity_generator", () -> BlockEntityType.Builder.of(GravityGeneratorBlockEntity::new, GravityBlocks.GRAVITY_GENERATOR.get()).build(null));
}
