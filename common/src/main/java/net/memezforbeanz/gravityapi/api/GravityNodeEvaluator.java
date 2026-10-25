package net.memezforbeanz.gravityapi.api;

import javax.annotation.Nullable;

import net.memezforbeanz.gravityapi.util.RotationUtil;

import java.util.EnumSet;

import it.unimi.dsi.fastutil.objects.Object2BooleanMap;
import it.unimi.dsi.fastutil.objects.Object2BooleanOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

public class GravityNodeEvaluator extends WalkNodeEvaluator
{
    /** Most blocks any scan along gravity walks (the region only covers the search range anyway). */
    private static final int MAX_COLUMN_SCAN = 64;

    private Direction gravityDirection = Direction.DOWN;
    private final Direction[] horizontalDirections = new Direction[4];
    private final Object2BooleanMap<AABB> collisionCache = new Object2BooleanOpenHashMap<>();

    /**
     * The space the search runs in: the world, or a ship's shipyard (its blocks are real there).
     * Set by {@link GravityPathNavigation} before each search; without one the mob's world
     * position and cardinal gravity are used.
     */
    @Nullable
    private Direction frameDown;
    @Nullable
    private Vec3 frameFeet;
    private boolean pendingShipyard;
    /** The search runs in a ship's shipyard: VS2's world-position path types must stay out of it. */
    private boolean shipyard;
    private Vec3 mobFeet = Vec3.ZERO;
    private BlockPos mobBlock = BlockPos.ZERO;
    private AABB mobBox = new AABB(0, 0, 0, 0, 0, 0);

    /** Run the next search with gravity {@code down} and the mob's feet at {@code feet}, both in the path's space. */
    public void setFrame(Direction down, Vec3 feet, boolean shipyard)
    {
        this.frameDown = down;
        this.frameFeet = feet;
        this.pendingShipyard = shipyard;
    }
    
    @Override
    public void prepare(PathNavigationRegion p_77620_, Mob p_77621_) 
    {
        this.collisionCache.clear();
        super.prepare(p_77620_, p_77621_);
        if(this.frameDown != null && this.frameFeet != null)
        {
            this.gravityDirection = this.frameDown;
            this.mobFeet = this.frameFeet;
            this.shipyard = this.pendingShipyard;
        }
        else
        {
            this.gravityDirection = GravityChangerAPI.getGravityDirection(p_77621_);
            this.mobFeet = p_77621_.position();
            this.shipyard = false;
        }
        this.pendingShipyard = false;
        this.frameDown = null;
        this.frameFeet = null;
        // A hair up from the feet, so feet resting on a face land in the cell above it
        this.mobBlock = BlockPos.containing(this.mobFeet.subtract(Vec3.atLowerCornerOf(this.gravityDirection.getNormal()).scale(0.01)));
        this.mobBox = RotationUtil.boxPlayerToWorld(this.getBoundingBoxForPose(p_77621_.getPose()), this.gravityDirection).move(this.mobFeet);
        this.updateHorizontalDirections();
    }

    /** Where feet stand in a node's cell: on its face toward gravity, centred on the body's footprint. */
    public Vec3 feetIn(BlockPos node)
    {
        return feetIn(node, this.gravityDirection, this.entityWidth);
    }

    /**
     * Where feet stand for a node: on the cell's face toward {@code down}, centred on the footprint
     * {@link #getBlockPathTypes} checks ({@code width} cells toward the frame's east and south),
     * like vanilla's node position plus half the footprint.
     */
    public static Vec3 feetIn(BlockPos node, Direction down, int width)
    {
        Vec3 east = Vec3.atLowerCornerOf(RotationUtil.dirPlayerToWorld(Direction.EAST, down).getNormal());
        Vec3 south = Vec3.atLowerCornerOf(RotationUtil.dirPlayerToWorld(Direction.SOUTH, down).getNormal());
        double half = (width - 1) / 2.0D;
        return Vec3.atCenterOf(node).add(Vec3.atLowerCornerOf(down.getNormal()).scale(0.5)).add(east.add(south).scale(half));
    }
    
