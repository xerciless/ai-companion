package com.aicompanion.brain;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * Things the companion knows how to build. Each blueprint is a list of steps
 * (in build order: clear the space, then bottom to top). Coordinates are local:
 * x = to the right of you, y = up, z = forward (away from you).
 */
public final class Blueprints {
	public static final List<String> KINDS = List.of("house", "tower", "wall", "bridge", "shelter");

	/** @param block Blocks.AIR means "clear this spot"; fillOnly means "only place if the spot is empty". */
	public record Step(BlockPos pos, Block block, boolean fillOnly) {
	}

	private Blueprints() {
	}

	public static String normalize(String kind) {
		if (kind == null) {
			return null;
		}
		return switch (kind.toLowerCase()) {
			case "house", "home", "base", "cabin", "cottage" -> "house";
			case "tower", "castle", "lookout" -> "tower";
			case "wall", "fence", "fort" -> "wall";
			case "bridge", "path", "walkway" -> "bridge";
			case "shelter", "hut", "bunker", "hideout" -> "shelter";
			default -> null;
		};
	}

	/** How far in front of the player the structure starts. */
	public static int distanceInFront(String kind) {
		return switch (kind) {
			case "shelter" -> 0;
			case "bridge" -> 0;
			default -> 2;
		};
	}

	public static List<Step> create(String kind, BlockPos origin, Direction facing) {
		Builder b = new Builder(origin, facing);
		switch (kind) {
			case "house" -> house(b);
			case "tower" -> tower(b);
			case "wall" -> wall(b);
			case "bridge" -> bridge(b);
			case "shelter" -> shelter(b);
			default -> {
			}
		}
		return b.steps;
	}

	private static final class Builder {
		final BlockPos origin;
		final Direction forward;
		final Direction right;
		final List<Step> steps = new ArrayList<>();

		Builder(BlockPos origin, Direction facing) {
			this.origin = origin;
			this.forward = facing;
			this.right = facing.getClockWise();
		}

		BlockPos at(int x, int y, int z) {
			return origin.offset(
					right.getStepX() * x + forward.getStepX() * z,
					y,
					right.getStepZ() * x + forward.getStepZ() * z);
		}

		void put(int x, int y, int z, Block block) {
			steps.add(new Step(at(x, y, z), block, false));
		}

		void fill(int x, int y, int z, Block block) {
			steps.add(new Step(at(x, y, z), block, true));
		}

		void clear(int x, int y, int z) {
			steps.add(new Step(at(x, y, z), Blocks.AIR, false));
		}
	}

	/** 7x7 wooden house with log corners, windows, a door, a stepped roof, torches, a table and a chest. */
	private static void house(Builder b) {
		// clear the inside
		for (int y = 1; y <= 5; y++)
			for (int x = -2; x <= 2; x++)
				for (int z = 1; z <= 5; z++)
					b.clear(x, y, z);
		// floor
		for (int x = -3; x <= 3; x++)
			for (int z = 0; z <= 6; z++)
				b.put(x, 0, z, Blocks.OAK_PLANKS);
		// walls, one layer at a time
		for (int y = 1; y <= 4; y++) {
			for (int x = -3; x <= 3; x++) {
				for (int z = 0; z <= 6; z++) {
					boolean edgeX = x == -3 || x == 3;
					boolean edgeZ = z == 0 || z == 6;
					if (!edgeX && !edgeZ) {
						continue;
					}
					if (x == 0 && z == 0 && (y == 1 || y == 2)) {
						b.clear(x, y, z); // doorway
						continue;
					}
					boolean window = (y == 2 || y == 3) && ((edgeX && z >= 2 && z <= 4) || (z == 6 && x >= -1 && x <= 1));
					if (window) {
						continue; // glass goes in later
					}
					b.put(x, y, z, edgeX && edgeZ ? Blocks.OAK_LOG : Blocks.OAK_PLANKS);
				}
			}
		}
		// roof (flat layer + a smaller slab layer on top)
		for (int x = -3; x <= 3; x++)
			for (int z = 0; z <= 6; z++)
				b.put(x, 5, z, Blocks.SPRUCE_PLANKS);
		for (int x = -2; x <= 2; x++)
			for (int z = 1; z <= 5; z++)
				b.put(x, 6, z, Blocks.SPRUCE_SLAB);
		// windows
		for (int y = 2; y <= 3; y++) {
			for (int z = 2; z <= 4; z++) {
				b.put(-3, y, z, Blocks.GLASS_PANE);
				b.put(3, y, z, Blocks.GLASS_PANE);
			}
			for (int x = -1; x <= 1; x++) {
				b.put(x, y, 6, Blocks.GLASS_PANE);
			}
		}
		// furniture & lights
		b.put(2, 1, 5, Blocks.CRAFTING_TABLE);
		b.put(-2, 1, 5, Blocks.CHEST);
		b.put(-2, 1, 1, Blocks.TORCH);
		b.put(2, 1, 1, Blocks.TORCH);
		b.put(0, 1, 4, Blocks.TORCH);
		// door last
		b.put(0, 1, 0, Blocks.OAK_DOOR);
	}

