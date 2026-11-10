package com.aicompanion;

import com.aicompanion.brain.CompanionBrain;
import com.aicompanion.brain.Mode;
import com.aicompanion.data.CompanionConfig;
import com.aicompanion.data.CompanionStorage;
import com.aicompanion.entity.CompanionPlayer;
import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Keeps track of every companion: making them join and leave the world, bringing them
 * along through portals, letting them "sleep" when you sleep, respawning them when they
 * die, and saving them with the world.
 */
public final class CompanionManager {
	private static final List<CompanionBrain> BRAINS = new ArrayList<>();
	private static final List<Scheduled> SCHEDULED = new ArrayList<>();
	/** Inventories of companions that are "asleep" (out of the world for a moment). */
	private static final java.util.Map<String, Inventory> STASH = new java.util.HashMap<>();
	private static MinecraftServer server;
	private static long serverTicks;

	private record Scheduled(long at, Runnable task) {
	}

	private CompanionManager() {
	}

	public static MinecraftServer server() {
		return server;
	}

	public static long serverTicks() {
		return serverTicks;
	}

	public static List<CompanionBrain> all() {
		return new ArrayList<>(BRAINS);
	}

	public static List<CompanionBrain> ownedBy(ServerPlayer player) {
		List<CompanionBrain> list = new ArrayList<>();
		for (CompanionBrain b : BRAINS) {
			if (b.ownerId.equals(player.getUUID())) {
				list.add(b);
			}
		}
		return list;
	}

	public static CompanionBrain byName(String name) {
		for (CompanionBrain b : BRAINS) {
			if (b.name.equalsIgnoreCase(name)) {
				return b;
			}
		}
		return null;
	}

	/** The companion the player means: the one with that name, otherwise their closest one. */
	public static CompanionBrain find(ServerPlayer player, String name) {
		if (name != null && !name.isBlank()) {
			CompanionBrain b = byName(name.trim());
			if (b != null && b.ownerId.equals(player.getUUID())) {
				return b;
			}
		}
		CompanionBrain closest = null;
		double best = Double.MAX_VALUE;
		for (CompanionBrain b : ownedBy(player)) {
			double d = b.hasBody() && b.body().level() == player.level() ? b.body().distanceToSqr(player) : 1.0E12;
			if (d < best) {
				best = d;
				closest = b;
			}
		}
		return closest;
	}