    private void updateHorizontalDirections() 
    {
        this.horizontalDirections[0] = RotationUtil.dirPlayerToWorld(Direction.NORTH, this.gravityDirection);
        this.horizontalDirections[1] = RotationUtil.dirPlayerToWorld(Direction.SOUTH, this.gravityDirection);
        this.horizontalDirections[2] = RotationUtil.dirPlayerToWorld(Direction.WEST, this.gravityDirection);
        this.horizontalDirections[3] = RotationUtil.dirPlayerToWorld(Direction.EAST, this.gravityDirection);
    }
    
    @Override
    public Node getStart() 
    {
    	BlockPos.MutableBlockPos blockpos$mutableblockpos = new BlockPos.MutableBlockPos();
    	BlockPos blockPos = this.mobBlock;
    	BlockState blockstate = this.level.getBlockState(blockpos$mutableblockpos.set(blockPos));
    	if(!this.mob.canStandOnFluid(blockstate.getFluidState())) 
    	{
    		if(this.canFloat() && this.mob.isInWater())
    		{
    			for(int i = 0; i < MAX_COLUMN_SCAN; i++)
    			{
    				if(!blockstate.is(Blocks.WATER) && blockstate.getFluidState() != Fluids.WATER.getSource(false)) 
    				{
    					blockPos = blockPos.relative(this.gravityDirection);
    					break;
    				}
					blockPos = blockPos.relative(this.gravityDirection.getOpposite());
    				blockstate = this.level.getBlockState(blockpos$mutableblockpos.set(blockPos));
    			}
           }
    		else if(this.mob.onGround()) 
    		{
    			blockPos = this.mobBlock;
    		}
    		else 
    		{
    			int i = 0;
    			BlockPos blockpos;
    			// Bounded: in open space there may be no ground along gravity at all
    			for(blockpos = this.mobBlock; (this.level.getBlockState(blockpos).isAir() || this.level.getBlockState(blockpos).isPathfindable(this.level, blockpos, PathComputationType.LAND)) && i < MAX_COLUMN_SCAN; blockpos = blockpos.relative(this.gravityDirection))
    			{
    				i++;
    			}
    			blockPos = blockpos.relative(this.gravityDirection.getOpposite());
    		}
        } 
    	else 
    	{
    		for(int i = 0; i < MAX_COLUMN_SCAN && this.mob.canStandOnFluid(blockstate.getFluidState()); i++)
    		{
				blockPos = blockPos.relative(this.gravityDirection.getOpposite());
    			blockstate = this.level.getBlockState(blockpos$mutableblockpos.set(blockPos));
    		}
			blockPos = blockPos.relative(this.gravityDirection);
        }
        return this.getStartNode(blockPos);
    }
    
    /** Floor height along up (vanilla's world Y under DOWN gravity), so step heights compare the right way round on every face. */
    @Override
    protected double getFloorLevel(BlockPos p_164733_) 
    {
    	if((this.canFloat() || this.isAmphibious()) && this.level.getFluidState(p_164733_).is(FluidTags.WATER))
    	{
    		return this.heightOf(p_164733_.get(this.gravityDirection.getAxis()) + 0.5D);
    	}
    	return this.getFloorLevelForGravity(this.level, p_164733_);
    }
    
    public double getFloorLevelForGravity(BlockGetter p_77612_, BlockPos p_77613_) 
    {
    	BlockPos blockpos = p_77613_.relative(this.gravityDirection);
    	VoxelShape voxelshape = p_77612_.getBlockState(blockpos).getCollisionShape(p_77612_, blockpos);
    	Direction.Axis axis = this.gravityDirection.getAxis();
    	boolean positive = this.gravityDirection.getAxisDirection() == Direction.AxisDirection.POSITIVE;
    	// The face of the block below (along gravity) that the feet rest on
    	double surface = voxelshape.isEmpty()
    		? blockpos.get(axis) + (positive ? 1.0D : 0.0D)
    		: blockpos.get(axis) + (positive ? voxelshape.min(axis) : voxelshape.max(axis));
    	return this.heightOf(surface);
    }

    /** A coordinate on the gravity axis as a height along up. */
    private double heightOf(double coordinate)
    {
    	return this.gravityDirection.getAxisDirection() == Direction.AxisDirection.POSITIVE ? -coordinate : coordinate;
    }
    
