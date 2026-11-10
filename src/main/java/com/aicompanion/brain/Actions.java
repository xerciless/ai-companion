package com.aicompanion.brain;

import com.aicompanion.entity.CompanionPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * The companion's "hands": looking, mining, placing, attacking and inventory handling.
 * Everything goes through the same game code a real player's clicks go through.
 */
public final class Actions {
	public enum PlaceResult { PLACED, WAITING, FAILED }

	private static final List<Item> WEAPONS = List.of(
			Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_AXE, Items.DIAMOND_AXE,
			Items.IRON_SWORD, Items.IRON_AXE, Items.STONE_SWORD, Items.STONE_AXE,
			Items.GOLDEN_SWORD, Items.WOODEN_SWORD, Items.GOLDEN_AXE, Items.WOODEN_AXE);

	private final CompanionBrain brain;
	private BlockPos breakingPos;
	private float breakProgress;
	private int lastStage = -1;
	private int breakIdle;
	private int swingTimer;
	private int useCooldown;
	private BlockPos openedDoor;
	private int doorTimer;

	Actions(CompanionBrain brain) {
		this.brain = brain;
	}

	CompanionPlayer bot() {
		return brain.body();
	}

	public void tick() {
		if (useCooldown > 0) {
			useCooldown--;
		}
		if (swingTimer > 0) {
			swingTimer--;
		}
		if (breakingPos != null && ++breakIdle > 5) {
			cancelBreaking();
		}
		// close doors we opened once we're through them
		if (openedDoor != null) {
			doorTimer++;
			if (doorTimer > 20 && bot().blockPosition().distSqr(openedDoor) > 6) {
				setDoor(openedDoor, false);
				openedDoor = null;
			} else if (doorTimer > 400) {
				openedDoor = null;
			}
		}
	}

	// ------------------------------------------------------------------ looking

	public void faceYaw(float yaw) {
		CompanionPlayer bot = bot();
		bot.setYRot(yaw);
		bot.setYHeadRot(yaw);
		bot.setYBodyRot(yaw);
	}

