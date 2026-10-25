package net.memezforbeanz.gravityapi.api;

import java.util.HashSet;
import java.util.Set;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableSet;

import net.memezforbeanz.gravityapi.compat.vs2.VS2Helper;
import net.memezforbeanz.gravityapi.compat.vs2.VS2Integration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.phys.Vec3;

/**
 * Ground navigation under any gravity. Paths are searched and followed in the mob's own space:
 * the world under cardinal gravity, or the shipyard of the ship whose frame its gravity is locked
 * to (a planetoid's blocks only exist there), with the ship-local face as down. Only the waypoint
 * handed to the move control is turned back into world space, each tick, so a turning ship drags
 * the path along with it.
 */
public class GravityPathNavigation extends GroundPathNavigation
{
	/** How far createPath searches along gravity for the ground under (or the air over) a target. */
	private static final int TARGET_SCAN_LIMIT = 32;

	/** The space of the current path (and of {@link #localTargetPos}). */
	@Nullable
	private Space pathSpace;
	@Nullable
	private BlockPos localTargetPos;
	private int localReachRange;
	private float searchDepthMultiplier = 1.0F;
	/** PathNavigation keeps its finder private. */
	private GravityPathFinder gravityPathFinder;

	public GravityPathNavigation(Mob p_26424_, Level p_26425_)
	{
		super(p_26424_, p_26425_);
	}

	/** The world, or one ship's shipyard; {@code down} is gravity's face in it. */
	private record Space(@Nullable Object ship, @Nullable VS2Helper.ShipFrame frame, Direction down)
	{
		Vec3 toLocal(Vec3 world)
		{
			Vec3 local = this.frame != null ? this.frame.toShip(world) : world;
			return local != null ? local : world;
		}

		Vec3 toWorld(Vec3 local)
		{
			Vec3 world = this.frame != null ? this.frame.toWorld(local) : local;
			return world != null ? world : local;
		}

		Vec3 downVector()
		{
			return Vec3.atLowerCornerOf(this.down.getNormal());
		}

		boolean sameAs(@Nullable Space other)
		{
			return other != null && other.ship == this.ship && other.down == this.down;
		}
	}

	private Space currentSpace()
	{
		GravityDirection gravity = GravityChangerAPI.getGravityDirectionVec(this.mob);
		if(VS2Integration.isVS2Loaded() && (gravity.isShipAnchored() || !gravity.isCardinal()))
		{
			Object ship = gravity.isShipAnchored() ? gravity.getAnchorShip() : VS2Helper.getShipEntityIsOn(this.mob);
			VS2Helper.ShipFrame frame = ship != null ? VS2Helper.frameOf(ship) : null;
			if(frame != null)
			{
				Vec3 local = gravity.isShipAnchored() ? gravity.getShipLocalVector() : frame.directionToShip(gravity.getVector());
				if(local != null && local.lengthSqr() > 1.0E-8)
				{
					return new Space(ship, frame, Direction.getNearest(local.x, local.y, local.z));
				}
			}
		}
		return new Space(null, null, gravity.getNearestDirection());
	}

	@Override
	protected PathFinder createPathFinder(int p_26428_)
	{
		this.nodeEvaluator = new GravityNodeEvaluator();
		this.nodeEvaluator.setCanPassDoors(true);
		// Runs in the super constructor: the field has no initializer, so this assignment stays
		this.gravityPathFinder = new GravityPathFinder(this.nodeEvaluator, p_26428_);
		return this.gravityPathFinder;
	}

	@Override
	public void setMaxVisitedNodesMultiplier(float multiplier)
	{
		super.setMaxVisitedNodesMultiplier(multiplier);
		this.searchDepthMultiplier = multiplier;
	}

	@Override
	public void resetMaxVisitedNodesMultiplier()
	{
		super.resetMaxVisitedNodesMultiplier();
		this.searchDepthMultiplier = 1.0F;
	}

	// ------------------------------------------------------------------
	// Positions in the path's space
	// ------------------------------------------------------------------