    @Override
    public int getNeighbors(Node[] neighbors, Node currentNode) 
    {
        int i = 0;
        int stepHeight = 0;
        
        BlockPos currentNodePos = currentNode.asBlockPos();
        BlockPos upPos = currentNodePos.relative(this.gravityDirection.getOpposite());

        BlockPathTypes pathTypeUp = this.getCachedBlockType(this.mob, upPos.getX(), upPos.getY(), upPos.getZ());
        BlockPathTypes pathTypeCurrent = this.getCachedBlockType(this.mob, currentNodePos.getX(), currentNodePos.getY(), currentNodePos.getZ());

        if(this.mob.getPathfindingMalus(pathTypeUp) >= 0.0F && pathTypeCurrent != BlockPathTypes.STICKY_HONEY) 
        {
            stepHeight = Mth.floor(Math.max(1.0F, net.memezforbeanz.gravityapi.platform.PlatformHooks.stepHeight(this.mob)));
        }

        double floorLevel = this.getFloorLevel(currentNodePos);

        Node southNode = this.findAcceptedNode(currentNodePos.relative(this.horizontalDirections[1]), stepHeight, floorLevel, this.horizontalDirections[1], pathTypeCurrent);
        if(this.isNeighborValid(southNode, currentNode))
        {
        	neighbors[i++] = southNode;
        }

        Node westNode = this.findAcceptedNode(currentNodePos.relative(this.horizontalDirections[2]), stepHeight, floorLevel, this.horizontalDirections[2], pathTypeCurrent);
        if(this.isNeighborValid(westNode, currentNode))
        {
        	neighbors[i++] = westNode;
        }

        Node eastNode = this.findAcceptedNode(currentNodePos.relative(this.horizontalDirections[3]), stepHeight, floorLevel, this.horizontalDirections[3], pathTypeCurrent);
        if(this.isNeighborValid(eastNode, currentNode))
        {
        	neighbors[i++] = eastNode;
        }

        Node northNode = this.findAcceptedNode(currentNodePos.relative(this.horizontalDirections[0]), stepHeight, floorLevel, this.horizontalDirections[0], pathTypeCurrent);
        if(this.isNeighborValid(northNode, currentNode))
        {
        	neighbors[i++] = northNode;
        }

        Node northWestNode = this.findAcceptedNode(currentNodePos.relative(this.horizontalDirections[0]).relative(this.horizontalDirections[2]), stepHeight, floorLevel, this.horizontalDirections[0], pathTypeCurrent);
        if(this.isDiagonalValid(currentNode, westNode, northNode, northWestNode)) 
        {
            neighbors[i++] = northWestNode;
        }

        Node northEastNode = this.findAcceptedNode(currentNodePos.relative(this.horizontalDirections[0]).relative(this.horizontalDirections[3]), stepHeight, floorLevel, this.horizontalDirections[0], pathTypeCurrent);
        if(this.isDiagonalValid(currentNode, eastNode, northNode, northEastNode)) {
            neighbors[i++] = northEastNode;
        }

        Node southWestNode = this.findAcceptedNode(currentNodePos.relative(this.horizontalDirections[1]).relative(this.horizontalDirections[2]), stepHeight, floorLevel, this.horizontalDirections[1], pathTypeCurrent);
        if(this.isDiagonalValid(currentNode, westNode, southNode, southWestNode))
        {
            neighbors[i++] = southWestNode;
        }
        
        Node southEastNode = this.findAcceptedNode(currentNodePos.relative(this.horizontalDirections[1]).relative(this.horizontalDirections[3]), stepHeight, floorLevel, this.horizontalDirections[1], pathTypeCurrent);
        if(this.isDiagonalValid(currentNode, eastNode, southNode, southEastNode)) 
        {
            neighbors[i++] = southEastNode;
        }
        
        return i;
    }
    
    private int getGravityAxisCoord(Node node) 
    {
        return node.asBlockPos().get(this.gravityDirection.getAxis());
    }

    private boolean isLowerOrSame(Node node1, Node node2) 
    {
        int coord1 = this.getGravityAxisCoord(node1);
        int coord2 = this.getGravityAxisCoord(node2);
        
        if(this.gravityDirection.getAxisDirection() == Direction.AxisDirection.POSITIVE) 
        {
            return coord1 >= coord2;
        }
        else 
        {
            return coord1 <= coord2;
        }
    }

