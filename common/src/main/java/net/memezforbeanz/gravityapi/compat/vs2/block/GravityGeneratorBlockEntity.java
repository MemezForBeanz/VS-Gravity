package net.memezforbeanz.gravityapi.compat.vs2.block;

import net.memezforbeanz.gravityapi.api.generator.AbstractGravityGeneratorBlockEntity;
import net.memezforbeanz.gravityapi.init.GravityBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Block entity for the debug Gravity Generator.
 * All the field logic lives in {@link AbstractGravityGeneratorBlockEntity};
 * this subclass only adds throttled debug logging.
 */
public class GravityGeneratorBlockEntity extends AbstractGravityGeneratorBlockEntity {

    private static final Logger GRAVITY_LOG = LoggerFactory.getLogger("GravityGenerator");

    public GravityGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(GravityBlocks.GRAVITY_GENERATOR_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    protected void onActivated() {
        if (level != null && !level.isClientSide()) {
            GRAVITY_LOG.info("[GravityGenerator] Registered at pos {}", getBlockPos());
        }
    }

    @Override
    protected void onDeactivated() {
        if (level != null && !level.isClientSide()) {
            GRAVITY_LOG.info("[GravityGenerator] Unregistered at pos {}", getBlockPos());
        }
    }

    @Override
    protected void onFieldTick(Level level, BlockPos pos, BlockState state) {
        if (!level.isClientSide() && tickCounter % 100 == 0) { // Log every 5 seconds
            GRAVITY_LOG.info("[GravityGenerator] Active at pos {}, isOnShip: {}, shipId: {}, gravity: {}",
                    pos, isOnShip(), getShipId(), computeGravityDirection());
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null && !level.isClientSide()) {
            GRAVITY_LOG.info("[GravityGenerator] Removed and unregistered at pos {}", getBlockPos());
        }
    }
}