	/** The mob's feet in {@code space} (lifted to the surface when floating). */
	private Vec3 mobFeet(Space space)
	{
		Vec3 feet = space.toLocal(this.mob.position());
		if(this.mob.isInWater() && this.canFloat())
		{
			Direction up = space.down.getOpposite();
			BlockPos pos = BlockPos.containing(feet);
			for(int i = 0; i < 16 && this.level.getBlockState(pos).is(Blocks.WATER); i++)
			{
				pos = pos.relative(up);
			}
			return Vec3.atCenterOf(pos);
		}
		return feet;
	}

	/** Where the feet stand for a node: as the evaluator laid out the body's footprint. */
	private Vec3 feetIn(Space space, BlockPos node)
	{
		return GravityNodeEvaluator.feetIn(node, space.down, Mth.floor(this.mob.getBbWidth() + 1.0F));
	}

	/** The cell of a world position in {@code space}, a hair up from the feet. */
	private static BlockPos cellOf(Space space, Vec3 worldFeet)
	{
		return BlockPos.containing(space.toLocal(worldFeet).subtract(space.downVector().scale(0.01)));
	}

	/** A block position given in world (or some ship's shipyard) coordinates, in {@code space}. */
	private BlockPos toLocalBlock(Space space, BlockPos pos)
	{
		Vec3 center = Vec3.atCenterOf(pos);
		if(VS2Integration.isVS2Loaded() && VS2Helper.isBlockOnShip(this.level, pos))
		{
			if(space.ship != null && VS2Helper.getShipAtBlock(this.level, pos) == space.ship)
			{
				return pos;
			}
			Vec3 world = VS2Helper.shipToWorldPosition(this.level, pos, center);
			if(world == null)
			{
				return pos;
			}
			center = world;
		}
		return BlockPos.containing(space.toLocal(center));
	}

	@Override
	protected Vec3 getTempMobPos()
	{
		return this.mobFeet(this.pathSpace != null ? this.pathSpace : this.currentSpace());
	}

	// ------------------------------------------------------------------
	// Searching
	// ------------------------------------------------------------------

	/**
	 * Vanilla's target snapping (drop an air target to the ground, lift a solid one into the air)
	 * along gravity, in the path's space. The scans are bounded and stop at unloaded chunks:
	 * unbounded they walked up to build height through empty space every repath, loading chunks
	 * on the way.
	 */
	@SuppressWarnings("deprecation")
	@Override
	public Path createPath(BlockPos target, int accuracy)
	{
		Space space = this.currentSpace();
		BlockPos local = this.toLocalBlock(space, target);
		Direction direction = space.down;
		if(!this.level.hasChunkAt(local))
		{
			return this.findPath(space, ImmutableSet.of(local), 8, false, accuracy, this.followRange());
		}
		if(this.level.getBlockState(local).isAir())
		{
			BlockPos pos = local.relative(direction);
			for(int i = 0; i < TARGET_SCAN_LIMIT && this.level.hasChunkAt(pos); i++, pos = pos.relative(direction))
			{
				if(!this.level.getBlockState(pos).isAir())
				{
					return this.findPath(space, ImmutableSet.of(pos.relative(direction.getOpposite())), 8, false, accuracy, this.followRange());
				}
			}
			return this.findPath(space, ImmutableSet.of(local), 8, false, accuracy, this.followRange());
		}
		if(!this.level.getBlockState(local).isSolid())
		{
			return this.findPath(space, ImmutableSet.of(local), 8, false, accuracy, this.followRange());
		}
		BlockPos pos = local.relative(direction.getOpposite());
		for(int i = 0; i < TARGET_SCAN_LIMIT && this.level.hasChunkAt(pos) && this.level.getBlockState(pos).isSolid(); i++)
		{
			pos = pos.relative(direction.getOpposite());
		}
		return this.findPath(space, ImmutableSet.of(pos), 8, false, accuracy, this.followRange());
	}

	/** Chasing an entity: its cell from its own feet, which may be on another face than ours. */
	@Override
	public Path createPath(Entity entity, int accuracy)
	{
		Space space = this.currentSpace();
		Vec3 entityUp = GravityChangerAPI.getGravityDirectionVec(entity).getVector().scale(-1);
		BlockPos cell = BlockPos.containing(space.toLocal(entity.position().add(entityUp.scale(0.1))));
		return this.findPath(space, ImmutableSet.of(cell), 16, true, accuracy, this.followRange());
	}