    private boolean isLower(Node node1, Node node2)
    {
        int coord1 = this.getGravityAxisCoord(node1);
        int coord2 = this.getGravityAxisCoord(node2);
        
        if(this.gravityDirection.getAxisDirection() == Direction.AxisDirection.POSITIVE) 
        {
            return coord1 > coord2;
        } 
        else 
        {
            return coord1 < coord2;
        }
    }

    @Override
    protected boolean isDiagonalValid(Node p_77630_, @Nullable Node p_77631_, @Nullable Node p_77632_, @Nullable Node p_77633_)
    {
        if(p_77633_ == null || p_77632_ == null || p_77631_ == null)
        {
            return false;
        }
        
        if(p_77633_.closed) 
        {
            return false;
        }
        
        if(this.isLowerOrSame(p_77632_, p_77630_) && this.isLowerOrSame(p_77631_, p_77630_))
        {
            if(p_77631_.type == BlockPathTypes.WALKABLE_DOOR || p_77632_.type == BlockPathTypes.WALKABLE_DOOR || p_77633_.type == BlockPathTypes.WALKABLE_DOOR)
            {
                return false;
            }
            boolean flag = p_77632_.type == BlockPathTypes.FENCE && p_77631_.type == BlockPathTypes.FENCE && (double)this.mob.getBbWidth() < 0.5D;
            return p_77633_.costMalus >= 0.0F && (this.isLower(p_77632_, p_77630_) || p_77632_.costMalus >= 0.0F || flag) && (this.isLower(p_77631_, p_77630_) || p_77631_.costMalus >= 0.0F || flag);
        }
        
        return false;
    }
    
