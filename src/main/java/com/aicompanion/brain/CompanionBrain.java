package com.aicompanion.brain;

import com.aicompanion.CompanionManager;
import com.aicompanion.chat.CompanionChat;
import com.aicompanion.entity.CompanionPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The companion's mind. Every tick it decides what to do based on its current mode,
 * then moves its body by "pressing keys" (walk, jump, sprint, sneak) and "clicking"
 * (mine, place, attack) - the same inputs a real player uses.
 */
public final class CompanionBrain {
	public final String name;
	public final UUID ownerId;
	public final Random random = new Random();

	public Mode mode = Mode.FOLLOW;
	/** Build without using up its own blocks. */
	public boolean freeBuild = true;
	/** Fight hostile mobs that come close. */
	public boolean defend = true;
	/** Can't take damage. */
	public boolean invincible = false;

	private CompanionPlayer body;
	private final Navigator nav = new Navigator(this);
	private final Actions actions = new Actions(this);
	private final WorkTasks work = new WorkTasks(this);
	private final BuildTask build = new BuildTask(this);
	private final CompanionChat chat;

	int ticks;
	private int errors;
	private int lastTeleport = -1000;

	// emotes & errands
	private String emote;
	private int emoteTicks;
	private boolean giving;
	private String giveFilter;
	private boolean giveAll;
	private int noCollectUntil;
	private int collectIdle;

	// free play
	private String playTask;
	private int playTimer;
	private BlockPos wanderTarget;

	// look-around idling
	private float idleYaw;
	private int idleLookTimer;

	// fighting
	private int fightLineCooldown;

	// status flags used by the manager
	public boolean sleeping;
	public int deathTicks;

	public CompanionBrain(String name, UUID ownerId) {
		this.name = name;
		this.ownerId = ownerId;
		this.chat = new CompanionChat(this);
	}

	// ------------------------------------------------------------------ accessors

	public CompanionPlayer body() {
		return body;
	}

	public boolean hasBody() {
		return body != null && !body.isRemoved();
	}

	public void attachBody(CompanionPlayer body) {
		this.body = body;
		body.setBrain(this);
		nav.stop();
		actions.cancelBreaking();
	}

	public void detachBody() {
		this.body = null;
	}

	public Navigator nav() {
		return nav;
	}

	public Actions actions() {
		return actions;
	}

	public CompanionChat chat() {
		return chat;
	}

	public MinecraftServer server() {
		return body != null ? body.level().getServer() : CompanionManager.server();
	}

	public ServerPlayer owner() {
		MinecraftServer s = server();
		return s == null ? null : s.getPlayerList().getPlayer(ownerId);
	}

	public void say(String text) {
		chat.say(text);
	}

	public String pick(String... options) {
		return options[random.nextInt(options.length)];
	}

	/** Whether this mode keeps the companion near its owner (and follows them through portals). */
	public boolean followsOwner() {
		return mode == Mode.FOLLOW || mode == Mode.GUARD || mode == Mode.PLAY || mode == Mode.COLLECT;
	}

	public String buildingKind() {
		return build.kind();
	}

	public void setMode(Mode m) {
		this.mode = m;
		nav.stop();
		actions.cancelBreaking();
		work.reset();
		if (m != Mode.BUILD) {
			build.cancel();
		}
		playTask = null;
		collectIdle = 0;
		giving = false;
		work.noTunnel = false;
	}

	public void recoverFromError() {
		nav.stop();
		actions.cancelBreaking();
		if (++errors > 40) {
			errors = 0;
			setMode(Mode.FOLLOW);
		}
	}

	void announceCraft(String what) {
		if (random.nextInt(3) != 0 || what.contains("pickaxe") || what.contains("sword")) {
			say(pick("I crafted " + what + "!", "Made myself " + what + ".", "Look, " + what + "!"));
		}
	}

	// ------------------------------------------------------------------ the main loop