	public void lookAt(Vec3 target) {
		CompanionPlayer bot = bot();
		Vec3 eye = bot.getEyePosition();
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		double horiz = Math.sqrt(dx * dx + dz * dz);
		if (horiz > 0.001) {
			faceYaw((float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F);
		}
		float pitch = (float) (-(Mth.atan2(dy, horiz) * (180.0 / Math.PI)));
		bot.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
	}

	public void lookAt(Entity e) {
		lookAt(e.position().add(0, e.getBbHeight() * 0.75, 0));
	}

	// ------------------------------------------------------------------ blocks

	public boolean canReach(BlockPos pos, double reach) {
		return bot().getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= reach * reach;
	}

	public boolean isBreakable(BlockPos pos) {
		ServerLevel level = bot().level();
		BlockState s = level.getBlockState(pos);
		return !s.isAir() && s.getFluidState().isEmpty() && s.getDestroySpeed(level, pos) >= 0;
	}

	/** The first solid block between the companion's eyes and the target (null if it can see it). */
	public BlockPos firstObstruction(BlockPos target) {
		CompanionPlayer bot = bot();
		BlockHitResult hit = bot.level().clip(new ClipContext(bot.getEyePosition(), Vec3.atCenterOf(target),
				ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, bot));
		if (hit.getType() == HitResult.Type.MISS) {
			return null;
		}
		BlockPos p = hit.getBlockPos();
		return p.equals(target) ? null : p.immutable();
	}

	public static boolean nearLava(ServerLevel level, BlockPos pos) {
		if (level.getFluidState(pos).is(FluidTags.LAVA)) {
			return true;
		}
		for (Direction d : Direction.values()) {
			if (level.getFluidState(pos.relative(d)).is(FluidTags.LAVA)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Mines a block like a player holding left click. Call every tick.
	 * Returns true once the block is gone.
	 */
	public boolean breakBlock(BlockPos pos) {
		CompanionPlayer bot = bot();
		ServerLevel level = bot.level();
		BlockState state = level.getBlockState(pos);
		breakIdle = 0;
		if (state.isAir() || !state.getFluidState().isEmpty() && state.canBeReplaced()) {
			return true;
		}
		if (state.getDestroySpeed(level, pos) < 0) {
			return true; // bedrock & friends: give up on it
		}
		if (!pos.equals(breakingPos)) {
			cancelBreaking();
			breakingPos = pos.immutable();
			breakProgress = 0;
			selectBestToolFor(state);
		}
		lookAt(Vec3.atCenterOf(pos));
		if (swingTimer <= 0) {
			bot.swing(InteractionHand.MAIN_HAND);
			swingTimer = 5;
		}
		breakProgress += state.getDestroyProgress(bot, level, pos);
		if (breakProgress >= 1.0F) {
			level.destroyBlockProgress(bot.getId(), pos, -1);
			bot.gameMode.destroyBlock(pos);
			breakingPos = null;
			breakProgress = 0;
			lastStage = -1;
			return true;
		}
		int stage = Math.min(9, (int) (breakProgress * 10.0F));
		if (stage != lastStage) {
			level.destroyBlockProgress(bot.getId(), pos, stage);
			lastStage = stage;
		}
		return false;
	}

	public void cancelBreaking() {
		if (breakingPos != null && brain.body() != null) {
			bot().level().destroyBlockProgress(bot().getId(), breakingPos, -1);
		}
		breakingPos = null;
		breakProgress = 0;
		lastStage = -1;
		breakIdle = 0;
	}

	/**
	 * Places a block like a player holding right click with it.
	 * With free = true it doesn't use up the companion's own blocks.
	 */
	public PlaceResult placeBlock(BlockPos pos, Block block, boolean free) {
		CompanionPlayer bot = bot();
		ServerLevel level = bot.level();
		Item item = block.asItem();
		if (item == Items.AIR) {
			return PlaceResult.FAILED;
		}
		BlockState current = level.getBlockState(pos);
		if (!current.isAir() && current.getBlock().asItem() == item) {
			return PlaceResult.PLACED;
		}
		if (!current.canBeReplaced()) {
			return PlaceResult.FAILED;
		}
		if (useCooldown > 0) {
			return PlaceResult.WAITING;
		}
		if (bot.getBoundingBox().intersects(new AABB(pos))) {
			return PlaceResult.WAITING; // caller should step aside first
		}

		Inventory inv = bot.getInventory();
		int selected;
		ItemStack previous = null;
		ItemStack stack;
		if (free) {
			selected = inv.getSelectedSlot();
			previous = inv.getItem(selected);
			stack = new ItemStack(item, 64);
			inv.setItem(selected, stack);
		} else {
			int slot = findSlot(item);
			if (slot < 0) {
				return PlaceResult.FAILED;
			}
			equipSlot(slot);
			selected = inv.getSelectedSlot();
			stack = inv.getItem(selected);
		}

		lookAt(Vec3.atCenterOf(pos));
		BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
		try {
			bot.gameMode.useItemOn(bot, level, stack, InteractionHand.MAIN_HAND, hit);
		} finally {
			if (previous != null) {
				inv.setItem(selected, previous);
			}
		}
		bot.swing(InteractionHand.MAIN_HAND);
		useCooldown = 3;
		BlockState after = level.getBlockState(pos);
		return !after.isAir() && after.getBlock().asItem() == item ? PlaceResult.PLACED : PlaceResult.FAILED;
	}

	// ------------------------------------------------------------------ doors

	public void openDoorIfClosed(BlockPos pos) {
		ServerLevel level = bot().level();
		BlockState s = level.getBlockState(pos);
		if (s.is(BlockTags.WOODEN_DOORS) && s.hasProperty(DoorBlock.OPEN) && !s.getValue(DoorBlock.OPEN)) {
			setDoor(pos, true);
			openedDoor = pos.immutable();
			doorTimer = 0;
		}
	}

	private void setDoor(BlockPos pos, boolean open) {
		CompanionPlayer bot = bot();
		ServerLevel level = bot.level();
		BlockState s = level.getBlockState(pos);
		if (s.getBlock() instanceof DoorBlock door && s.hasProperty(DoorBlock.OPEN) && s.getValue(DoorBlock.OPEN) != open) {
			door.setOpen(bot, level, s, pos, open);
		}
	}

	// ------------------------------------------------------------------ fighting

	public void attack(Entity target) {
		CompanionPlayer bot = bot();
		lookAt(target);
		bot.attack(target);
		bot.swing(InteractionHand.MAIN_HAND);
	}

	public void equipBestWeapon() {
		for (Item w : WEAPONS) {
			int slot = findSlot(w);
			if (slot >= 0) {
				equipSlot(slot);
				return;
			}
		}
		int any = findSlot(s -> s.is(ItemTags.SWORDS));
		if (any < 0) {
			any = findSlot(s -> s.is(ItemTags.AXES));
		}
		if (any >= 0) {
			equipSlot(any);
		}
		// otherwise: fists it is
	}

	// ------------------------------------------------------------------ inventory

	public void selectBestToolFor(BlockState state) {
		Inventory inv = bot().getInventory();
		int best = -1;
		float bestSpeed = 1.0F;
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) {
				continue;
			}
			float speed = s.getDestroySpeed(state);
			if (speed > bestSpeed) {
				bestSpeed = speed;
				best = i;
			}
		}
		if (best >= 0) {
			equipSlot(best);
			return;
		}
		// no tool helps: don't wear down a tool, hold something harmless
		ItemStack held = inv.getSelectedItem();
		if (!held.isEmpty() && held.isDamageableItem()) {
			for (int i = 0; i < Inventory.getSelectionSize(); i++) {
				ItemStack s = inv.getItem(i);
				if (s.isEmpty() || !s.isDamageableItem()) {
					inv.setSelectedSlot(i);
					return;
				}
			}
		}
	}

	/** Puts the item from any inventory slot into the hand. */
	public void equipSlot(int slot) {
		Inventory inv = bot().getInventory();
		if (slot < 0) {
			return;
		}
		if (slot < Inventory.getSelectionSize()) {
			inv.setSelectedSlot(slot);
			return;
		}
		int sel = inv.getSelectedSlot();
		ItemStack inHand = inv.getItem(sel);
		inv.setItem(sel, inv.getItem(slot));
		inv.setItem(slot, inHand);
	}

	public int findSlot(Item item) {
		return findSlot(s -> s.is(item));
	}

	public int findSlot(Predicate<ItemStack> test) {
		Inventory inv = bot().getInventory();
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && test.test(s)) {
				return i;
			}
		}
		return -1;
	}