    @Override
    protected Node findAcceptedNode(int p_164726_, int p_164727_, int p_164728_, int p_164729_, double p_164730_, Direction p_164731_, BlockPathTypes p_164732_) 
    {
        Node node = null;
        BlockPos currentPos = new BlockPos(p_164726_, p_164727_, p_164728_);
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos(p_164726_, p_164727_, p_164728_);

        double d0 = this.getFloorLevel(mutablePos);
        if(d0 - p_164730_ > this.getMobJumpHeight())
        {
            return null;
        }

        BlockPathTypes blockpathtypes = this.getCachedBlockType(this.mob, p_164726_, p_164727_, p_164728_);
        float f = this.mob.getPathfindingMalus(blockpathtypes);
        if(f >= 0.0F) 
        {
            node = this.getNodeAndUpdateCostToMax(p_164726_, p_164727_, p_164728_, blockpathtypes, f);
        }

        if(doesBlockHavePartialCollision(p_164732_) && node != null && node.costMalus >= 0.0F && !this.canReachWithoutCollision(node))
        {
            node = null;
        }

        if (blockpathtypes != BlockPathTypes.WALKABLE && (!this.isAmphibious() || blockpathtypes != BlockPathTypes.WATER))
        {
            if((node == null || node.costMalus < 0.0F) && p_164729_ > 0 && (blockpathtypes != BlockPathTypes.FENCE || this.canWalkOverFences()) && blockpathtypes != BlockPathTypes.UNPASSABLE_RAIL && blockpathtypes != BlockPathTypes.TRAPDOOR && blockpathtypes != BlockPathTypes.POWDER_SNOW) 
            {
                BlockPos upPos = currentPos.relative(this.gravityDirection.getOpposite());
                node = this.findAcceptedNode(upPos.getX(), upPos.getY(), upPos.getZ(), p_164729_ - 1, p_164730_, p_164731_, p_164732_);

                if (node != null && (node.type == BlockPathTypes.OPEN || node.type == BlockPathTypes.WALKABLE) && this.mob.getBbWidth() < 1.0F)
                {
                	AABB rawAABB = this.getBoundingBoxForPose(this.mob.getPose()).move(0.0D, 1.0E-6D, 0.0D);
                    AABB aabb = RotationUtil.boxPlayerToWorld(rawAABB, this.gravityDirection).move(this.feetIn(node.asBlockPos()));
                    if(this.hasCollisions(aabb))
                    {
                        node = null;
                    }
                }
            }

            if(!this.isAmphibious() && blockpathtypes == BlockPathTypes.WATER && !this.canFloat()) 
            {
                BlockPos downPos = currentPos.relative(this.gravityDirection);
                if(this.getCachedBlockType(this.mob, downPos.getX(), downPos.getY(), downPos.getZ()) != BlockPathTypes.WATER)
                {
                    return node;
                }
                
                mutablePos.set(currentPos);
                for(int depth = 0; depth < MAX_COLUMN_SCAN; depth++)
                {
                    mutablePos.move(this.gravityDirection);
                    blockpathtypes = this.getCachedBlockType(this.mob, mutablePos.getX(), mutablePos.getY(), mutablePos.getZ());
                    if(blockpathtypes != BlockPathTypes.WATER)
                    {
                        return node;
                    }
                    node = this.getNodeAndUpdateCostToMax(mutablePos.getX(), mutablePos.getY(), mutablePos.getZ(), blockpathtypes, this.mob.getPathfindingMalus(blockpathtypes));
                }
            }

            if(blockpathtypes == BlockPathTypes.OPEN)
            {
                int fallDistance = 0;
                mutablePos.set(currentPos);
                while(blockpathtypes == BlockPathTypes.OPEN)
                {
                    mutablePos.move(this.gravityDirection);
                    if(mutablePos.getY() < this.mob.level().getMinBuildHeight())
                    {
                        return this.getBlockedNode(p_164726_, p_164727_, p_164728_);
                    }
                    
                    if(fallDistance++ >= this.mob.getMaxFallDistance())
                    {
                        return this.getBlockedNode(mutablePos.getX(), mutablePos.getY(), mutablePos.getZ());
                    }

                    blockpathtypes = this.getCachedBlockType(this.mob, mutablePos.getX(), mutablePos.getY(), mutablePos.getZ());
                    f = this.mob.getPathfindingMalus(blockpathtypes);
                    
                    if(blockpathtypes != BlockPathTypes.OPEN && f >= 0.0F) 
                    {
                        node = this.getNodeAndUpdateCostToMax(mutablePos.getX(), mutablePos.getY(), mutablePos.getZ(), blockpathtypes, f);
                        break;
                    }
                    
                    if(f < 0.0F)
                    {
                        return this.getBlockedNode(mutablePos.getX(), mutablePos.getY(), mutablePos.getZ());
                    }
                }
            }

            if(doesBlockHavePartialCollision(blockpathtypes) && node == null)
            {
                node = this.getNode(p_164726_, p_164727_, p_164728_);
                node.closed = true;
                node.type = blockpathtypes;
                node.costMalus = blockpathtypes.getMalus();
            }
        }
        return node;
    }
    
    /** The pose's box in the gravity frame, feet at the origin. */
    protected AABB getBoundingBoxForPose(Pose p_20218_) 
    {
    	EntityDimensions entitydimensions = this.mob.getDimensions(p_20218_);
    	float f = entitydimensions.width / 2.0F;
        return new AABB(-f, 0.0D, -f, f, entitydimensions.height, f);
    }
    
    @Nullable
    protected Node findAcceptedNode(BlockPos pos, int stepHeight, double floorLevel, Direction moveDir, BlockPathTypes originPathType)
    {
        return this.findAcceptedNode(pos.getX(), pos.getY(), pos.getZ(), stepHeight, floorLevel, moveDir, originPathType);
    }
    
    private boolean canReachWithoutCollision(Node p_77625_) 
    {
    	AABB aabb = this.mobBox;
    	Vec3 vec3 = this.feetIn(p_77625_.asBlockPos()).subtract(this.mobFeet);
        int i = Mth.ceil(vec3.length() / aabb.getSize());
        vec3 = vec3.scale((double)(1.0F / (float)i));
        for(int j = 1; j <= i; ++j) 
        {
        	aabb = aabb.move(vec3);
        	if(this.hasCollisions(aabb)) 
        	{
        		return false;
        	}
        }
        return true;
    }
    
    private boolean hasCollisions(AABB p_77635_) 
    {
    	return this.collisionCache.computeIfAbsent(p_77635_, (p_192973_) -> 
    	{
    		return !this.level.noCollision(this.mob, p_77635_);
    	});
    }
    
