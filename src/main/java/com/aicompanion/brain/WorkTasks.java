package com.aicompanion.brain;

import com.aicompanion.entity.CompanionPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Chopping trees and mining, done the way a player does it. */
final class WorkTasks {
	private final CompanionBrain brain;
	private final Map<Long, Integer> blacklist = new HashMap<>();
	private final Set<String> announced = new HashSet<>();

	// ---- wood
	int woodGoal = 32;
	private BlockPos log;
	private BlockPos treeBase;
	private int searchCooldown;
	private int collectTicks;
	private boolean saidNoTrees;

	// ---- mining
	private BlockPos ore;
	private int oreScan;
	private Direction tunnelDir;
	private int tunnelSteps;
	private int sinceTorch;
	private int targetY = 12;
	private TagKey<Block> preferredOre;
	boolean noTunnel;
	private boolean saidNeedPick;

	// ---- shared
	private BlockPos stand;
	private BlockPos standFor;

	WorkTasks(CompanionBrain brain) {
		this.brain = brain;
	}

	void reset() {
		log = null;
		treeBase = null;
		ore = null;
		stand = null;
		standFor = null;
		collectTicks = 0;
		searchCooldown = 0;
		oreScan = 0;
		tunnelDir = null;
		tunnelSteps = 0;
		saidNoTrees = false;
		saidNeedPick = false;
	}

	/** "mine", "mine diamonds", "mine iron"... */
	void configureMine(String what) {
		preferredOre = null;
		targetY = 12;
		if (what == null) {
			return;
		}
		switch (what) {
			case "diamond", "diamonds" -> { preferredOre = BlockTags.DIAMOND_ORES; targetY = -54; }
			case "iron" -> { preferredOre = BlockTags.IRON_ORES; targetY = 16; }
			case "coal" -> { preferredOre = BlockTags.COAL_ORES; targetY = 40; }
			case "gold" -> { preferredOre = BlockTags.GOLD_ORES; targetY = -16; }
			case "copper" -> { preferredOre = BlockTags.COPPER_ORES; targetY = 48; }
			case "redstone" -> { preferredOre = BlockTags.REDSTONE_ORES; targetY = -54; }
			case "lapis" -> { preferredOre = BlockTags.LAPIS_ORES; targetY = 0; }
			case "emerald", "emeralds" -> { preferredOre = BlockTags.EMERALD_ORES; targetY = 100; }
			default -> {
			}
		}
	}

	private CompanionPlayer bot() {
		return brain.body();
	}

	private boolean blacklisted(BlockPos p) {
		Integer until = blacklist.get(p.asLong());
		return until != null && until > brain.ticks;
	}

	private void blacklist(BlockPos p) {
		blacklist.put(p.asLong(), brain.ticks + 2400);
		if (blacklist.size() > 512) {
			blacklist.entrySet().removeIf(e -> e.getValue() <= brain.ticks);
		}
	}

	// =====================================================================  WOOD

	/** Returns true when we have enough wood. */
	boolean woodTick(ServerPlayer owner) {
		CompanionPlayer bot = bot();
		ServerLevel level = bot.level();
		Actions a = brain.actions();

		if (collectTicks > 0) {
			collectTicks--;
			if (brain.collectNearby(6)) {
				return false;
			}
			collectTicks = 0;
		}
		if (a.count(s -> s.is(ItemTags.LOGS)) >= woodGoal) {
			return true;
		}

		if (log == null) {
			if (searchCooldown > 0) {
				searchCooldown--;
				brain.lookAround();
				return false;
			}
			log = findTreeLog(level, bot.blockPosition(), 20);
			searchCooldown = 40;
			stand = null;
			if (log == null) {
				if (!saidNoTrees) {
					brain.say("I can't find any trees around here. Lead me to a forest and I'll chop!");
					saidNoTrees = true;
				}
				brain.followLoosely(owner);
				return false;
			}
			saidNoTrees = false;
			treeBase = log;
		}

		BlockState state = level.getBlockState(log);
		if (!state.is(BlockTags.LOGS)) {
			BlockPos next = nextLog(level, log);
			if (next != null) {
				log = next;
				return false;
			}
			replant(level);
			log = null;
			collectTicks = 80;
			return false;
		}

		if (a.canReach(log, 4.5)) {
			BlockPos obstruction = a.firstObstruction(log);
			if (obstruction != null && a.isBreakable(obstruction) && !Actions.nearLava(level, obstruction)) {
				brain.nav().stop();
				a.breakBlock(obstruction); // leaves or something in the way
				return false;
			}
			brain.nav().stop();
			a.breakBlock(log);
			return false;
		}

		if (stand == null || !log.equals(standFor)) {
			stand = Pathfinder.findStandSpot(level, log, bot.position(), 4.5);
			standFor = log;
		}
		if (stand == null) {
			blacklist(log);
			log = null;
			return false;
		}
		if (brain.nav().moveTo(stand, 0.6, false) == Navigator.Result.FAILED) {
			blacklist(log);
			log = null;
		}
		return false;
	}