	@Override
	protected Path createPath(Set<BlockPos> targets, int regionOffset, boolean offsetUpward, int accuracy, float followRange)
	{
		Space space = this.currentSpace();
		Set<BlockPos> local = new HashSet<>();
		for(BlockPos target : targets)
		{
			local.add(this.toLocalBlock(space, target));
		}
		return this.findPath(space, local, regionOffset, offsetUpward, accuracy, followRange);
	}

	private float followRange()
	{
		return (float)this.mob.getAttributeValue(Attributes.FOLLOW_RANGE);
	}

	/** PathNavigation's search, with targets and region in {@code space}. */
	@Nullable
	private Path findPath(Space space, Set<BlockPos> targets, int regionOffset, boolean offsetUpward, int accuracy, float followRange)
	{
		if(targets.isEmpty() || !this.canUpdatePath())
		{
			return null;
		}
		if(this.path != null && !this.path.isDone() && space.sameAs(this.pathSpace) && targets.contains(this.localTargetPos))
		{
			return this.path;
		}
		this.level.getProfiler().push("pathfind");
		Vec3 feet = this.mobFeet(space);
		BlockPos center = cellOf(space, space.toWorld(feet));
		if(offsetUpward)
		{
			center = center.relative(space.down.getOpposite());
		}
		int range = (int)(followRange + regionOffset);
		PathNavigationRegion region = new PathNavigationRegion(this.level, center.offset(-range, -range, -range), center.offset(range, range, range));
		((GravityNodeEvaluator)this.nodeEvaluator).setFrame(space.down, feet, space.ship != null);
		Path path = this.gravityPathFinder.findPath(region, this.mob, targets, followRange, accuracy, this.searchDepthMultiplier);
		this.level.getProfiler().pop();
		if(path != null && path.getTarget() != null)
		{
			this.pathSpace = space;
			this.localTargetPos = path.getTarget();
			this.localReachRange = accuracy;
			this.timeoutCachedNode = Vec3i.ZERO;
			this.timeoutTimer = 0L;
			this.timeoutLimit = 0.0D;
		}
		return path;
	}

	@Override
	public void recomputePath()
	{
		if(this.level.getGameTime() - this.timeLastRecompute > 20L)
		{
			if(this.localTargetPos != null && this.pathSpace != null)
			{
				Space space = this.currentSpace();
				BlockPos target = space.sameAs(this.pathSpace) ? this.localTargetPos
					: cellOf(space, this.pathSpace.toWorld(Vec3.atCenterOf(this.localTargetPos)));
				this.path = null;
				this.path = this.findPath(space, ImmutableSet.of(target), 8, false, this.localReachRange, this.followRange());
				this.timeLastRecompute = this.level.getGameTime();
				this.hasDelayedRecomputation = false;
			}
		}
		else
		{
			this.hasDelayedRecomputation = true;
		}
	}

	@Nullable
	@Override
	public BlockPos getTargetPos()
	{
		if(this.localTargetPos == null || this.pathSpace == null)
		{
			return null;
		}
		return BlockPos.containing(this.pathSpace.toWorld(Vec3.atCenterOf(this.localTargetPos)));
	}

	// ------------------------------------------------------------------
	// Following
	// ------------------------------------------------------------------