    private Node getNodeAndUpdateCostToMax(int p_230620_, int p_230621_, int p_230622_, BlockPathTypes p_230623_, float p_230624_) 
    {
        Node node = this.getNode(p_230620_, p_230621_, p_230622_);
        node.type = p_230623_;
        node.costMalus = Math.max(node.costMalus, p_230624_);
        return node;
    }
    
    private Node getBlockedNode(int p_230628_, int p_230629_, int p_230630_)
    {
    	Node node = this.getNode(p_230628_, p_230629_, p_230630_);
    	node.type = BlockPathTypes.BLOCKED;
    	node.costMalus = -1.0F;
    	return node;
    }
    
    private static boolean doesBlockHavePartialCollision(BlockPathTypes p_230626_) 
    {
    	return p_230626_ == BlockPathTypes.FENCE || p_230626_ == BlockPathTypes.DOOR_WOOD_CLOSED || p_230626_ == BlockPathTypes.DOOR_IRON_CLOSED;
    }
    
    /** Vanilla's classification, with the body footprint laid out in the gravity frame. */
    @Override
    public BlockPathTypes getBlockPathType(BlockGetter level, int x, int y, int z, Mob mob)
    {
        EnumSet<BlockPathTypes> types = EnumSet.noneOf(BlockPathTypes.class);
        BlockPathTypes type = this.getBlockPathTypes(level, x, y, z, types, BlockPathTypes.BLOCKED, this.mobBlock);
        if(types.contains(BlockPathTypes.FENCE))
        {
            return BlockPathTypes.FENCE;
        }
        if(types.contains(BlockPathTypes.UNPASSABLE_RAIL))
        {
            return BlockPathTypes.UNPASSABLE_RAIL;
        }
        BlockPathTypes worst = BlockPathTypes.BLOCKED;
        for(BlockPathTypes candidate : types)
        {
            if(mob.getPathfindingMalus(candidate) < 0.0F)
            {
                return candidate;
            }
            if(mob.getPathfindingMalus(candidate) >= mob.getPathfindingMalus(worst))
            {
                worst = candidate;
            }
        }
        return type == BlockPathTypes.OPEN && mob.getPathfindingMalus(worst) == 0.0F && this.entityWidth <= 1 ? BlockPathTypes.OPEN : worst;
    }

    @Override
    public BlockPathTypes getBlockPathTypes(BlockGetter level, int x, int y, int z, EnumSet<BlockPathTypes> output, BlockPathTypes fallback, BlockPos mobPos)
    {
        Direction east = this.horizontalDirections[3];
        Direction south = this.horizontalDirections[1];
        Direction up = this.gravityDirection.getOpposite();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for(int i = 0; i < this.entityWidth; ++i)
        {
            for(int j = 0; j < this.entityHeight; ++j)
            {
                for(int k = 0; k < this.entityDepth; ++k)
                {
                    pos.set(x, y, z).move(east, i).move(up, j).move(south, k);
                    BlockPathTypes type = this.getBlockPathType(level, pos.getX(), pos.getY(), pos.getZ());
                    type = this.evaluateBlockPathType(level, mobPos, type);
                    if(i == 0 && j == 0 && k == 0)
                    {
                        fallback = type;
                    }
                    output.add(type);
                }
            }
        }
        return fallback;
    }

    /** Vanilla's walkable test looks one block down in world Y: look along gravity instead. */
    @Override
    public BlockPathTypes getBlockPathType(BlockGetter level, int x, int y, int z)
    {
        if(this.gravityDirection == Direction.DOWN && !this.shipyard)
        {
            return super.getBlockPathType(level, x, y, z);
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, y, z);
        BlockPathTypes type = this.rawType(level, pos);
        if(type == BlockPathTypes.OPEN)
        {
            BlockPathTypes below = this.rawType(level, pos.move(this.gravityDirection));
            type = below != BlockPathTypes.WALKABLE && below != BlockPathTypes.OPEN && below != BlockPathTypes.WATER && below != BlockPathTypes.LAVA ? BlockPathTypes.WALKABLE : BlockPathTypes.OPEN;
            if(below == BlockPathTypes.DAMAGE_FIRE || below == BlockPathTypes.DAMAGE_OTHER || below == BlockPathTypes.STICKY_HONEY || below == BlockPathTypes.DAMAGE_CAUTIOUS)
            {
                type = below;
            }
            else if(below == BlockPathTypes.POWDER_SNOW)
            {
                type = BlockPathTypes.DANGER_POWDER_SNOW;
            }
        }
        if(type == BlockPathTypes.WALKABLE)
        {
            type = checkNeighbourBlocks(level, pos.set(x, y, z), type);
        }
        return type;
    }

