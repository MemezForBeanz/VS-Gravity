package net.memezforbeanz.gravityapi.plating;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;

import net.memezforbeanz.gravityapi.EntityTags;
import net.memezforbeanz.gravityapi.api.GravityChangerAPI;
import net.memezforbeanz.gravityapi.api.GravityDirection;
import net.memezforbeanz.gravityapi.capabilities.GravityCapabilityImpl;
import net.memezforbeanz.gravityapi.compat.vs2.VS2Helper;
import net.memezforbeanz.gravityapi.compat.vs2.VS2Integration;
import net.memezforbeanz.gravityapi.config.GravityConfig;
import net.memezforbeanz.gravityapi.init.GravityBlocks;
import net.memezforbeanz.gravityapi.util.GCUtil;
import net.memezforbeanz.gravityapi.util.RotationUtil;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Based on code from AmethystGravity (by CyborgCabbage)
 */
public class GravityPlatingBlockEntity extends BlockEntity {
    
    private static final int MAX_LEVEL = 64;
    
    public GravityPlatingBlockEntity(BlockPos pos, BlockState state) {
        super(GravityBlocks.GRAVITY_PLATING_BLOCK_ENTITY.get(), pos, state);
    }
    
    public static class SideData {
        public boolean isAttracting = true;
        public int level = 1;
        
        public @Nullable AABB effectBoxCache = null;
        
        public SideData(boolean isAttracting, int level) {
            this.isAttracting = isAttracting;
            this.level = level;
        }
        
        public static SideData createDefault() {
            return new SideData(true, 1);
        }
        
        public static SideData fromTag(CompoundTag tag) {
            boolean isAttracting_ = tag.getBoolean("isAttracting");
            int level_ = tag.getInt("level");
            
            level_ = Mth.clamp(level_, 1, MAX_LEVEL);
            
            return new SideData(isAttracting_, level_);
        }
        
        public CompoundTag toTag() {
            CompoundTag tag = new CompoundTag();
            tag.putBoolean("isAttracting", isAttracting);
            tag.putInt("level", level);
            return tag;
        }
        
        public double getEffectRange() {
            return level - 0.1;
        }
        
        public AABB getEffectBox(BlockPos blockPos, Direction plateDir, Level world) {
            if (effectBoxCache == null) {
                double expand = 0.001;
                
                double minX = blockPos.getX() - expand;
                double minY = blockPos.getY() - expand;
                double minZ = blockPos.getZ() - expand;
                double maxX = blockPos.getX() + 1 + expand;
                double maxY = blockPos.getY() + 1 + expand;
                double maxZ = blockPos.getZ() + 1 + expand;
                
                double delta = getEffectRange() - 1;
                switch (plateDir) {
                    case DOWN -> maxY += delta;
                    case UP -> minY -= delta;
                    case NORTH -> maxZ += delta;
                    case SOUTH -> minZ -= delta;
                    case WEST -> maxX += delta;
                    case EAST -> minX -= delta;
                }
                
                BlockPos wallPos = blockPos.relative(plateDir);
                for (Direction sideDir : Direction.values()) {
                    if (sideDir.getAxis() == plateDir.getAxis()) {continue;}
                    
                    BlockPos sidePos = wallPos.relative(sideDir);
                    BlockState sideBlockState = world.getBlockState(sidePos);
                    if (!(sideBlockState.getBlock() instanceof GravityPlatingBlock sidePlatingBlock)) {continue;}
                    
                    if (!GravityPlatingBlock.hasDir(sideBlockState, sideDir.getOpposite())) {continue;}
                    
                    if (!(world.getBlockEntity(sidePos) instanceof GravityPlatingBlockEntity be)) {continue;}
                    
                    if (isAttracting != this.isAttracting) {continue;}
                    
                    double sideDelta = getEffectRange();
                    switch (sideDir) {
                        case DOWN -> minY -= sideDelta;
                        case UP -> maxY += sideDelta;
                        case NORTH -> minZ -= sideDelta;
                        case SOUTH -> maxZ += sideDelta;
                        case WEST -> minX -= sideDelta;
                        case EAST -> maxX += sideDelta;
                    }
                }
                
                effectBoxCache = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
            }
            
            return effectBoxCache;
        }
    }
    
    private @Nullable SideData[] sideData = null;
    
