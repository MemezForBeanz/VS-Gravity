package net.memezforbeanz.gravityapi.api;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import net.minecraft.core.BlockPos;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.BinaryHeap;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.Target;

/**
 * Vanilla's A* search, taking targets as given. VS2 rewrites {@link PathFinder#findPath}'s targets
 * from shipyard to world coordinates, which breaks searches run in a ship's own space.
 */
public class GravityPathFinder extends PathFinder
{
	private final Node[] neighbors = new Node[32];
	private final int maxVisitedNodes;
	private final NodeEvaluator nodeEvaluator;
	private final BinaryHeap openSet = new BinaryHeap();

	public GravityPathFinder(NodeEvaluator nodeEvaluator, int maxVisitedNodes)
	{
		super(nodeEvaluator, maxVisitedNodes);
		this.nodeEvaluator = nodeEvaluator;
		this.maxVisitedNodes = maxVisitedNodes;
	}

	@Nullable
	@Override
	public Path findPath(PathNavigationRegion region, Mob mob, Set<BlockPos> targets, float maxRange, int accuracy, float searchDepthMultiplier)
	{
		this.openSet.clear();
		this.nodeEvaluator.prepare(region, mob);
		Node start = this.nodeEvaluator.getStart();
		if(start == null)
		{
			return null;
		}
		Map<Target, BlockPos> goals = targets.stream().collect(Collectors.toMap(
			pos -> this.nodeEvaluator.getGoal(pos.getX(), pos.getY(), pos.getZ()), Function.identity(), (a, b) -> a));
		Path path = this.search(region.getProfiler(), start, goals, maxRange, accuracy, searchDepthMultiplier);
		this.nodeEvaluator.done();
		return path;
	}

	@Nullable
	private Path search(ProfilerFiller profiler, Node start, Map<Target, BlockPos> goals, float maxRange, int accuracy, float searchDepthMultiplier)
	{
		profiler.push("find_path");
		Set<Target> targets = goals.keySet();
		start.g = 0.0F;
		start.h = this.getBestH(start, targets);
		start.f = start.h;
		this.openSet.clear();
		this.openSet.insert(start);
		int visited = 0;
		Set<Target> reached = Sets.newHashSetWithExpectedSize(targets.size());
		int maxVisited = (int)(this.maxVisitedNodes * searchDepthMultiplier);

		while(!this.openSet.isEmpty())
		{
			if(++visited >= maxVisited)
			{
				break;
			}
			Node node = this.openSet.pop();
			node.closed = true;
			for(Target target : targets)
			{
				if(node.distanceManhattan(target) <= accuracy)
				{
					target.setReached();
					reached.add(target);
				}
			}
			if(!reached.isEmpty())
			{
				break;
			}
			if(node.distanceTo(start) < maxRange)
			{
				int count = this.nodeEvaluator.getNeighbors(this.neighbors, node);
				for(int i = 0; i < count; ++i)
				{
					Node neighbor = this.neighbors[i];
					float step = this.distance(node, neighbor);
					neighbor.walkedDistance = node.walkedDistance + step;
					float g = node.g + step + neighbor.costMalus;
					if(neighbor.walkedDistance < maxRange && (!neighbor.inOpenSet() || g < neighbor.g))
					{
						neighbor.cameFrom = node;
						neighbor.g = g;
						neighbor.h = this.getBestH(neighbor, targets) * 1.5F;
						if(neighbor.inOpenSet())
						{
							this.openSet.changeCost(neighbor, neighbor.g + neighbor.h);
						}
						else
						{
							neighbor.f = neighbor.g + neighbor.h;
							this.openSet.insert(neighbor);
						}
					}
				}
			}
		}

		Optional<Path> path = !reached.isEmpty()
			? reached.stream().map(target -> this.reconstructPath(target.getBestNode(), goals.get(target), true))
				.min(Comparator.comparingInt(Path::getNodeCount))
			: targets.stream().map(target -> this.reconstructPath(target.getBestNode(), goals.get(target), false))
				.min(Comparator.comparingDouble(Path::getDistToTarget).thenComparingInt(Path::getNodeCount));
		profiler.pop();
		return path.orElse(null);
	}

	private float getBestH(Node node, Set<Target> targets)
	{
		float best = Float.MAX_VALUE;
		for(Target target : targets)
		{
			float h = node.distanceTo(target);
			target.updateBest(h, node);
			best = Math.min(h, best);
		}
		return best;
	}

	private Path reconstructPath(Node end, BlockPos target, boolean reachesTarget)
	{
		List<Node> nodes = Lists.newArrayList();
		Node node = end;
		nodes.add(0, end);
		while(node.cameFrom != null)
		{
			node = node.cameFrom;
			nodes.add(0, node);
		}
		return new Path(nodes, target, reachesTarget);
	}
}