	/** 5x5 stone brick tower, 7 high, with a ladder inside and a lookout roof. */
	private static void tower(Builder b) {
		for (int y = 1; y <= 7; y++)
			for (int x = -1; x <= 1; x++)
				for (int z = 1; z <= 3; z++)
					b.clear(x, y, z);
		for (int x = -2; x <= 2; x++)
			for (int z = 0; z <= 4; z++)
				b.put(x, 0, z, Blocks.COBBLESTONE);
		for (int y = 1; y <= 6; y++) {
			for (int x = -2; x <= 2; x++) {
				for (int z = 0; z <= 4; z++) {
					boolean edge = x == -2 || x == 2 || z == 0 || z == 4;
					if (!edge) {
						continue;
					}
					if (x == 0 && z == 0 && y <= 2) {
						b.clear(x, y, z); // doorway
						continue;
					}
					boolean window = y == 4 && x == 0 && z == 4 || y == 4 && z == 2 && (x == -2 || x == 2);
					if (window) {
						b.clear(x, y, z);
						continue;
					}
					boolean corner = (x == -2 || x == 2) && (z == 0 || z == 4);
					b.put(x, y, z, corner ? Blocks.COBBLESTONE : Blocks.STONE_BRICKS);
				}
			}
		}
		// ladder up the back wall
		for (int y = 1; y <= 6; y++) {
			b.put(0, y, 3, Blocks.LADDER);
		}
		// roof with a hatch over the ladder
		for (int x = -2; x <= 2; x++) {
			for (int z = 0; z <= 4; z++) {
				if (x == 0 && z == 3) {
					continue;
				}
				b.put(x, 7, z, Blocks.STONE_BRICKS);
			}
		}
		b.put(-1, 1, 1, Blocks.TORCH);
		b.put(1, 1, 1, Blocks.TORCH);
	}

	/** 11 wide cobblestone wall with battlements. */
	private static void wall(Builder b) {
		for (int x = -5; x <= 5; x++) {
			for (int y = 0; y <= 2; y++) {
				b.put(x, y, 0, Blocks.COBBLESTONE);
			}
		}
		for (int x = -5; x <= 5; x += 2) {
			b.put(x, 3, 0, Blocks.COBBLESTONE_WALL);
		}
	}

	/** 3 wide, 16 long plank bridge at your feet level, with fences along the sides. */
	private static void bridge(Builder b) {
		for (int z = 1; z <= 16; z++) {
			for (int x = -1; x <= 1; x++) {
				b.fill(x, -1, z, Blocks.OAK_PLANKS);
			}
			b.fill(-2, -1, z, Blocks.OAK_PLANKS);
			b.fill(2, -1, z, Blocks.OAK_PLANKS);
			if (z % 2 == 0) {
				b.fill(-2, 0, z, Blocks.OAK_FENCE);
				b.fill(2, 0, z, Blocks.OAK_FENCE);
			}
		}
	}

	/** Quick cobblestone hut built around you, for surviving the night. */
	private static void shelter(Builder b) {
		for (int y = 0; y <= 2; y++)
			for (int x = -1; x <= 1; x++)
				for (int z = -1; z <= 1; z++)
					if (!(x == 0 && z == 0)) {
						b.clear(x, y, z);
					}
		for (int y = 0; y <= 2; y++) {
			for (int x = -2; x <= 2; x++) {
				for (int z = -2; z <= 2; z++) {
					boolean edge = x == -2 || x == 2 || z == -2 || z == 2;
					if (!edge) {
						continue;
					}
					if (x == 0 && z == 2 && y <= 1) {
						continue; // door goes here
					}
					b.put(x, y, z, Blocks.COBBLESTONE);
				}
			}
		}
		for (int x = -2; x <= 2; x++)
			for (int z = -2; z <= 2; z++)
				b.put(x, 3, z, Blocks.COBBLESTONE);
		b.put(1, 0, 1, Blocks.TORCH);
		b.put(0, 0, 2, Blocks.OAK_DOOR);
	}
}
