package net.memezforbeanz.gravityapi.compat.vs2.block;

import net.memezforbeanz.gravityapi.api.generator.AbstractGravityGeneratorBlock;
import net.memezforbeanz.gravityapi.compat.vs2.VS2Integration;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Debug Gravity Generator block.
 * When placed on a Valkyrien Skies ship, this block creates a gravitational field
 * that aligns entities' gravity to the ship's "down" direction.
 * <p>
 * This is the reference implementation of {@link AbstractGravityGeneratorBlock};
 * other mods should extend the abstract classes rather than these debug ones.
 */
public class GravityGeneratorBlock extends AbstractGravityGeneratorBlock {

    public GravityGeneratorBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(3.0f, 6.0f)
                .requiresCorrectToolForDrops()
                .lightLevel(state -> state.getValue(ACTIVE) ? 10 : 0));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new GravityGeneratorBlockEntity(pos, state);
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