	public int count(Predicate<ItemStack> test) {
		Inventory inv = bot().getInventory();
		int n = 0;
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && test.test(s)) {
				n += s.getCount();
			}
		}
		return n;
	}

	public int count(Item item) {
		return count(s -> s.is(item));
	}

	/** Removes up to amount matching items. Returns how many were removed. */
	public int remove(Predicate<ItemStack> test, int amount) {
		Inventory inv = bot().getInventory();
		int left = amount;
		for (int i = 0; i < Inventory.INVENTORY_SIZE && left > 0; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && test.test(s)) {
				int take = Math.min(left, s.getCount());
				s.shrink(take);
				if (s.isEmpty()) {
					inv.setItem(i, ItemStack.EMPTY);
				}
				left -= take;
			}
		}
		inv.setChanged();
		return amount - left;
	}

	/** Adds an item; if the inventory is full it's dropped at the companion's feet. */
	public void give(ItemStack stack) {
		CompanionPlayer bot = bot();
		if (!bot.getInventory().add(stack) && !stack.isEmpty()) {
			bot.drop(stack, false, false);
		}
	}

	public boolean hasPickaxe() {
		return findSlot(s -> s.is(ItemTags.PICKAXES)) >= 0;
	}

	/** Tool quality: 0 none, 1 wood/gold, 2 stone (or other), 3 iron, 4 diamond, 5 netherite. */
	public int bestPickaxeTier() {
		int best = 0;
		Inventory inv = bot().getInventory();
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty() && s.is(ItemTags.PICKAXES)) {
				best = Math.max(best, tierOf(s));
			}
		}
		return best;
	}

	public static int tierOf(ItemStack s) {
		String id = s.getItem().toString().toLowerCase(Locale.ROOT);
		if (id.contains("netherite")) return 5;
		if (id.contains("diamond")) return 4;
		if (id.contains("iron")) return 3;
		if (id.contains("wooden") || id.contains("golden")) return 1;
		return 2;
	}

	/** Throws items to whoever the companion is looking at. Returns how many stacks were given. */
	public int throwItems(String filter, boolean everything) {
		CompanionPlayer bot = bot();
		Inventory inv = bot.getInventory();
		int given = 0;
		boolean keptPick = false;
		boolean keptWeapon = false;
		boolean keptAxe = false;
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) {
				continue;
			}
			String name = s.getHoverName().getString().toLowerCase(Locale.ROOT);
			if (filter != null && !filter.isEmpty() && !everything && !matchesFilter(s, name, filter)) {
				continue;
			}
			if (!everything && filter == null) {
				// keep one of each tool so the companion can keep working
				if (!keptPick && s.is(ItemTags.PICKAXES)) { keptPick = true; continue; }
				if (!keptAxe && s.is(ItemTags.AXES)) { keptAxe = true; continue; }
				if (!keptWeapon && s.is(ItemTags.SWORDS)) { keptWeapon = true; continue; }
				if (s.is(Items.CRAFTING_TABLE) && s.getCount() == 1) continue;
			}
			ItemStack out = inv.removeItemNoUpdate(i);
			bot.drop(out, false, true);
			given++;
		}
		inv.setChanged();
		return given;
	}

	private static boolean matchesFilter(ItemStack s, String name, String filter) {
		switch (filter) {
			case "wood":
			case "log":
			case "logs":
				return s.is(ItemTags.LOGS) || s.is(ItemTags.PLANKS);
			case "stone":
			case "cobble":
			case "cobblestone":
				return name.contains("stone") || name.contains("deepslate");
			case "ore":
			case "ores":
			case "minerals":
				return name.contains("raw") || name.contains("diamond") || name.contains("coal") || name.contains("emerald")
						|| name.contains("lapis") || name.contains("redstone") || name.contains("ore") || name.contains("quartz");
			case "food":
				return name.contains("apple") || name.contains("beef") || name.contains("pork") || name.contains("chicken")
						|| name.contains("bread") || name.contains("mutton") || name.contains("carrot") || name.contains("potato");
			default:
				String f = filter.endsWith("s") && filter.length() > 3 ? filter.substring(0, filter.length() - 1) : filter;
				return name.contains(f);
		}
	}
}
