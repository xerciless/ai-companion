package com.aicompanion.brain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A* pathfinding over the block grid, the way a player walks:
 * walking, diagonal walking, jumping up one block, dropping down up to three blocks,
 * swimming and climbing ladders/vines.
 */
public final class Pathfinder {
	private static final int[][] DIRS = {
			{1, 0}, {-1, 0}, {0, 1}, {0, -1},
			{1, 1}, {1, -1}, {-1, 1}, {-1, -1}
	};

	private Pathfinder() {
	}

	// ---------------------------------------------------------------- block rules

	public static boolean isDanger(BlockState s) {
		return s.is(Blocks.LAVA) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.CACTUS)
				|| s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.POWDER_SNOW)
				|| s.is(Blocks.WITHER_ROSE) || s.is(BlockTags.CAMPFIRES) || s.is(Blocks.COBWEB);
	}

	public static boolean isClimbable(Level level, BlockPos pos) {
		return level.getBlockState(pos).is(BlockTags.CLIMBABLE);
	}

	public static boolean isWater(Level level, BlockPos pos) {
		return level.getFluidState(pos).is(FluidTags.WATER);
	}

	/** Can a player's body be inside this block? */
	public static boolean passable(Level level, BlockPos pos) {
		if (!level.isLoaded(pos)) {
			return false;
		}
		BlockState s = level.getBlockState(pos);
		if (s.isAir()) {
			return true;
		}
		if (level.getFluidState(pos).is(FluidTags.LAVA) || isDanger(s)) {
			return false;
		}
		if (s.is(BlockTags.CLIMBABLE) || s.is(BlockTags.WOODEN_DOORS)) {
			return true; // ladders can be climbed, wooden doors can be opened
		}
		VoxelShape shape = s.getCollisionShape(level, pos);
		return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.2;
	}

	/** Can a player stand on top of this block? */
	public static boolean solidGround(Level level, BlockPos pos) {
		if (!level.isLoaded(pos)) {
			return false;
		}
		BlockState s = level.getBlockState(pos);
		if (s.isAir() || isDanger(s)) {
			return false;
		}
		if (s.is(BlockTags.FENCES) || s.is(BlockTags.WALLS) || s.is(BlockTags.FENCE_GATES)) {
			return false;
		}
		VoxelShape shape = s.getCollisionShape(level, pos);
		return !shape.isEmpty() && shape.max(Direction.Axis.Y) > 0.2;
	}

	/** Is this a spot where the companion can stand (feet in pos)? */
	public static boolean standable(Level level, BlockPos pos) {
		if (!passable(level, pos) || !passable(level, pos.above())) {
			return false;
		}
		BlockPos below = pos.below();
		return solidGround(level, below)
				|| isClimbable(level, pos) || isClimbable(level, below)
				|| isWater(level, pos) || isWater(level, below);
	}

	// ---------------------------------------------------------------- A*

	private static final class Node implements Comparable<Node> {
		final BlockPos pos;
		Node parent;
		double g;
		double f;
		boolean closed;

		Node(BlockPos pos) {
			this.pos = pos;
		}

		@Override
		public int compareTo(Node o) {
			return Double.compare(this.f, o.f);
		}
	}

	private static double distToGoal(BlockPos p, Vec3 goal) {
		double dx = p.getX() + 0.5 - goal.x;
		double dy = p.getY() - goal.y;
		double dz = p.getZ() + 0.5 - goal.z;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/**
	 * Finds a path from start to (within arriveDist of) goal.
	 * If the goal can't be reached, returns a path to the closest reachable point
	 * (or an empty list if we can't get any closer).
	 */
	public static List<BlockPos> find(Level level, BlockPos start, BlockPos goal, double arriveDist, int maxNodes) {
		Vec3 goalVec = Vec3.atBottomCenterOf(goal);
		Map<Long, Node> nodes = new HashMap<>();
		PriorityQueue<Node> open = new PriorityQueue<>();

		Node startNode = new Node(start.immutable());
		startNode.g = 0;
		startNode.f = distToGoal(start, goalVec);
		nodes.put(start.asLong(), startNode);
		open.add(startNode);

		Node best = startNode;
		double bestH = startNode.f;
		int expanded = 0;

		while (!open.isEmpty() && expanded < maxNodes) {
			Node cur = open.poll();
			if (cur.closed) {
				continue;
			}
			cur.closed = true;
			expanded++;

			double h = distToGoal(cur.pos, goalVec);
			if (h <= arriveDist) {
				return build(cur);
			}
			if (h < bestH) {
				bestH = h;
				best = cur;
			}
			if (cur.pos.distManhattan(start) > 96) {
				continue;
			}

			for (Move m : neighbors(level, cur.pos)) {
				long key = m.pos.asLong();
				Node n = nodes.get(key);
				double g = cur.g + m.cost;
				if (n == null) {
					n = new Node(m.pos);
					nodes.put(key, n);
				} else if (n.closed || g >= n.g) {
					continue;
				}
				n.parent = cur;
				n.g = g;
				n.f = g + distToGoal(m.pos, goalVec) * 1.2;
				open.add(n);
			}
		}

		if (best != startNode && bestH < distToGoal(start, goalVec) - 1.0) {
			return build(best);
		}
		return Collections.emptyList();
	}

	private static List<BlockPos> build(Node end) {
		List<BlockPos> out = new ArrayList<>();
		Node n = end;
		while (n != null && n.parent != null) {
			out.add(n.pos);
			n = n.parent;
		}
		Collections.reverse(out);
		return out;
	}

	private record Move(BlockPos pos, double cost) {
	}

	private static List<Move> neighbors(Level level, BlockPos p) {
		List<Move> out = new ArrayList<>(12);
		boolean inWater = isWater(level, p);

		for (int[] d : DIRS) {
			int dx = d[0];
			int dz = d[1];
			boolean diag = dx != 0 && dz != 0;
			BlockPos side = p.offset(dx, 0, dz);

			if (diag) {
				// no cutting corners
				if (!passable(level, p.offset(dx, 0, 0)) || !passable(level, p.offset(dx, 1, 0))
						|| !passable(level, p.offset(0, 0, dz)) || !passable(level, p.offset(0, 1, dz))) {
					continue;
				}
				if (standable(level, side)) {
					out.add(new Move(side, inWater ? 3.0 : 1.414));
				}
				continue;
			}

			if (standable(level, side)) {
				out.add(new Move(side, inWater || isWater(level, side) ? 2.5 : 1.0));
				continue;
			}

			// jump up one block
			BlockPos up = side.above();
			if (standable(level, up) && passable(level, p.above(2))) {
				out.add(new Move(up, 2.0));
			}

			// drop down (max 3 blocks)
			if (passable(level, side) && passable(level, side.above())) {
				for (int drop = 1; drop <= 3; drop++) {
					BlockPos down = side.below(drop);
					if (standable(level, down)) {
						out.add(new Move(down, 1.0 + drop * 0.6));
						break;
					}
					if (!passable(level, down)) {
						break;
					}
				}
			}
		}

		// climbing ladders / vines, swimming up and down
		boolean climb = isClimbable(level, p);
		if ((climb || inWater) && passable(level, p.above()) && passable(level, p.above(2))) {
			out.add(new Move(p.above(), 1.6));
		}
		BlockPos below = p.below();
		if ((isClimbable(level, below) || (inWater && isWater(level, below))) && passable(level, below)) {
			out.add(new Move(below, 1.3));
		}
		return out;
	}

	/**
	 * Finds a spot to stand on from which the companion can reach (and see) the target block.
	 * Never picks a spot where its own body would be inside the target or standing on top of it.
	 */
	public static BlockPos findStandSpot(Level level, BlockPos target, Vec3 from, double reach) {
		Vec3 center = Vec3.atCenterOf(target);
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		for (int dy = -5; dy <= 1; dy++) {
			for (int dx = -4; dx <= 4; dx++) {
				for (int dz = -4; dz <= 4; dz++) {
					BlockPos c = target.offset(dx, dy, dz);
					if (c.equals(target) || c.above().equals(target) || c.below().equals(target)) {
						continue;
					}
					Vec3 eye = new Vec3(c.getX() + 0.5, c.getY() + 1.62, c.getZ() + 0.5);
					if (eye.distanceTo(center) > reach - 0.25) {
						continue;
					}
					if (!standable(level, c)) {
						continue;
					}
					double score = Vec3.atBottomCenterOf(c).distanceToSqr(from) + Math.abs(dy) * 0.5;
					if (score < bestScore) {
						bestScore = score;
						best = c.immutable();
					}
				}
			}
		}
		return best;
	}
}
