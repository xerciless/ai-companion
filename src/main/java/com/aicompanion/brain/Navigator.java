package com.aicompanion.brain;

import com.aicompanion.entity.CompanionPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.List;

/**
 * Walks the companion along a path by pressing its "keys" (forward, jump, sprint, sneak),
 * exactly like a player holding W and jumping over blocks.
 */
public final class Navigator {
	public enum Result { MOVING, ARRIVED, FAILED }

	private final CompanionBrain brain;
	private List<BlockPos> path = Collections.emptyList();
	private int index;
	private BlockPos goal;
	private double goalDist;
	private int repathTimer;
	private int stuckTimer;
	private Vec3 lastCheckPos = Vec3.ZERO;
	private int failures;
	private boolean movedThisTick;

	public Navigator(CompanionBrain brain) {
		this.brain = brain;
	}

	private CompanionPlayer bot() {
		return brain.body();
	}

	/** Called at the start of every tick: release all keys. */
	public void beginTick() {
		CompanionPlayer bot = bot();
		bot.zza = 0;
		bot.xxa = 0;
		bot.setJumping(false);
		bot.setSprinting(false);
		bot.setShiftKeyDown(false);
		movedThisTick = false;
	}

	public boolean movedThisTick() {
		return movedThisTick;
	}

	public void stop() {
		path = Collections.emptyList();
		index = 0;
		goal = null;
		failures = 0;
	}

	/**
	 * Move towards target. Call every tick until it returns ARRIVED or FAILED.
	 */
	public Result moveTo(BlockPos target, double arriveDist, boolean sprint) {
		CompanionPlayer bot = bot();
		Vec3 targetVec = Vec3.atBottomCenterOf(target);
		if (bot.position().distanceTo(targetVec) <= arriveDist) {
			stop();
			return Result.ARRIVED;
		}

		boolean goalChanged = goal == null || goal.distSqr(target) > 4 || Math.abs(goalDist - arriveDist) > 0.01;
		if (goalChanged) {
			failures = 0;
		}
		if (goalChanged || path.isEmpty() || index >= path.size() || --repathTimer <= 0) {
			goal = target.immutable();
			goalDist = arriveDist;
			path = Pathfinder.find(bot.level(), bot.blockPosition(), target, Math.max(arriveDist, 0.5), 4000);
			index = 0;
			repathTimer = 80;
			stuckTimer = 0;
			lastCheckPos = bot.position();
			if (path.isEmpty()) {
				failures++;
				if (failures >= 3) {
					return Result.FAILED;
				}
				// No path: try walking straight at it (works for short gaps and open ground)
				walkToward(targetVec, sprint);
				return Result.MOVING;
			}
		}

		followPath(sprint);

		if (++stuckTimer >= 30) {
			if (bot.position().distanceTo(lastCheckPos) < 0.6) {
				failures++;
				path = Collections.emptyList();
				if (failures >= 4) {
					return Result.FAILED;
				}
			}
			stuckTimer = 0;
			lastCheckPos = bot.position();
		}
		return Result.MOVING;
	}

	private void followPath(boolean sprint) {
		CompanionPlayer bot = bot();
		// skip nodes we already reached
		while (index < path.size()) {
			BlockPos n = path.get(index);
			double dx = n.getX() + 0.5 - bot.getX();
			double dz = n.getZ() + 0.5 - bot.getZ();
			double dy = n.getY() - bot.getY();
			if (dx * dx + dz * dz < 0.12 && dy > -1.2 && dy < 0.65) {
				index++;
			} else {
				break;
			}
		}
		if (index >= path.size()) {
			return;
		}

		BlockPos node = path.get(index);
		// open wooden doors that are in the way, like a player would
		brain.actions().openDoorIfClosed(node);
		brain.actions().openDoorIfClosed(node.above());
		double dx = node.getX() + 0.5 - bot.getX();
		double dz = node.getZ() + 0.5 - bot.getZ();
		double dy = node.getY() - bot.getY();
		double horiz = Math.sqrt(dx * dx + dz * dz);
		boolean onClimbable = bot.onClimbable();

		if (horiz > 0.12) {
			brain.actions().faceYaw(yawTo(dx, dz));
			bot.zza = 1.0F;
			movedThisTick = true;
		}

		if (dy > 0.5) {
			if (onClimbable || bot.isInWater() || horiz < 1.8) {
				bot.setJumping(true);
				movedThisTick = true;
			}
		}
		if (bot.horizontalCollision && (bot.onGround() || bot.isInWater())) {
			bot.setJumping(true);
		}
		if ((bot.isInWater() || bot.isInLava()) && dy > -0.5) {
			bot.setJumping(true); // swim up / stay afloat
		}

		int remaining = path.size() - index;
		if (sprint && remaining > 3 && horiz > 0.5 && !bot.isInWater() && bot.getFoodData().getFoodLevel() > 6) {
			bot.setSprinting(true);
		}
	}

	/** Walk in a straight line towards a point (no pathfinding). */
	public void walkToward(Vec3 target, boolean sprint) {
		CompanionPlayer bot = bot();
		double dx = target.x - bot.getX();
		double dz = target.z - bot.getZ();
		if (dx * dx + dz * dz > 0.04) {
			brain.actions().faceYaw(yawTo(dx, dz));
			bot.zza = 1.0F;
			movedThisTick = true;
		}
		if (bot.horizontalCollision && bot.onGround()) {
			bot.setJumping(true);
		}
		if (bot.isInWater()) {
			bot.setJumping(true);
		}
		if (sprint && !bot.isInWater()) {
			bot.setSprinting(true);
		}
	}

	/** Walk directly away from a point. */
	public void walkAwayFrom(Vec3 from) {
		CompanionPlayer bot = bot();
		Vec3 away = bot.position().subtract(from);
		walkToward(bot.position().add(away.normalize().scale(4)), true);
	}

	public static float yawTo(double dx, double dz) {
		return (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
	}
}