	public static int realPlayerCount(MinecraftServer s) {
		int n = 0;
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			if (!(p instanceof CompanionPlayer)) {
				n++;
			}
		}
		return n;
	}

	public static void later(int ticks, Runnable task) {
		SCHEDULED.add(new Scheduled(serverTicks + ticks, task));
	}

	// ------------------------------------------------------------------ joining & leaving

	public static String sanitizeName(String raw) {
		if (raw == null) {
			return CompanionConfig.get().defaultName;
		}
		String n = raw.replaceAll("[^A-Za-z0-9_]", "");
		if (n.length() > 16) {
			n = n.substring(0, 16);
		}
		return n;
	}

	/** Makes a companion join the world next to the owner. Returns an error message, or null on success. */
	public static String summon(ServerPlayer owner, String rawName) {
		MinecraftServer s = owner.level().getServer();
		server = s;
		String name = sanitizeName(rawName == null || rawName.isBlank() ? CompanionConfig.get().defaultName : rawName);
		if (name.length() < 3) {
			return "Companion names need 3-16 letters, numbers or _ (like Buddy or Steve_2).";
		}
		CompanionBrain existing = byName(name);
		if (existing != null) {
			if (existing.ownerId.equals(owner.getUUID())) {
				if (existing.hasBody() && existing.body().level() == owner.level()) {
					existing.teleportNear(owner);
				} else if (existing.hasBody()) {
					relocate(existing, owner.level(), CompanionBrain.findSpotNear(owner.level(), owner.blockPosition()));
				}
				existing.setMode(Mode.FOLLOW);
				existing.chat().sayLater("I'm already here! Coming to you.", 5);
				return null;
			}
			return "Someone else already has a companion called " + name + ".";
		}
		for (ServerPlayer p : s.getPlayerList().getPlayers()) {
			if (p.getName().getString().equalsIgnoreCase(name)) {
				return "A real player is already using the name " + name + ". Pick another name!";
			}
		}
		if (ownedBy(owner).size() >= Math.max(1, CompanionConfig.get().maxCompanionsPerPlayer)) {
			return "You already have " + ownedBy(owner).size() + " companions (the max). Send one away first.";
		}

		CompanionStorage.Saved saved = CompanionStorage.load(s, name);
		if (saved != null && saved.owner != null && !saved.owner.equals(owner.getUUID())) {
			return "The name " + name + " belongs to another player's companion in this world.";
		}

		CompanionBrain brain = new CompanionBrain(name, owner.getUUID());
		CompanionConfig cfg = CompanionConfig.get();
		brain.freeBuild = cfg.freeBuildByDefault;
		brain.defend = cfg.defendByDefault;
		if (saved != null) {
			brain.freeBuild = saved.freeBuild;
			brain.defend = saved.defend;
			brain.invincible = saved.invincible;
		}
		BRAINS.add(brain);

		BlockPos spot = CompanionBrain.findSpotNear(owner.level(), owner.blockPosition());
		CompanionPlayer body = spawnBody(brain, owner.level(), spot, owner.getYRot() + 180.0F);
		if (saved != null) {
			CompanionStorage.readInventory(s, body.getInventory(), saved.inventory);
		}
		announce(body, true);
		brain.setMode(Mode.FOLLOW);
		brain.chat().sayLater(saved != null
				? brain.pick("I'm back, " + owner.getName().getString() + "! I still have all my stuff.", "Hey again! Missed me?")
				: "Hi " + owner.getName().getString() + "! I'm " + name + ". Talk to me in chat, or press the Down Arrow for my menu!", 30);
		save(brain, true);
		return null;
	}

	/** The companion leaves the world (it remembers its stuff if you call it back with the same name). */
	public static void dismiss(CompanionBrain brain, boolean announce) {
		if (announce) {
			brain.say(brain.pick("Bye! Press the Down Arrow and click Join World to call me back.", "See you later!",
					"Leaving now - call me anytime!"));
		}
		save(brain, false);
		if (brain.hasBody()) {
			if (announce) {
				announce(brain.body(), false);
			}
			removeBody(brain);
		}
		BRAINS.remove(brain);
	}

	private static void announce(CompanionPlayer body, boolean joined) {
		MinecraftServer s = body.level().getServer();
		s.getPlayerList().broadcastSystemMessage(Component.translatable(
				joined ? "multiplayer.player.joined" : "multiplayer.player.left", body.getDisplayName()).withStyle(ChatFormatting.YELLOW), false);
	}

	/** Creates the player body and adds it to the world (and everyone's tab list). */
	static CompanionPlayer spawnBody(CompanionBrain brain, ServerLevel level, BlockPos spot, float yaw) {
		GameProfile profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(brain.name), brain.name);
		CompanionPlayer body = new CompanionPlayer(level, profile);
		body.snapTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5);
		body.setYRot(yaw);
		body.setYHeadRot(yaw);
		brain.attachBody(body);
		level.getServer().getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(body)));
		level.addNewPlayer(body);
		return body;
	}

	/** Takes the body out of the world. Its inventory object stays usable (for moving it to a new body). */
	static void removeBody(CompanionBrain brain) {
		CompanionPlayer body = brain.body();
		if (body == null) {
			return;
		}
		MinecraftServer s = body.level().getServer();
		if (!body.isRemoved()) {
			body.level().removePlayerImmediately(body, Entity.RemovalReason.UNLOADED_WITH_PLAYER);
		}
		s.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(body.getUUID())));
		brain.detachBody();
	}

	/** Moves the companion somewhere (another dimension, or just re-sends it to everyone) keeping its stuff. */
	static void relocate(CompanionBrain brain, ServerLevel level, BlockPos spot) {
		CompanionPlayer old = brain.body();
		Inventory inv = old != null ? old.getInventory() : null;
		float health = old != null ? old.getHealth() : 20.0F;
		float yaw = old != null ? old.getYRot() : 0.0F;
		removeBody(brain);
		CompanionPlayer body = spawnBody(brain, level, spot, yaw);
		if (inv != null) {
			body.getInventory().replaceWith(inv);
		}
		if (health > 0) {
			body.setHealth(Math.min(health, body.getMaxHealth()));
		}
	}

	// ------------------------------------------------------------------ saving

	static void save(CompanionBrain brain, boolean active) {
		if (server == null) {
			return;
		}
		CompanionStorage.Saved data = new CompanionStorage.Saved();
		data.name = brain.name;
		data.owner = brain.ownerId;
		data.active = active;
		data.freeBuild = brain.freeBuild;
		data.defend = brain.defend;
		data.invincible = brain.invincible;
		data.mode = brain.mode.id;
		Inventory inv = brain.body() != null ? brain.body().getInventory() : STASH.get(brain.name.toLowerCase(Locale.ROOT));
		if (inv != null) {
			data.inventory = CompanionStorage.writeInventory(server, inv);
		} else {
			CompanionStorage.Saved old = CompanionStorage.load(server, brain.name);
			if (old != null) {
				data.inventory = old.inventory;
			}
		}
		CompanionStorage.save(server, data);
	}

	// ------------------------------------------------------------------ events

	public static void onServerStarted(MinecraftServer s) {
		server = s;
		BRAINS.clear();
		SCHEDULED.clear();
		STASH.clear();
	}

	public static void onServerStopping(MinecraftServer s) {
		server = s;
		for (CompanionBrain b : new ArrayList<>(BRAINS)) {
			save(b, true);
			try {
				removeBody(b);
			} catch (Exception e) {
				AICompanion.LOGGER.warn("Problem removing companion {} on shutdown", b.name, e);
			}
		}
		BRAINS.clear();
		SCHEDULED.clear();
		STASH.clear();
	}

	public static void onPlayerJoin(ServerPlayer player) {
		if (player instanceof CompanionPlayer) {
			return;
		}
		server = player.level().getServer();
		// 1) bring back this player's companions from last time
		later(40, () -> {
			ServerPlayer p = server.getPlayerList().getPlayer(player.getUUID());
			if (p == null) {
				return;
			}
			for (CompanionStorage.Saved s : CompanionStorage.loadAll(server)) {
				if (s.active && player.getUUID().equals(s.owner) && byName(s.name) == null) {
					String err = summon(p, s.name);
					if (err != null) {
						AICompanion.LOGGER.info("Could not bring back companion {}: {}", s.name, err);
					} else {
						CompanionBrain b = byName(s.name);
						if (b != null) {
							b.setMode(Mode.byId(s.mode) == Mode.BUILD ? Mode.FOLLOW : Mode.byId(s.mode));
						}
					}
				}
			}
		});
		// 2) make sure the new player can see companions that are already here
		later(20, () -> {
			for (CompanionBrain b : new ArrayList<>(BRAINS)) {
				if (b.hasBody() && !b.ownerId.equals(player.getUUID())) {
					relocate(b, b.body().level(), b.body().blockPosition());
				}
			}
		});
	}

	public static void onPlayerLeave(ServerPlayer player) {
		if (player instanceof CompanionPlayer) {
			return;
		}
		for (CompanionBrain b : ownedBy(player)) {
			save(b, true);
			if (b.hasBody()) {
				announce(b.body(), false);
				removeBody(b);
			}
			BRAINS.remove(b);
		}
	}

	// ------------------------------------------------------------------ every tick

	public static void tick(MinecraftServer s) {
		server = s;
		serverTicks++;

		if (!SCHEDULED.isEmpty()) {
			List<Scheduled> due = new ArrayList<>();
			Iterator<Scheduled> it = SCHEDULED.iterator();
			while (it.hasNext()) {
				Scheduled sc = it.next();
				if (sc.at() <= serverTicks) {
					due.add(sc);
					it.remove();
				}
			}
			for (Scheduled sc : due) {
				try {
					sc.task().run();
				} catch (Exception e) {
					AICompanion.LOGGER.warn("Companion task failed", e);
				}
			}
		}

		for (CompanionBrain b : new ArrayList<>(BRAINS)) {
			try {
				b.chat().tick();
				manage(s, b);
			} catch (Exception e) {
				AICompanion.LOGGER.warn("Problem updating companion {}", b.name, e);
			}
		}

		if (serverTicks % 6000 == 0) {
			for (CompanionBrain b : BRAINS) {
				save(b, true);
			}
		}
	}

	private static void manage(MinecraftServer s, CompanionBrain b) {
		ServerPlayer owner = s.getPlayerList().getPlayer(b.ownerId);
		if (owner == null) {
			return; // handled by onPlayerLeave
		}

		// sleeping together: the companion "goes to bed" too, so the night can be skipped
		if (owner.isSleeping() && b.hasBody() && !b.sleeping) {
			b.sleeping = true;
			b.say(b.pick("Good night! Zzz...", "Sleep tight! I'll nap too.", "Bedtime! See you in the morning."));
			stash(b);
			owner.level().updateSleepingPlayerList();
			return;
		}
		if (b.sleeping) {
			if (!owner.isSleeping()) {
				b.sleeping = false;
				unstash(b, owner);
				b.chat().sayLater(owner.level().isDarkOutside() ? "Couldn't sleep either, huh?" : "Good morning! Let's go!", 20);
			}
			return;
		}

		CompanionPlayer body = b.body();
		if (body == null) {
			return;
		}

		// dying and coming back, like a player pressing "Respawn"
		if (body.isDeadOrDying() || body.isRemoved()) {
			if (++b.deathTicks == 10) {
				b.say(b.pick("Ouch! I'll be right back.", "Oops... respawning!", "Noooo! Be right there."));
			}
			if (b.deathTicks >= 60) {
				b.deathTicks = 0;
				relocate(b, owner.level(), CompanionBrain.findSpotNear(owner.level(), owner.blockPosition()));
				if (b.body() != null) {
					b.body().setHealth(b.body().getMaxHealth());
				}
				b.setMode(Mode.FOLLOW);
			}
			return;
		}
		b.deathTicks = 0;

		// follow the owner through portals
		if (body.level() != owner.level() && b.followsOwner()) {
			relocate(b, owner.level(), CompanionBrain.findSpotNear(owner.level(), owner.blockPosition()));
			b.chat().sayLater("Wait for me!", 10);
		}
	}

	private static void stash(CompanionBrain b) {
		CompanionPlayer body = b.body();
		if (body != null) {
			STASH.put(b.name.toLowerCase(Locale.ROOT), body.getInventory());
			removeBody(b);
		}
	}

	private static void unstash(CompanionBrain b, ServerPlayer owner) {
		CompanionPlayer body = spawnBody(b, owner.level(), CompanionBrain.findSpotNear(owner.level(), owner.blockPosition()),
				owner.getYRot() + 180.0F);
		Inventory inv = STASH.remove(b.name.toLowerCase(Locale.ROOT));
		if (inv != null) {
			body.getInventory().replaceWith(inv);
		}
	}

	public static boolean isCompanion(UUID id) {
		for (CompanionBrain b : BRAINS) {
			if (b.hasBody() && b.body().getUUID().equals(id)) {
				return true;
			}
		}
		return false;
	}
}