	public void tick() {
		CompanionPlayer bot = body;
		if (bot == null || bot.isRemoved() || bot.isDeadOrDying()) {
			return;
		}
		ticks++;
		nav.beginTick();
		actions.tick();

		ServerPlayer owner = owner();

		if (ticks % 100 == 0) {
			keepFed();
		}
		if (ticks % 40 == 0) {
			Crafting.tick(this);
		}
		chat.idleChatter(owner);

		// lost? (fell out of the world, or owner ran far away)
		if (owner != null && owner.level() == bot.level()) {
			double d = bot.distanceTo(owner);
			boolean far = followsOwner() ? d > 48 : d > 160;
			if ((far || bot.getY() < -128) && ticks - lastTeleport > 100) {
				teleportNear(owner);
				return;
			}
		}

		if (emoteTicks > 0) {
			emoteTick();
			return;
		}
		if (giving) {
			giveTick(owner);
			return;
		}

		if (defend) {
			LivingEntity threat = findThreat(owner);
			if (threat != null) {
				fight(threat);
				return;
			}
		}

		switch (mode) {
			case IDLE -> idleTick(owner);
			case FOLLOW, GUARD -> followTick(owner);
			case WOOD -> {
				if (work.woodTick(owner)) {
					say(pick("I've got " + countLogs() + " logs! What should we do next?",
							"That's plenty of wood: " + countLogs() + " logs!"));
					setMode(Mode.FOLLOW);
				}
			}
			case MINE -> {
				if (work.mineTick(owner)) {
					setMode(Mode.FOLLOW);
				}
			}
			case COLLECT -> collectTick(owner);
			case BUILD -> build.tick(owner);
			case PLAY -> playTick(owner);
		}
	}

	private void keepFed() {
		// the companion packs its own snacks
		if (body.getFoodData().getFoodLevel() < 20) {
			body.getFoodData().setFoodLevel(20);
		}
	}

	int countLogs() {
		return actions.count(s -> s.is(ItemTags.LOGS));
	}

	// ------------------------------------------------------------------ moving around

	public void teleportNear(ServerPlayer owner) {
		BlockPos spot = findSpotNear(owner.level(), owner.blockPosition());
		body.teleportTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
		body.setDeltaMovement(Vec3.ZERO);
		body.resetFallDistance();
		nav.stop();
		lastTeleport = ticks;
	}

