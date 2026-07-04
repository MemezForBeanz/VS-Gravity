package com.min01.gravityapi.init;

import com.min01.gravityapi.GravityAPI;
import com.min01.gravityapi.compat.vs2.block.GravityGeneratorBlock;
import com.min01.gravityapi.compat.vs2.block.GravityGeneratorBlockEntity;
import com.min01.gravityapi.plating.GravityPlatingBlock;
import com.min01.gravityapi.plating.GravityPlatingBlockEntity;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class GravityBlocks
{
	public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, GravityAPI.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, GravityAPI.MODID);
    
    public static final RegistryObject<Block> GRAVITY_PLATING = BLOCKS.register("plating", () -> new GravityPlatingBlock());
    
    public static final RegistryObject<BlockEntityType<GravityPlatingBlockEntity>> GRAVITY_PLATING_BLOCK_ENTITY = BLOCK_ENTITIES.register("plating", () -> BlockEntityType.Builder.of(GravityPlatingBlockEntity::new, GravityBlocks.GRAVITY_PLATING.get()).build(null));

    // Gravity Generator - creates a gravitational field that aligns to ship orientation
    public static final RegistryObject<Block> GRAVITY_GENERATOR = BLOCKS.register("gravity_generator", GravityGeneratorBlock::new);

    public static final RegistryObject<BlockEntityType<GravityGeneratorBlockEntity>> GRAVITY_GENERATOR_BLOCK_ENTITY = BLOCK_ENTITIES.register("gravity_generator", () -> BlockEntityType.Builder.of(GravityGeneratorBlockEntity::new, GravityBlocks.GRAVITY_GENERATOR.get()).build(null));
}
