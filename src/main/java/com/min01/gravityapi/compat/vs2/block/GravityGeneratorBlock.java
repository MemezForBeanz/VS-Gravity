package com.min01.gravityapi.compat.vs2.block;

import com.min01.gravityapi.compat.vs2.VS2Integration;
import com.min01.gravityapi.init.GravityBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Debug Gravity Generator block.
 * When placed on a Valkyrien Skies ship, this block creates a gravitational field
 * that aligns entities' gravity to the ship's "down" direction.
 */
public class GravityGeneratorBlock extends BaseEntityBlock {
    
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");
    
    public GravityGeneratorBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(3.0f, 6.0f)
                .requiresCorrectToolForDrops()
                .lightLevel(state -> state.getValue(ACTIVE) ? 10 : 0));
        this.registerDefaultState(this.stateDefinition.any().setValue(ACTIVE, true));
    }
    
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ACTIVE);
    }
    
    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }
    
    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GravityGeneratorBlockEntity(pos, state);
    }
    
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        if (!level.isClientSide()) {
            return createTickerHelper(type, GravityBlocks.GRAVITY_GENERATOR_BLOCK_ENTITY.get(), GravityGeneratorBlockEntity::serverTick);
        } else {
            // Client side ticker - needed to register generators in the client-side registry
            // so the client can properly compute gravity effects for local player prediction
            return createTickerHelper(type, GravityBlocks.GRAVITY_GENERATOR_BLOCK_ENTITY.get(), GravityGeneratorBlockEntity::clientTick);
        }
    }
    
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!level.isClientSide()) {
            // Toggle active state
            boolean isActive = state.getValue(ACTIVE);
            level.setBlock(pos, state.setValue(ACTIVE, !isActive), 3);
            
            // Send feedback to player
            if (!isActive) {
                player.displayClientMessage(Component.literal("Gravity Generator: §aActivated"), true);
            } else {
                player.displayClientMessage(Component.literal("Gravity Generator: §cDeactivated"), true);
            }
            
            // Check if on ship and inform player
            if (VS2Integration.isVS2Loaded()) {
                BlockEntity be = level.getBlockEntity(pos);
                if (be instanceof GravityGeneratorBlockEntity generator) {
                    if (generator.isOnShip()) {
                        player.displayClientMessage(Component.literal("§7[On Ship - Field Active]"), true);
                    } else {
                        player.displayClientMessage(Component.literal("§7[Not on Ship - Works in world space]"), true);
                    }
                }
            }
            
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}