	public static BlockPos findSpotNear(ServerLevel level, BlockPos center) {
		int[] dys = {0, 1, -1, 2, -2, 3};
		for (int r = 1; r <= 3; r++) {
			for (int dy : dys) {
				for (int dx = -r; dx <= r; dx++) {
					for (int dz = -r; dz <= r; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
							continue;
						}
						BlockPos p = center.offset(dx, dy, dz);
						if (Pathfinder.standable(level, p) && !Pathfinder.isWater(level, p)) {
							return p;
						}
					}
				}
			}
		}
		return center;
	}

	private void followTick(ServerPlayer owner) {
		if (owner == null || owner.level() != body.level()) {
			idleTick(null);
			return;
		}
		double d = body.distanceTo(owner);
		if (d > 3.5) {
			Navigator.Result r = nav.moveTo(owner.blockPosition(), 2.2, d > 6);
			if (r == Navigator.Result.FAILED && d > 6 && ticks - lastTeleport > 60) {
				teleportNear(owner);
			}
		} else {
			nav.stop();
			if (ticks % 4 == 0) {
				actions.lookAt(owner.getEyePosition());
			}
			// copy the owner's sneaking - a classic way players say hi
			body.setShiftKeyDown(owner.isShiftKeyDown());
		}
	}

	/** Used by tasks when they have nothing to do: stay close but don't crowd the owner. */
	void followLoosely(ServerPlayer owner) {
		if (owner != null && owner.level() == body.level() && body.distanceTo(owner) > 8) {
			nav.moveTo(owner.blockPosition(), 4.0, false);
		} else {
			lookAround();
		}
	}

	private void idleTick(ServerPlayer owner) {
		nav.stop();
		Player nearest = null;
		double best = 64;
		for (ServerPlayer p : body.level().players()) {
			if (p == body) {
				continue;
			}
			double d = p.distanceToSqr(body);
			if (d < best) {
				best = d;
				nearest = p;
			}
		}
		if (nearest != null) {
			if (ticks % 4 == 0) {
				actions.lookAt(nearest.getEyePosition());
			}
		} else {
			lookAround();
		}
	}

	void lookAround() {
		if (--idleLookTimer <= 0) {
			idleLookTimer = 40 + random.nextInt(80);
			idleYaw = body.getYRot() + (random.nextFloat() - 0.5F) * 140F;
		}
		float yaw = body.getYRot();
		float diff = ((idleYaw - yaw + 540F) % 360F) - 180F;
		actions.faceYaw(yaw + diff * 0.1F);
		body.setXRot(body.getXRot() * 0.9F);
	}

	// ------------------------------------------------------------------ collecting

	/** Walk to the nearest dropped item. Returns false if there is none. */
	boolean collectNearby(double radius) {
		if (ticks < noCollectUntil) {
			return false;
		}
		List<ItemEntity> items = body.level().getEntitiesOfClass(ItemEntity.class, body.getBoundingBox().inflate(radius, 3, radius),
				e -> e.isAlive() && !e.getItem().isEmpty());
		ItemEntity nearest = null;
		double best = Double.MAX_VALUE;
		for (ItemEntity e : items) {
			double d = e.distanceToSqr(body);
			if (d < best) {
				best = d;
				nearest = e;
			}
		}
		if (nearest == null) {
			return false;
		}
		if (body.getInventory().getFreeSlot() < 0 && body.getInventory().getSlotWithRemainingSpace(nearest.getItem()) < 0) {
			return false;
		}
		nav.moveTo(nearest.blockPosition(), 0.7, false);
		return true;
	}

	private void collectTick(ServerPlayer owner) {
		if (collectNearby(16)) {
			collectIdle = 0;
			return;
		}
		if (++collectIdle > 100) {
			say(pick("All cleaned up!", "I picked up everything I could find."));
			setMode(Mode.FOLLOW);
			return;
		}
		followLoosely(owner);
	}

	// ------------------------------------------------------------------ fighting

	private LivingEntity findThreat(ServerPlayer owner) {
		ServerLevel level = body.level();
		boolean guarding = mode == Mode.GUARD;
		LivingEntity best = null;
		double bestScore = Double.MAX_VALUE;

		// whoever just hurt us or our owner (but never a player)
		for (LivingEntity attacker : new LivingEntity[]{body.getLastHurtByMob(), owner != null ? owner.getLastHurtByMob() : null}) {
			if (attacker != null && attacker.isAlive() && !(attacker instanceof Player) && attacker.level() == level
					&& attacker.distanceTo(body) < 20) {
				double s = attacker.distanceToSqr(body) * 0.25;
				if (s < bestScore) {
					bestScore = s;
					best = attacker;
				}
			}
		}

		boolean ownerNear = owner != null && owner.level() == level && owner.distanceTo(body) < 24 && mode != Mode.IDLE;
		double radius = guarding ? 16 : 8;
		var center = ownerNear ? owner : body;
		List<Mob> mobs = level.getEntitiesOfClass(Mob.class, center.getBoundingBox().inflate(radius), m -> m.isAlive() && m instanceof Enemy);
		for (Mob m : mobs) {
			boolean angry = m.getTarget() == body || (owner != null && m.getTarget() == owner);
			EntityType<?> t = m.getType();
			boolean neutral = t == EntityType.ENDERMAN || t == EntityType.ZOMBIFIED_PIGLIN || t == EntityType.PIGLIN
					|| t == EntityType.PIGLIN_BRUTE;
			if (neutral && !angry) {
				continue;
			}
			double d = m.distanceTo(body);
			if (!guarding && !angry && d > 5) {
				continue;
			}
			double score = m.distanceToSqr(body) * (angry ? 0.5 : 1.0);
			if (score < bestScore) {
				bestScore = score;
				best = m;
			}
		}
		if (best != null && best.distanceTo(body) > 24) {
			return null;
		}
		return best;
	}

	private void fight(LivingEntity target) {
		actions.equipBestWeapon();
		double d = body.distanceTo(target);
		if (d > 2.6) {
			nav.moveTo(target.blockPosition(), 1.5, true);
		} else {
			nav.stop();
		}
		actions.lookAt(target);
		if (d <= 3.2 && body.getAttackStrengthScale(0.5F) >= 0.9F) {
			actions.attack(target);
		}
		if (--fightLineCooldown <= 0) {
			fightLineCooldown = 600;
			if (random.nextInt(2) == 0) {
				String mob = target.getName().getString();
				say(pick("I'll handle the " + mob + "!", "Take that, " + mob + "!", "Stay back, I've got this!"));
			}
		}
	}

	// ------------------------------------------------------------------ errands & emotes

	public void startGive(String filter) {
		giving = true;
		giveAll = "all".equals(filter) || "everything".equals(filter);
		giveFilter = giveAll ? null : filter;
	}

	private void giveTick(ServerPlayer owner) {
		if (owner == null || owner.level() != body.level()) {
			giving = false;
			return;
		}
		if (body.distanceTo(owner) > 3.0) {
			if (nav.moveTo(owner.blockPosition(), 2.0, true) == Navigator.Result.FAILED) {
				teleportNear(owner);
			}
			return;
		}
		nav.stop();
		actions.lookAt(owner.getEyePosition());
		int n = actions.throwItems(giveFilter, giveAll);
		say(n > 0 ? pick("Here you go!", "Catch!", "All yours!")
				: (giveFilter != null ? "I don't have any " + giveFilter + " right now." : "My pockets are empty!"));
		giving = false;
		noCollectUntil = ticks + 200; // don't grab it all back
	}

	public void startEmote(String which, int duration) {
		emote = which;
		emoteTicks = duration;
		nav.stop();
	}

	private void emoteTick() {
		emoteTicks--;
		switch (emote) {
			case "dance" -> {
				actions.faceYaw(body.getYRot() + 24F);
				if (body.onGround() && ticks % 10 == 0) {
					body.setJumping(true);
				}
				body.setShiftKeyDown((ticks / 5) % 2 == 0);
			}
			case "jump" -> {
				if (body.onGround()) {
					body.setJumping(true);
				}
			}
			case "wave" -> {
				if (ticks % 6 == 0) {
					body.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
				}
				ServerPlayer o = owner();
				if (o != null && o.level() == body.level()) {
					actions.lookAt(o.getEyePosition());
				}
			}
			default -> emoteTicks = 0;
		}
	}

	// ------------------------------------------------------------------ free play

	private void playTick(ServerPlayer owner) {
		if (owner == null || owner.level() != body.level()) {
			idleTick(owner);
			return;
		}
		double d = body.distanceTo(owner);
		if (d > 28) {
			playTask = null;
			followTick(owner);
			return;
		}
		if (playTask == null || --playTimer <= 0) {
			choosePlayTask(owner);
		}
		switch (playTask) {
			case "wood" -> {
				work.woodGoal = 16;
				if (work.woodTick(owner)) {
					playTask = null;
				}
			}
			case "mine" -> {
				work.noTunnel = true;
				if (work.mineTick(owner)) {
					playTask = null;
				}
			}
			case "collect" -> {
				if (!collectNearby(10)) {
					playTask = null;
				}
			}
			case "wander" -> {
				if (wanderTarget == null || nav.moveTo(wanderTarget, 1.0, false) != Navigator.Result.MOVING) {
					wanderTarget = null;
					lookAround();
					if (random.nextInt(60) == 0 && body.onGround()) {
						body.setJumping(true);
					}
				}
			}
			default -> followTick(owner);
		}
	}

	private void choosePlayTask(ServerPlayer owner) {
		work.reset();
		nav.stop();
		int wood = countLogs() + actions.count(s -> s.is(ItemTags.PLANKS)) / 4;
		int roll = random.nextInt(100);
		if (wood < 16 && roll < 60) {
			playTask = "wood";
			playTimer = 1200;
		} else if (actions.hasPickaxe() && roll < 45) {
			playTask = "mine";
			playTimer = 900;
		} else if (roll < 65) {
			playTask = "collect";
			playTimer = 200;
		} else if (roll < 85) {
			playTask = "wander";
			playTimer = 240;
			BlockPos o = owner.blockPosition();
			wanderTarget = findSpotNear(body.level(), o.offset(random.nextInt(17) - 8, 0, random.nextInt(17) - 8));
		} else {
			playTask = "follow";
			playTimer = 300;
		}
	}

	// ------------------------------------------------------------------ commands (from chat, the menu and /companion)

	/**
	 * Runs an action such as "follow", "build:house", "mine:diamonds" or "give:wood".
	 * Returns what the companion says back (or null).
	 */
	public String command(String action, ServerPlayer by) {
		String arg = null;
		int colon = action.indexOf(':');
		if (colon > 0) {
			arg = action.substring(colon + 1);
			action = action.substring(0, colon);
		}
		action = action.toLowerCase(Locale.ROOT);
		switch (action) {
			case "follow":
			case "come": {
				setMode(Mode.FOLLOW);
				if (by != null && body != null && by.level() == body.level() && body.distanceTo(by) > 40) {
					teleportNear(by);
				}
				return pick("Right behind you!", "Coming!", "Lead the way!", "On my way!");
			}
			case "stay":
				setMode(Mode.IDLE);
				return pick("Okay, I'll wait right here.", "I'll stay put!", "Waiting here!");
			case "play":
				setMode(Mode.PLAY);
				return pick("Yay! I'll do my own thing but stay close.", "Free time! I'll chop, mine and explore nearby.");
			case "guard":
				setMode(Mode.GUARD);
				defend = true;
				return pick("I've got your back! No mob gets near you.", "Guard mode on. Let them come!");
			case "wood":
				work.woodGoal = Math.min(64, countLogs() + 16);
				setMode(Mode.WOOD);
				return pick("Off to chop some trees!", "Timber time!", "I'll get us some wood.");
			case "mine":
				setMode(Mode.MINE);
				work.configureMine(arg);
				return arg != null ? "Let's go find some " + arg + "!" : pick("Let's go mining!", "Grabbing my pickaxe!");
			case "collect":
				setMode(Mode.COLLECT);
				return "I'll pick up everything lying around.";
			case "build": {
				String kind = Blueprints.normalize(arg);
				if (kind == null) {
					return "I can build a house, tower, wall, bridge or shelter. Which one?";
				}
				if (by == null) {
					return null;
				}
				setMode(Mode.BUILD);
				build.start(kind, by);
				String supplies = freeBuild ? "" : " (using my own blocks)";
				return pick("Building a " + kind + "! Stand back and watch.", "One " + kind + " coming right up!") + supplies;
			}
			case "give":
				startGive(arg);
				return arg == null ? "Coming over with my stuff!" : "Let me find my " + arg + "...";
			case "dance":
				startEmote("dance", 100);
				return pick("Dance party!", "Watch these moves!");
			case "jump":
				startEmote("jump", 50);
				return "Boing! Boing!";
			case "wave":
				startEmote("wave", 40);
				return "Hiii!";
			case "free":
				freeBuild = toggle(arg, freeBuild);
				return freeBuild ? "Free Blocks ON: I'll build without using up my own blocks."
						: "Free Blocks OFF: I'll only build with blocks I actually have.";
			case "invincible":
				invincible = toggle(arg, invincible);
				return invincible ? "Invincible ON: nothing can hurt me now!" : "Invincible OFF: I can get hurt like a normal player.";
			case "defend":
				defend = toggle(arg, defend);
				return defend ? "I'll fight off monsters that come close." : "Okay, I won't fight unless you tell me to guard you.";
			case "status":
				return statusLine();
			case "inventory":
				return inventoryLine();
			case "leave":
				CompanionManager.dismiss(this, true);
				return null;
			default:
				return null;
		}
	}

	private static boolean toggle(String arg, boolean current) {
		if (arg == null || arg.equals("toggle")) {
			return !current;
		}
		return arg.equals("on") || arg.equals("true") || arg.equals("yes");
	}

	public String statusLine() {
		if (body == null) {
			return "I'm taking a break right now.";
		}
		String doing = mode == Mode.BUILD && build.active()
				? "building a " + build.kind() + " (" + build.progressPercent() + "% done)"
				: mode.description;
		return "I'm " + doing + ". Health: " + Math.round(body.getHealth()) + "/" + Math.round(body.getMaxHealth())
				+ ". Free Blocks: " + (freeBuild ? "on" : "off") + ", Invincible: " + (invincible ? "on" : "off") + ".";
	}

	public String inventoryLine() {
		if (body == null) {
			return "I don't have my backpack with me.";
		}
		Map<String, Integer> totals = new TreeMap<>();
		Inventory inv = body.getInventory();
		for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty()) {
				totals.merge(s.getHoverName().getString(), s.getCount(), Integer::sum);
			}
		}
		if (totals.isEmpty()) {
			return "My pockets are empty!";
		}
		List<Map.Entry<String, Integer>> list = new ArrayList<>(totals.entrySet());
		list.sort((a, b) -> b.getValue() - a.getValue());
		StringBuilder sb = new StringBuilder("I have: ");
		int shown = 0;
		for (Map.Entry<String, Integer> e : list) {
			if (shown++ >= 8) {
				sb.append(", and more");
				break;
			}
			if (shown > 1) {
				sb.append(", ");
			}
			sb.append(e.getValue()).append(" ").append(e.getKey());
		}
		return sb.toString();
	}

	/** Short description of the situation, for the AI chat. */
	public String situation() {
		if (body == null) {
			return "You are currently resting (not in the world).";
		}
		ServerPlayer owner = owner();
		StringBuilder sb = new StringBuilder();
		sb.append("Current activity: ").append(statusLine()).append(' ');
		sb.append(inventoryLine()).append(". ");
		sb.append("Position: ").append(body.getBlockX()).append(", ").append(body.getBlockY()).append(", ").append(body.getBlockZ()).append(". ");
		sb.append(body.level().isDarkOutside() ? "It is night time. " : "It is day time. ");
		if (owner != null) {
			sb.append("Your friend ").append(owner.getName().getString()).append(" has ")
					.append(Math.round(owner.getHealth())).append(" health");
			if (owner.level() == body.level()) {
				sb.append(" and is ").append(Math.round(body.distanceTo(owner))).append(" blocks away");
			}
			sb.append(". ");
		}
		return sb.toString();
	}
}
