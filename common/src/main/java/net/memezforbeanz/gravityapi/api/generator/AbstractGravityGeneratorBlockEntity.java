package net.memezforbeanz.gravityapi.api.generator;

import net.memezforbeanz.gravityapi.EntityTags;
import net.memezforbeanz.gravityapi.api.GravityDirection;
import net.memezforbeanz.gravityapi.api.RotationParameters;
import net.memezforbeanz.gravityapi.compat.vs2.VS2Helper;
import net.memezforbeanz.gravityapi.compat.vs2.VS2Integration;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Base class for gravity generator block entities (with {@link AbstractGravityGeneratorBlock}).
 * Handles registration with the {@link GravityGeneratorRegistry} on both sides, VS2 ship
 * detection and transforms (reflection-based, VS2 optional), and a field direction that follows
 * the ship's down on a ship.
 * <p>
 * Customize through {@link #getFieldRadius()}, {@link #getGravityPriority()},
 * {@link #getRotationParameters()}, {@link #getBaseGravityDirection()}, {@link #isInField(Entity)}
 * and {@link #canAffect(Entity)}.
 */
public abstract class AbstractGravityGeneratorBlockEntity extends BlockEntity {

    /** Default radius of the gravitational field in blocks. */
    public static final double DEFAULT_FIELD_RADIUS = 20.0;

    /** Default priority for the gravity effect (higher = overrides other effects). */
    public static final double DEFAULT_GRAVITY_PRIORITY = 50.0;

    @Nullable
    private Long cachedShipId = null;

    private boolean shipCheckDone = false;

    private boolean registered = false;

    /** Tick counter, usable by subclasses for throttled logic/logging. */
    protected int tickCounter = 0;

    protected AbstractGravityGeneratorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ------------------------------------------------------------------
    // Overridable API
    // ------------------------------------------------------------------

    /**
     * The radius of the gravitational field in blocks.
     */
    public double getFieldRadius() {
        return DEFAULT_FIELD_RADIUS;
    }

    /**
     * The priority of the gravity effect this generator applies.
     * Higher priorities override lower-priority effects.
     */
    public double getGravityPriority() {
        return DEFAULT_GRAVITY_PRIORITY;
    }

    /**
     * The rotation parameters used when the field rotates an entity's gravity.
     */
    public RotationParameters getRotationParameters() {
        return RotationParameters.getDefault();
    }

    /**
     * Whether this generator is currently producing a field.
     * By default this reads the {@link AbstractGravityGeneratorBlock#ACTIVE} blockstate
     * property if present, and is otherwise always active.
     */
    public boolean isGeneratorActive() {
        BlockState state = getBlockState();
        return !state.hasProperty(AbstractGravityGeneratorBlock.ACTIVE)
                || state.getValue(AbstractGravityGeneratorBlock.ACTIVE);
    }

    /**
     * Whether this generator's field can affect the given entity.
     */
    public boolean canAffect(Entity entity) {
        return EntityTags.canChangeGravity(entity);
    }

    /**
     * Whether the given entity is inside this generator's field.
     * The default implementation is a sphere of {@link #getFieldRadius()} blocks
     * around the generator's effective world position. Override for other field shapes.
     */
    public boolean isInField(Entity entity) {
        Vec3 center = getEffectiveWorldPosition();
        if (center == null) {
            return false;
        }
        double radius = getFieldRadius();
        return entity.position().distanceToSqr(center) <= radius * radius;
    }

    /**
     * The gravity direction this generator projects, in its local frame: ship-local on a VS ship
     * ({@link GravityDirection#DOWN} is the ship's down), world space otherwise. Any vector works;
     * {@code null} skips the field this tick. Default {@link GravityDirection#DOWN}.
     */
    @Nullable
    public GravityDirection getBaseGravityDirection() {
        return GravityDirection.DOWN;
    }

    /**
     * The field's gravity direction in world space: {@link #getBaseGravityDirection()} carried
     * through the ship transform on a ship. Override only to bypass the ship transform.
     *
     * @return the direction, or {@code null} to skip the field this tick
     */
    @Nullable
    public GravityDirection computeGravityDirection() {
        GravityDirection base = getBaseGravityDirection();
        if (base == null) {
            return null;
        }
        if (level != null && isOnShip()) {
            Vec3 worldDir = VS2Helper.shipToWorldDirectionFromBlock(level, getBlockPos(), base.getVector());
            if (worldDir != null) {
                return GravityDirection.fromVector(worldDir);
            }
        }
        return base;
    }

    /**
     * Called once when the generator becomes active (registered).
     */
    protected void onActivated() {
    }

    /**
     * Called once when the generator becomes inactive (unregistered).
     */
    protected void onDeactivated() {
    }

    /**
     * Called every tick while the generator is active, on both logical sides.
     * Use {@link Level#isClientSide()} to distinguish.
     */
    protected void onFieldTick(Level level, BlockPos pos, BlockState state) {
    }

    // ------------------------------------------------------------------
    // Ship helpers
    // ------------------------------------------------------------------

    /**
     * Check if this block is on a Valkyrien Skies ship. Cached per tick.
     */
    public boolean isOnShip() {
        if (!VS2Integration.isVS2Loaded() || level == null) {
            return false;
        }
        if (!shipCheckDone) {
            cachedShipId = VS2Helper.getShipIdAtBlock(level, getBlockPos());
            shipCheckDone = true;
        }
        return cachedShipId != null;
    }

    /**
     * Get the ship ID this block is on, or null if not on a ship.
     */
    @Nullable
    public Long getShipId() {
        isOnShip(); // Ensure cache is populated
        return cachedShipId;
    }

    /**
     * Get the world-space position of the center of this generator's field.
     * When on a ship, the shipyard position is transformed to world space.
     *
     * @return the world position, or {@code null} if the ship transform failed
     */
    @Nullable
    public Vec3 getEffectiveWorldPosition() {
        if (level == null) {
            return null;
        }
        Vec3 blockCenter = Vec3.atCenterOf(getBlockPos());
        if (isOnShip()) {
            // May return null if the transform fails; callers skip the generator then
            return VS2Helper.shipToWorldPosition(level, getBlockPos(), blockCenter);
        }
        return blockCenter;
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Ticked on both logical sides by {@link AbstractGravityGeneratorBlock}.
     */
    public void tick(Level level, BlockPos pos, BlockState state) {
        shipCheckDone = false;
        tickCounter++;

        boolean active = isGeneratorActive();
        if (active && !registered) {
            GravityGeneratorRegistry.registerGenerator(level, pos);
            registered = true;
            onActivated();
        } else if (!active && registered) {
            GravityGeneratorRegistry.unregisterGenerator(level, pos);
            registered = false;
            onDeactivated();
        }

        if (active) {
            onFieldTick(level, pos, state);
        }
    }

    private void unregister() {
        if (level != null && registered) {
            GravityGeneratorRegistry.unregisterGenerator(level, getBlockPos());
            registered = false;
        }
    }

    // Also runs when the chunk unloads; the ticker re-registers it when the chunk loads again
    @Override
    public void setRemoved() {
        super.setRemoved();
        unregister();
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }
}
