package net.memezforbeanz.gravityapi.api.generator;

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
import org.jetbrains.annotations.Nullable;

/**
 * Base class for gravity generator blocks.
 * <p>
 * Extend this and return your own {@link AbstractGravityGeneratorBlockEntity} subclass from
 * {@link #newBlockEntity}. The base class wires up the {@code ACTIVE} blockstate property and
 * a ticker that runs on both logical sides (client-side ticking is required so the local
 * player's gravity can be predicted smoothly).
 */
public abstract class AbstractGravityGeneratorBlock extends BaseEntityBlock {

    /**
     * Whether the generator's field is enabled. Present on all gravity generator blocks;
     * subclasses must not redeclare it.
     */
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    protected AbstractGravityGeneratorBlock(BlockBehaviour.Properties properties) {
        super(properties);
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
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        // Tick on both sides: the server drives gravity, the client registers generators
        // in the client-side registry for local player prediction.
        return (lvl, pos, st, be) -> {
            if (be instanceof AbstractGravityGeneratorBlockEntity generator) {
                generator.tick(lvl, pos, st);
            }
        };
    }
}
