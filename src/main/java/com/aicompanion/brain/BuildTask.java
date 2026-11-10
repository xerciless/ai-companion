package com.aicompanion.brain;

import com.aicompanion.entity.CompanionPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builds a blueprint block by block, walking around to reach each spot like a player. */
final class BuildTask {
	private static final double REACH = 5.5;
	private static final Set<Block> DECOR = Set.of(Blocks.GLASS_PANE, Blocks.OAK_DOOR, Blocks.TORCH, Blocks.SPRUCE_SLAB,
			Blocks.CHEST, Blocks.CRAFTING_TABLE, Blocks.LADDER, Blocks.OAK_FENCE, Blocks.COBBLESTONE_WALL);

	private final CompanionBrain brain;
	private final ArrayDeque<Blueprints.Step> queue = new ArrayDeque<>();
	private final Map<BlockPos, Integer> tries = new HashMap<>();
	private String kind;
	private int total;
	private int placed;
	private boolean halfwaySaid;
	private BlockPos stand;
	private Blueprints.Step standFor;
	private int stepTicks;

	BuildTask(CompanionBrain brain) {
		this.brain = brain;
	}

	boolean active() {
		return kind != null;
	}

	String kind() {
		return kind;
	}

	int progressPercent() {
		return total == 0 ? 0 : Math.min(100, (total - queue.size()) * 100 / total);
	}

	void cancel() {
		queue.clear();
		tries.clear();
		kind = null;
		stand = null;
		standFor = null;
	}

	void start(String kind, ServerPlayer owner) {
		cancel();
		Direction facing = owner.getDirection();
		BlockPos origin = owner.blockPosition().relative(facing, Blueprints.distanceInFront(kind));
		List<Blueprints.Step> steps = Blueprints.create(kind, origin, facing);
		queue.addAll(steps);
		this.kind = kind;
		this.total = steps.size();
		this.placed = 0;
		this.halfwaySaid = false;
	}

	void tick(ServerPlayer owner) {
		CompanionPlayer bot = brain.body();
		ServerLevel level = bot.level();
		Actions actions = brain.actions();

		if (kind == null) {
			brain.setMode(Mode.FOLLOW);
			return;
		}
		if (queue.isEmpty()) {
			brain.say(brain.pick("Ta-da! Your " + kind + " is finished!", "Done! How do you like the " + kind + "?",
					"All finished! That " + kind + " looks awesome."));
			kind = null;
			brain.setMode(Mode.FOLLOW);
			return;
		}
		if (!halfwaySaid && progressPercent() >= 50) {
			halfwaySaid = true;
			brain.say(brain.pick("Halfway done with the " + kind + "!", "The " + kind + " is coming along nicely!"));
		}

		Blueprints.Step step = queue.peek();
		BlockPos pos = step.pos();
		BlockState current = level.getBlockState(pos);
		boolean wantAir = step.block() == Blocks.AIR;

		// already done?
		if (wantAir) {
			if (current.isAir() || current.canBeReplaced() || !actions.isBreakable(pos)) {
				next(false);
				return;
			}
		} else {
			if (!current.isAir() && current.getBlock().asItem() == step.block().asItem()) {
				next(true);
				return;
			}
			if (step.fillOnly() && !current.canBeReplaced()) {
				next(false);
				return;
			}
		}

		boolean needBreak = wantAir || (!current.isAir() && !current.canBeReplaced());
		if (needBreak && !actions.isBreakable(pos)) {
			next(false);
			return;
		}

		Block material = null;
		if (!needBreak) {
			material = resolveMaterial(step.block());
			if (material == null) {
				if (DECOR.contains(step.block())) {
					next(false); // skip decorations we don't have
					return;
				}
				brain.say("I ran out of building blocks! Give me some, or turn on Free Blocks in my menu.");
				cancel();
				brain.setMode(Mode.FOLLOW);
				return;
			}
		}

		// walk close enough
		if (!actions.canReach(pos, REACH)) {
			if (stand == null || standFor != step) {
				stand = Pathfinder.findStandSpot(level, pos, bot.position(), REACH);
				standFor = step;
			}
			if (stand == null) {
				postpone(step);
				return;
			}
			Navigator.Result r = brain.nav().moveTo(stand, 0.6, false);
			if (r == Navigator.Result.FAILED) {
				stand = null;
				postpone(step);
			}
			return;
		}
		brain.nav().stop();

		if (++stepTicks > 400) { // something's wrong with this block, move on
			postpone(step);
			return;
		}

		if (needBreak) {
			actions.breakBlock(pos);
			return;
		}

		if (bot.getBoundingBox().intersects(new AABB(pos))) {
			brain.nav().walkAwayFrom(Vec3.atCenterOf(pos));
			return;
		}

		Actions.PlaceResult result = actions.placeBlock(pos, material, brain.freeBuild);
		if (result == Actions.PlaceResult.PLACED) {
			next(true);
		} else if (result == Actions.PlaceResult.FAILED) {
			int t = tries.merge(pos, 1, Integer::sum);
			if (t > 4) {
				postpone(step);
			}
		}
	}

	private void next(boolean countPlaced) {
		queue.poll();
		if (countPlaced) {
			placed++;
		}
		stand = null;
		standFor = null;
		stepTicks = 0;
	}

	/** Try this block again later (someone might be standing there); give up after a few tries. */
	private void postpone(Blueprints.Step step) {
		queue.poll();
		int t = tries.merge(step.pos(), 10, Integer::sum);
		if (t < 30) {
			queue.addLast(step);
		}
		stand = null;
		standFor = null;
		stepTicks = 0;
	}

	/** Which block to actually use for a step (the exact one, or a substitute from the inventory). */
	private Block resolveMaterial(Block wanted) {
		if (brain.freeBuild) {
			return wanted;
		}
		Actions a = brain.actions();
		if (a.findSlot(wanted.asItem()) >= 0) {
			return wanted;
		}
		if (DECOR.contains(wanted)) {
			return null;
		}
		Inventory inv = brain.body().getInventory();
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (s.getItem() instanceof BlockItem bi) {
				Block b = bi.getBlock();
				if (!(b instanceof FallingBlock)
						&& b.defaultBlockState().isCollisionShapeFullBlock(brain.body().level(), BlockPos.ZERO)) {
					return b;
				}
			}
		}
		return null;
	}
}