    private @Nullable AABB roughAreaBoxCache = null;
    
    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        
        sideData = new SideData[6];
        for (Direction dir : Direction.values()) {
            String dirName = dir.getName();
            if (tag.contains(dirName)) {
                CompoundTag sideTag = tag.getCompound(dirName);
                sideData[dir.ordinal()] = SideData.fromTag(sideTag);
            }
        }
    }
    
    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        
        if (sideData != null) {
            for (Direction dir : Direction.values()) {
                String dirName = dir.getName();
                SideData side = sideData[dir.ordinal()];
                if (side != null) {
                    tag.put(dirName, side.toTag());
                }
            }
        }
    }
    
    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
    
    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag);
        return tag;
    }
    
    public void refreshCache() {
        Level world = getLevel();
        
        if (world == null) {
            return;
        }
        
        if (sideData == null) {
            sideData = new SideData[6];
        }
        
        BlockState blockState = world.getBlockState(this.getBlockPos());
        for (Direction dir : Direction.values()) {
            if (GravityPlatingBlock.hasDir(blockState, dir)) {
                if (sideData[dir.ordinal()] == null) {
                    sideData[dir.ordinal()] = SideData.createDefault();
                }
            }
            else {
                sideData[dir.ordinal()] = null;
            }
        }
        
        if (this.worldPosition.hashCode() % 5 == world.getGameTime() % 5) {
            roughAreaBoxCache = null;
            for (SideData sideDatum : sideData) {
                if (sideDatum != null) {
                    sideDatum.effectBoxCache = null;
                }
            }
        }
    }
    
    private AABB getRoughEffectBox() {
        if (roughAreaBoxCache == null) {
            double maxRange = 0;
            for (SideData sideDatum : sideData) {
                if (sideDatum != null) {
                    maxRange = Math.max(maxRange, sideDatum.getEffectRange());
                }
            }
            
            BlockPos blockPos = this.getBlockPos();
            double expand = 0.001;
            double delta = maxRange + expand;
            return new AABB(
                blockPos.getX() - delta, blockPos.getY() - delta, blockPos.getZ() - delta,
                blockPos.getX() + 1 + delta, blockPos.getY() + 1 + delta, blockPos.getZ() + 1 + delta
            );
        }
        return roughAreaBoxCache;
    }
    
    public static void tick(Level world, BlockPos blockPos, BlockState blockState, GravityPlatingBlockEntity be) {
        if (!(blockState.getBlock() instanceof GravityPlatingBlock gravityPlatingBlock)) {
            return;
        }

        be.refreshCache();

        // On a VS ship, this block entity ticks at shipyard coordinates while the entities it
        // should affect exist at world coordinates: queries and geometry tests must be swept
        // between the two frames, and the applied direction must be the plate normal rotated
        // into the world frame.
        boolean onShip = VS2Integration.isVS2Loaded() && VS2Helper.isBlockOnShip(world, blockPos);

        // AmethystGravity-reattracted model: plates only nominate candidates; the entity's
        // gravity capability resolves them once per tick with hysteresis. Detection is
        // box-vs-box - the plate's effect box against a collider spanning the entity's feet
        // in its current gravity frame - which keeps registration stable while standing on
        // walls and through rotation animations (a feet-point test drifts out of range).
        for (Direction plateDir : Direction.values()) {
            SideData sideDatum = be.sideData[plateDir.ordinal()];
            if (sideDatum == null) {
                continue;
            }
            Direction gravityEffectDir = sideDatum.isAttracting ? plateDir : plateDir.getOpposite();

            Vec3 worldDir;
            if (onShip) {
                worldDir = VS2Helper.shipToWorldDirectionFromBlock(
                        world, blockPos, Vec3.atLowerCornerOf(gravityEffectDir.getNormal()));
                if (worldDir == null) {
                    continue;
                }
                worldDir = worldDir.normalize();
            } else {
                worldDir = Vec3.atLowerCornerOf(gravityEffectDir.getNormal());
            }

            AABB gravityEffectBox = sideDatum.getEffectBox(blockPos, plateDir, world);

            AABB queryBox = gravityEffectBox.inflate(1.0);
            if (onShip) {
                queryBox = VS2Helper.shipBoxToWorldBox(world, blockPos, queryBox);
                if (queryBox == null) {
                    continue;
                }
            }

            List<Entity> entities = world.getEntitiesOfClass(
                Entity.class, queryBox, e -> EntityTags.canChangeGravity(e)
            );

            for (Entity entity : entities) {
                // Players are client-authoritative for plating: the local client detects
                // and reports its choice (UpdatePlatingGravityPacket); the server injects
                // the verified report instead of detecting on its stale view of the player.
                // Remote players on the client are sync-driven.
                if (entity instanceof Player) {
                    if (!world.isClientSide() || !GCUtil.isClientPlayer(entity)) {
                        continue;
                    }
                }
                GravityCapabilityImpl comp = GravityChangerAPI.getGravityComponent(entity);
                if (comp == null) {
                    continue;
                }

                AABB collider = getGravityEffectCollider(entity, comp, worldDir, onShip);
                if (onShip) {
                    collider = VS2Helper.worldBoxToShipBox(world, blockPos, collider);
                    if (collider == null) {
                        continue;
                    }
                }
                if (!gravityEffectBox.intersects(collider)) {
                    continue;
                }

                comp.addPlatingCandidate(worldDir, onShip, sideDatum.getEffectRange(), blockPos);

                // The corner auto-jump math is world-frame; skip it on ships
                if (!onShip && GravityConfig.autoJumpOnGravityPlateInnerCorner.get()) {
                    tryToDoCornerAutoJump(blockState, blockPos, entity, comp);
                }
            }
        }
    }

    /** See {@link GravityChangerAPI#getGravityEffectCollider} — shared with dependent mods' fields. */
    private static AABB getGravityEffectCollider(
        Entity entity, GravityCapabilityImpl comp, Vec3 effectWorldDir, boolean onShip
    ) {
        return GravityChangerAPI.getGravityEffectCollider(entity, effectWorldDir, onShip);
    }
    
    // when approaching an inward corner, do auto-jump to make it smoothly go forward
    private static void tryToDoCornerAutoJump(
        BlockState blockState, BlockPos blockPos,
        Entity entity, GravityCapabilityImpl comp
    ) {
        if (!entity.onGround()) {
            return;
        }
        
        // apply levitation when the entity is close to corner
        Direction entityGravityDir = comp.getCurrGravityDirection();
        
        for (Direction plateDir : Direction.values()) {
            if (GravityPlatingBlock.hasDir(blockState, plateDir)) {
                boolean orthogonal = entityGravityDir.getAxis() != plateDir.getAxis();
                if (!orthogonal) {
                    continue;
                }
                
                Vec3 plateDirVec = Vec3.atLowerCornerOf(plateDir.getNormal());
                
                Vec3 effectCenter = Vec3.atCenterOf(blockPos).add(plateDirVec.scale(0.5));
                Vec3 offset = effectCenter.subtract(entity.position());
                if (offset.dot(Vec3.atLowerCornerOf(entityGravityDir.getNormal())) > 0) {
                    // that plate is lower than entity
                    continue;
                }
                
                Vec3 worldVelocity = GravityChangerAPI.getWorldVelocity(entity);
                if (worldVelocity.dot(plateDirVec) < 0.01) {
                    continue;
                }
                
                double distanceToPlate = Math.abs(entity.position().subtract(effectCenter).dot(plateDirVec));
                if (distanceToPlate < 0.8) {
                    double strengthSqrt = Math.sqrt(comp.getCurrGravityStrength());
                    
                    Vec3 entityGravityVec = Vec3.atLowerCornerOf(entityGravityDir.getNormal());
                    
                    Vec3 deltaWorldVelocity =
                        entityGravityVec.scale(-strengthSqrt * 0.4)
                            .add(plateDirVec.scale(0.08));
                    
                    GravityChangerAPI.setWorldVelocity(
                        entity,
                        GravityChangerAPI.getWorldVelocity(entity).add(deltaWorldVelocity)
                    );
                    return;
                }
            }
        }
        
    }
    
    public InteractionResult interact(Level level, BlockPos pos, Direction plateDir, Player player, InteractionHand hand) {
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        
        refreshCache();
        
        SideData sideDatum = sideData[plateDir.ordinal()];
        
        if (sideDatum == null) {
            return InteractionResult.FAIL;
        }
        
        ItemStack handItem = player.getItemInHand(hand);
        if (handItem.getItem() == Items.AIR) {
            // reducing level
            if (sideDatum.level != 1) {
                sideDatum.level -= 1;
                if (!player.isCreative()) {
                    player.getInventory().add(new ItemStack(Items.AMETHYST_CLUSTER));
                }
            }
            else {
                sideDatum.isAttracting = !sideDatum.isAttracting;
            }
        }
        else if (handItem.getItem() == Items.AMETHYST_CLUSTER) {
            if (!player.isCreative()) {
                handItem.shrink(1);
            }
            
            sideDatum.level += 1;
            
            if (sideDatum.level > MAX_LEVEL) {
                sideDatum.level = MAX_LEVEL;
            }
        }
        else {
            ((ServerPlayer) player).sendSystemMessage(
                Component.translatable("gravity_changer.plate.wrong_interaction"),
                true // on overlay (wrong parchment name)
            );
            return InteractionResult.FAIL;
        }
        
        sync();
        
        boolean isAttracting = sideDatum.isAttracting;
        ((ServerPlayer) player).sendSystemMessage(
            Component.translatable(
                "gravity_changer.plate.status",
                GCUtil.getDirectionText(plateDir.getOpposite()),
                sideDatum.level,
                getForceText(isAttracting)
            ),
            true // on overlay (wrong parchment name)
        );
        
        return InteractionResult.SUCCESS;
    }
    
    public static MutableComponent getForceText(boolean isAttracting) {
        return Component.translatable(
            isAttracting ?
                "gravity_changer.plate.force.attract" : "gravity_changer.plate.force.repulse"
        );
    }
    
    public void sync() {
        Level world = getLevel();
        Validate.notNull(world);
        Validate.isTrue(!world.isClientSide());
        
        setChanged();
        
        // make the packet to be sent from ChunkHolder, so the packet will be redirected by ImmPtl
        // don't directly send update packet
        ((ServerChunkCache) world.getChunkSource()).blockChanged(this.getBlockPos());
    }
    
    public void onPlacing(Direction side, SideData sideData) {
        refreshCache();
        this.sideData[side.ordinal()] = sideData;
        sync();
    }
    
    public List<ItemStack> getDrops() {
        if (sideData == null) {
            return List.of();
        }
        
        List<ItemStack> drops = new ArrayList<>();
        for (Direction value : Direction.values()) {
            SideData sideDatum = sideData[value.ordinal()];
            if (sideDatum != null) {
                ItemStack stack = GravityPlatingItem.createStack(sideDatum);
                drops.add(stack);
            }
        }
        return drops;
    }
}