    /** The block's own path type; in a shipyard bypassing VS2, which replaces it with the type at the matching world position. */
    private BlockPathTypes rawType(BlockGetter level, BlockPos pos)
    {
        return this.shipyard ? rawTypeUnhooked(level, pos) : getBlockPathTypeRaw(level, pos);
    }

    /** {@link WalkNodeEvaluator#getBlockPathTypeRaw} (Forge 1.20.1), outside VS2's injection. */
    private static BlockPathTypes rawTypeUnhooked(BlockGetter level, BlockPos pos)
    {
        BlockState state = level.getBlockState(pos);
        BlockPathTypes type = net.memezforbeanz.gravityapi.platform.PlatformHooks.blockPathType(state, level, pos, null);
        if(type != null)
        {
            return type;
        }
        Block block = state.getBlock();
        if(state.isAir())
        {
            return BlockPathTypes.OPEN;
        }
        if(state.is(BlockTags.TRAPDOORS) || state.is(Blocks.LILY_PAD) || state.is(Blocks.BIG_DRIPLEAF))
        {
            return BlockPathTypes.TRAPDOOR;
        }
        if(state.is(Blocks.POWDER_SNOW))
        {
            return BlockPathTypes.POWDER_SNOW;
        }
        if(state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH))
        {
            return BlockPathTypes.DAMAGE_OTHER;
        }
        if(state.is(Blocks.HONEY_BLOCK))
        {
            return BlockPathTypes.STICKY_HONEY;
        }
        if(state.is(Blocks.COCOA))
        {
            return BlockPathTypes.COCOA;
        }
        if(state.is(Blocks.WITHER_ROSE) || state.is(Blocks.POINTED_DRIPSTONE))
        {
            return BlockPathTypes.DAMAGE_CAUTIOUS;
        }
        FluidState fluid = level.getFluidState(pos);
        BlockPathTypes fluidType = net.memezforbeanz.gravityapi.platform.PlatformHooks.fluidPathType(fluid, level, pos, null, false);
        if(fluidType != null)
        {
            return fluidType;
        }
        if(fluid.is(FluidTags.LAVA))
        {
            return BlockPathTypes.LAVA;
        }
        if(isBurningBlock(state))
        {
            return BlockPathTypes.DAMAGE_FIRE;
        }
        if(block instanceof DoorBlock door)
        {
            if(state.getValue(DoorBlock.OPEN))
            {
                return BlockPathTypes.DOOR_OPEN;
            }
            return door.type().canOpenByHand() ? BlockPathTypes.DOOR_WOOD_CLOSED : BlockPathTypes.DOOR_IRON_CLOSED;
        }
        if(block instanceof BaseRailBlock)
        {
            return BlockPathTypes.RAIL;
        }
        if(block instanceof LeavesBlock)
        {
            return BlockPathTypes.LEAVES;
        }
        if(state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS) || (block instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN)))
        {
            return BlockPathTypes.FENCE;
        }
        if(!state.isPathfindable(level, pos, PathComputationType.LAND))
        {
            return BlockPathTypes.BLOCKED;
        }
        BlockPathTypes loggedType = net.memezforbeanz.gravityapi.platform.PlatformHooks.fluidPathType(fluid, level, pos, null, true);
        if(loggedType != null)
        {
            return loggedType;
        }
        return fluid.is(FluidTags.WATER) ? BlockPathTypes.WATER : BlockPathTypes.OPEN;
    }

    private double getMobJumpHeight()
    {
    	return Math.max(1.125D, (double)net.memezforbeanz.gravityapi.platform.PlatformHooks.stepHeight(this.mob));
    }
}