	@Override
	public void tick()
	{
		++this.tick;
		if(this.hasDelayedRecomputation)
		{
			this.recomputePath();
		}
		if(this.isDone())
		{
			return;
		}
		Space space = this.currentSpace();
		if(!space.sameAs(this.pathSpace))
		{
			// Gravity moved to another face or ship: the nodes no longer mean anything
			this.stop();
			return;
		}
		// Fresh transform: the ship has moved since the path was made
		this.pathSpace = space;
		Vec3 feet = this.mobFeet(space);
		if(this.canUpdatePath())
		{
			this.followThePath(space, feet);
		}
		else if(!this.path.isDone())
		{
			// Airborne over the next node's column: count it as reached
			BlockPos next = this.path.getNextNodePos();
			Vec3 offset = feet.subtract(feetIn(space, next));
			Vec3 up = space.downVector().scale(-1);
			if(offset.dot(up) > 0 && !this.mob.onGround() && offset.subtract(up.scale(offset.dot(up))).lengthSqr() < 0.25)
			{
				this.path.advance();
			}
		}
		if(!this.isDone())
		{
			Vec3 wanted = space.toWorld(feetIn(space, this.path.getNextNodePos()));
			this.mob.getMoveControl().setWantedPosition(wanted.x, wanted.y, wanted.z, this.speedModifier);
		}
	}

	@Override
	protected void followThePath()
	{
		Space space = this.pathSpace != null ? this.pathSpace : this.currentSpace();
		this.followThePath(space, this.mobFeet(space));
	}

	/** Vanilla's waypoint advance, with height and the horizontal plane taken along gravity. */
	private void followThePath(Space space, Vec3 feet)
	{
		float width = this.mob.getBbWidth();
		this.maxDistanceToWaypoint = width > 0.75F ? width / 2.0F : 0.75F - width / 2.0F;
		Vec3 offset = feet.subtract(feetIn(space, this.path.getNextNodePos()));
		Vec3 up = space.downVector().scale(-1);
		double height = offset.dot(up);
		Vec3 horizontal = offset.subtract(up.scale(height));
		boolean reached = Math.abs(horizontal.x) <= this.maxDistanceToWaypoint && Math.abs(horizontal.y) <= this.maxDistanceToWaypoint
			&& Math.abs(horizontal.z) <= this.maxDistanceToWaypoint && Math.abs(height) < 1.0D;
		if(reached || this.canCutCorner(this.path.getNextNode().type) && this.shouldTargetNextNode(space, feet))
		{
			this.path.advance();
		}
		this.doStuckDetection(feet);
	}

	private boolean shouldTargetNextNode(Space space, Vec3 feet)
	{
		if(this.path.getNextNodeIndex() + 1 >= this.path.getNodeCount())
		{
			return false;
		}
		Vec3 next = feetIn(space, this.path.getNextNodePos());
		if(!feet.closerThan(next, 2.0D))
		{
			return false;
		}
		Vec3 after = feetIn(space, this.path.getNodePos(this.path.getNextNodeIndex() + 1));
		Vec3 toNext = next.subtract(feet);
		Vec3 toAfter = after.subtract(feet);
		double nextDistance = toNext.lengthSqr();
		if(toAfter.lengthSqr() >= nextDistance && nextDistance >= 0.5D)
		{
			return false;
		}
		return toAfter.normalize().dot(toNext.normalize()) < 0.0D;
	}

	/** Cauldron lifts and sun avoidance work in world Y: only under plain world gravity. */
	@Override
	protected void trimPath()
	{
		if(this.pathSpace == null || (this.pathSpace.ship == null && this.pathSpace.down == Direction.DOWN))
		{
			super.trimPath();
		}
	}

	@Override
	public boolean shouldRecomputePath(BlockPos pos)
	{
		if(this.hasDelayedRecomputation || this.path == null || this.path.isDone() || this.path.getNodeCount() == 0 || this.pathSpace == null)
		{
			return false;
		}
		Vec3 end = Vec3.atCenterOf(this.path.getEndNode().asBlockPos());
		Vec3 middle = end.add(this.mobFeet(this.pathSpace)).scale(0.5);
		Vec3 changed = Vec3.atCenterOf(this.toLocalBlock(this.pathSpace, pos));
		double reach = this.path.getNodeCount() - this.path.getNextNodeIndex();
		return changed.distanceToSqr(middle) < reach * reach;
	}

	@Override
	public boolean isStableDestination(BlockPos pos)
	{
		Space space = this.currentSpace();
		BlockPos below = this.toLocalBlock(space, pos).relative(space.down);
		BlockState state = this.level.getBlockState(below);
		return state.isSolidRender(this.level, below);
	}
}