	private BlockPos findTreeLog(ServerLevel level, BlockPos center, int radius) {
		List<BlockPos> candidates = new ArrayList<>();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dy = -4; dy <= 10; dy++) {
					m.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					if (!level.isLoaded(m)) {
						continue;
					}
					if (level.getBlockState(m).is(BlockTags.LOGS) && !blacklisted(m)) {
						candidates.add(m.immutable());
					}
				}
			}
		}
		candidates.sort((p, q) -> Double.compare(p.distSqr(center), q.distSqr(center)));
		int checked = 0;
		for (BlockPos c : candidates) {
			if (++checked > 24) {
				break;
			}
			if (isNaturalTree(level, c)) {
				// start at the bottom of the trunk
				BlockPos base = c;
				int guard = 0;
				while (guard++ < 32 && level.getBlockState(base.below()).is(BlockTags.LOGS)) {
					base = base.below();
				}
				return base;
			}
			blacklist(c);
		}
		return null;
	}

	/** A natural tree has natural (non player-placed) leaves at the top. Keeps us from chopping your house. */
	private static boolean isNaturalTree(ServerLevel level, BlockPos logPos) {
		BlockPos top = logPos;
		int guard = 0;
		while (guard++ < 32 && level.getBlockState(top.above()).is(BlockTags.LOGS)) {
			top = top.above();
		}
		for (int dx = -2; dx <= 2; dx++) {
			for (int dy = -1; dy <= 2; dy++) {
				for (int dz = -2; dz <= 2; dz++) {
					BlockState s = level.getBlockState(top.offset(dx, dy, dz));
					if (s.is(BlockTags.LEAVES) && s.hasProperty(LeavesBlock.PERSISTENT) && !s.getValue(LeavesBlock.PERSISTENT)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	private static BlockPos nextLog(ServerLevel level, BlockPos prev) {
		BlockPos up = prev.above();
		if (level.getBlockState(up).is(BlockTags.LOGS)) {
			return up;
		}
		for (int dy = 0; dy <= 1; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dz = -1; dz <= 1; dz++) {
					BlockPos p = prev.offset(dx, dy, dz);
					if (level.getBlockState(p).is(BlockTags.LOGS)) {
						return p;
					}
				}
			}
		}
		return null;
	}

	private void replant(ServerLevel level) {
		if (treeBase == null) {
			return;
		}
		Actions a = brain.actions();
		int slot = a.findSlot(s -> s.is(ItemTags.SAPLINGS));
		if (slot < 0) {
			return;
		}
		ItemStack sapling = bot().getInventory().getItem(slot);
		if (!(sapling.getItem() instanceof BlockItem bi)) {
			return;
		}
		if (level.getBlockState(treeBase).isAir() && level.getBlockState(treeBase.below()).is(BlockTags.DIRT)
				&& a.canReach(treeBase, 4.5)) {
			if (a.placeBlock(treeBase, bi.getBlock(), false) == Actions.PlaceResult.PLACED && brain.ticks % 3 == 0) {
				brain.say("Planted a new tree!");
			}
		}
	}

	// =====================================================================  MINING

	static boolean isOre(BlockState s) {
		return s.is(BlockTags.COAL_ORES) || s.is(BlockTags.IRON_ORES) || s.is(BlockTags.COPPER_ORES)
				|| s.is(BlockTags.GOLD_ORES) || s.is(BlockTags.REDSTONE_ORES) || s.is(BlockTags.LAPIS_ORES)
				|| s.is(BlockTags.DIAMOND_ORES) || s.is(BlockTags.EMERALD_ORES)
				|| s.is(Blocks.NETHER_QUARTZ_ORE) || s.is(Blocks.ANCIENT_DEBRIS);
	}

	/** Returns true when the mining trip is over. */
	boolean mineTick(ServerPlayer owner) {
		CompanionPlayer bot = bot();
		ServerLevel level = bot.level();
		Actions a = brain.actions();

		if (bot.getInventory().getFreeSlot() < 0) {
			brain.say("My pockets are full! Click 'Give Me Items' and I'll hand everything over.");
			return true;
		}

		if (!a.hasPickaxe()) {
			Crafting.tick(brain);
			if (a.hasPickaxe()) {
				return false;
			}
			if (!saidNeedPick) {
				brain.say("I need a pickaxe first! Let me grab some wood and make one.");
				saidNeedPick = true;
			}
			int goal = woodGoal;
			woodGoal = Math.max(3, a.count(s -> s.is(ItemTags.LOGS)) + 3);
			if (a.count(s -> s.is(ItemTags.LOGS)) + a.count(s -> s.is(ItemTags.PLANKS)) / 4 < 3) {
				woodTick(owner);
			}
			woodGoal = goal;
			return false;
		}

		if (collectTicks > 0) {
			collectTicks--;
			if (brain.collectNearby(5)) {
				return false;
			}
			collectTicks = 0;
		}

		if (ore == null && --oreScan <= 0) {
			ore = findOre(level, bot.blockPosition(), noTunnel ? 12 : 8);
			oreScan = 30;
			stand = null;
			if (ore != null) {
				announceOre(level.getBlockState(ore));
			}
		}

		if (ore != null) {
			BlockState s = level.getBlockState(ore);
			if (!isOre(s)) {
				ore = adjacentOre(level, ore); // follow the vein
				collectTicks = 25;
				stand = null;
				return false;
			}
			a.selectBestToolFor(s);
			if (!bot.hasCorrectToolForDrops(s)) {
				String n = s.getBlock().getName().getString();
				if (announced.add("tool:" + n)) {
					brain.say("I found " + n + " but I need a better pickaxe to mine it.");
				}
				blacklist(ore);
				ore = null;
				return false;
			}
			if (a.canReach(ore, 4.5)) {
				BlockPos obstruction = a.firstObstruction(ore);
				brain.nav().stop();
				if (obstruction != null) {
					if (!a.isBreakable(obstruction) || Actions.nearLava(level, obstruction)) {
						blacklist(ore);
						ore = null;
						return false;
					}
					a.breakBlock(obstruction);
					return false;
				}
				a.breakBlock(ore);
				return false;
			}
			if (stand == null || !ore.equals(standFor)) {
				stand = Pathfinder.findStandSpot(level, ore, bot.position(), 4.5);
				standFor = ore;
			}
			if (stand != null) {
				if (brain.nav().moveTo(stand, 0.6, false) != Navigator.Result.FAILED) {
					return false;
				}
				stand = null;
			}
			if (noTunnel || !tunnelToward(ore)) {
				blacklist(ore);
				ore = null;
			}
			return false;
		}

		if (noTunnel) {
			return true; // nothing to mine nearby
		}

		// Nothing in sight: dig. Staircase down to a good height, then a long tunnel.
		if (tunnelDir == null) {
			tunnelDir = bot.getDirection();
			brain.say(bot.getBlockY() > targetY ? "Digging a staircase down to find ores..." : "Starting a mining tunnel!");
		}
		BlockPos feet = bot.blockPosition();
		boolean ok = digStep(feet, tunnelDir, feet.getY() > targetY ? -1 : 0);
		if (!ok) {
			tunnelDir = brain.random.nextBoolean() ? tunnelDir.getClockWise() : tunnelDir.getCounterClockWise();
		}
		if (tunnelSteps > 48) {
			tunnelSteps = 0;
			tunnelDir = tunnelDir.getClockWise();
		}
		return false;
	}

	private BlockPos findOre(ServerLevel level, BlockPos center, int radius) {
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -radius; dy <= radius; dy++) {
				for (int dz = -radius; dz <= radius; dz++) {
					m.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
					if (!level.isLoaded(m)) {
						continue;
					}
					BlockState s = level.getBlockState(m);
					if (!isOre(s) || blacklisted(m) || Actions.nearLava(level, m)) {
						continue;
					}
					double score = m.distSqr(center);
					if (preferredOre != null && !s.is(preferredOre)) {
						score *= 3;
					}
					if (s.is(BlockTags.DIAMOND_ORES)) {
						score *= 0.5;
					}
					if (score < bestScore) {
						bestScore = score;
						best = m.immutable();
					}
				}
			}
		}
		return best;
	}

	private BlockPos adjacentOre(ServerLevel level, BlockPos p) {
		for (int dx = -1; dx <= 1; dx++)
			for (int dy = -1; dy <= 1; dy++)
				for (int dz = -1; dz <= 1; dz++) {
					BlockPos q = p.offset(dx, dy, dz);
					if (isOre(level.getBlockState(q)) && !blacklisted(q)) {
						return q;
					}
				}
		return null;
	}

	private void announceOre(BlockState s) {
		String key;
		String line;
		if (s.is(BlockTags.DIAMOND_ORES)) { key = "diamond"; line = "DIAMONDS!! I see diamonds!"; }
		else if (s.is(BlockTags.EMERALD_ORES)) { key = "emerald"; line = "Whoa, an emerald!"; }
		else if (s.is(BlockTags.GOLD_ORES)) { key = "gold"; line = "Ooh, shiny! Gold ore!"; }
		else if (s.is(BlockTags.IRON_ORES)) { key = "iron"; line = "Found some iron!"; }
		else if (s.is(BlockTags.LAPIS_ORES)) { key = "lapis"; line = "Lapis! Good for enchanting."; }
		else if (s.is(BlockTags.REDSTONE_ORES)) { key = "redstone"; line = "Redstone ore over here!"; }
		else { return; }
		if (announced.add(key + (brain.ticks / 6000))) {
			brain.say(line);
		}
	}

	/** Digs one step of a tunnel toward a target block. */
	private boolean tunnelToward(BlockPos target) {
		BlockPos feet = bot().blockPosition();
		int dx = target.getX() - feet.getX();
		int dy = target.getY() - feet.getY();
		int dz = target.getZ() - feet.getZ();
		if (dx == 0 && dz == 0) {
			if (dy < 0) {
				return digDown(feet);
			}
			Direction d = bot().getDirection();
			return digStep(feet, d, 1);
		}
		Direction dir = Math.abs(dx) >= Math.abs(dz)
				? (dx > 0 ? Direction.EAST : Direction.WEST)
				: (dz > 0 ? Direction.SOUTH : Direction.NORTH);
		int v = dy < -1 ? -1 : (dy > 1 ? 1 : 0);
		return digStep(feet, dir, v);
	}

	private boolean digDown(BlockPos feet) {
		ServerLevel level = bot().level();
		BlockPos below = feet.below();
		if (Actions.nearLava(level, below) || Actions.nearLava(level, below.below())) {
			return false;
		}
		if (!brain.actions().isBreakable(below)) {
			return false;
		}
		brain.nav().stop();
		brain.actions().breakBlock(below);
		return true;
	}

	/**
	 * One step of digging: clears the blocks in front (and above/below for stairs),
	 * then walks forward. vertical: -1 = staircase down, 0 = straight, +1 = staircase up.
	 * Returns false if the way is blocked by lava, bedrock or a drop.
	 */
	private boolean digStep(BlockPos feet, Direction dir, int vertical) {
		CompanionPlayer bot = bot();
		ServerLevel level = bot.level();
		Actions a = brain.actions();
		BlockPos next = feet.relative(dir).above(vertical);

		List<BlockPos> clear = new ArrayList<>(3);
		if (vertical < 0) {
			clear.add(next.above(2));
			clear.add(next.above());
			clear.add(next);
		} else if (vertical > 0) {
			clear.add(feet.above(2));
			clear.add(next.above());
			clear.add(next);
		} else {
			clear.add(next.above());
			clear.add(next);
		}

		for (BlockPos c : clear) {
			if (Pathfinder.passable(level, c)) {
				continue;
			}
			if (Actions.nearLava(level, c) || !a.isBreakable(c)) {
				return false;
			}
			brain.nav().stop();
			a.breakBlock(c);
			return true;
		}

		// need something to stand on
		if (!Pathfinder.solidGround(level, next.below()) && !Pathfinder.isWater(level, next)) {
			if (Actions.nearLava(level, next.below())) {
				return false;
			}
			int slot = a.findSlot(s -> s.getItem() instanceof BlockItem bi
					&& (s.is(Items.COBBLESTONE) || s.is(Items.COBBLED_DEEPSLATE) || s.is(Items.DIRT) || s.is(Items.NETHERRACK)));
			if (slot < 0 || !(bot.getInventory().getItem(slot).getItem() instanceof BlockItem filler)) {
				// a drop of more than 3 blocks? turn instead
				return Pathfinder.solidGround(level, next.below(2)) || Pathfinder.solidGround(level, next.below(3));
			}
			if (a.canReach(next.below(), 4.5)) {
				a.placeBlock(next.below(), filler.getBlock(), false);
			}
			return true;
		}

		// walk into the cleared spot
		brain.nav().walkToward(Vec3.atBottomCenterOf(next), false);
		if (vertical > 0 && bot.onGround()) {
			bot.setJumping(true);
		}
		if (bot.blockPosition().equals(next) || bot.blockPosition().equals(next.above())) {
			tunnelSteps++;
			if (++sinceTorch >= 10) {
				sinceTorch = 0;
				placeTorchBehind(feet, dir);
			}
		}
		return true;
	}

	private void placeTorchBehind(BlockPos feet, Direction dir) {
		Actions a = brain.actions();
		if (a.count(Items.TORCH) == 0) {
			return;
		}
		ServerLevel level = bot().level();
		BlockPos spot = feet.relative(dir.getOpposite());
		if (level.getBlockState(spot).isAir() && Pathfinder.solidGround(level, spot.below())) {
			a.placeBlock(spot, Blocks.TORCH, false);
		}
	}
}
