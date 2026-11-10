package com.aicompanion.data;

import com.aicompanion.AICompanion;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Saves each companion (name, owner, settings and inventory) inside the world folder:
 * saves/&lt;world&gt;/aicompanion/&lt;name&gt;.json
 * so it keeps its stuff between sessions and comes back when you rejoin the world.
 */
public final class CompanionStorage {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private CompanionStorage() {
	}

	public static final class Saved {
		public String name;
		public UUID owner;
		public boolean active;
		public boolean freeBuild;
		public boolean defend;
		public boolean invincible;
		public String mode = "FOLLOW";
		public JsonArray inventory = new JsonArray();
	}

	private static Path dir(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("aicompanion");
	}

	private static Path file(MinecraftServer server, String name) {
		return dir(server).resolve(name.toLowerCase(Locale.ROOT) + ".json");
	}

	public static void save(MinecraftServer server, Saved data) {
		try {
			Files.createDirectories(dir(server));
			JsonObject o = new JsonObject();
			o.addProperty("name", data.name);
			o.addProperty("owner", data.owner == null ? "" : data.owner.toString());
			o.addProperty("active", data.active);
			o.addProperty("freeBuild", data.freeBuild);
			o.addProperty("defend", data.defend);
			o.addProperty("invincible", data.invincible);
			o.addProperty("mode", data.mode);
			o.add("inventory", data.inventory);
			Files.writeString(file(server, data.name), GSON.toJson(o), StandardCharsets.UTF_8);
		} catch (Exception e) {
			AICompanion.LOGGER.warn("Could not save companion {}", data.name, e);
		}
	}

	public static Saved load(MinecraftServer server, String name) {
		Path p = file(server, name);
		if (!Files.exists(p)) {
			return null;
		}
		return read(p);
	}

	public static List<Saved> loadAll(MinecraftServer server) {
		List<Saved> list = new ArrayList<>();
		Path d = dir(server);
		if (!Files.isDirectory(d)) {
			return list;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(d, "*.json")) {
			for (Path p : stream) {
				Saved s = read(p);
				if (s != null) {
					list.add(s);
				}
			}
		} catch (IOException e) {
			AICompanion.LOGGER.warn("Could not list saved companions", e);
		}
		return list;
	}

	private static Saved read(Path p) {
		try {
			JsonObject o = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
			Saved s = new Saved();
			s.name = o.get("name").getAsString();
			String owner = o.has("owner") ? o.get("owner").getAsString() : "";
			s.owner = owner.isEmpty() ? null : UUID.fromString(owner);
			s.active = o.has("active") && o.get("active").getAsBoolean();
			s.freeBuild = !o.has("freeBuild") || o.get("freeBuild").getAsBoolean();
			s.defend = !o.has("defend") || o.get("defend").getAsBoolean();
			s.invincible = o.has("invincible") && o.get("invincible").getAsBoolean();
			s.mode = o.has("mode") ? o.get("mode").getAsString() : "FOLLOW";
			s.inventory = o.has("inventory") ? o.getAsJsonArray("inventory") : new JsonArray();
			return s;
		} catch (Exception e) {
			AICompanion.LOGGER.warn("Could not read saved companion {}", p, e);
			return null;
		}
	}

	/** Inventory -> JSON (every slot, including armor and offhand). */
	public static JsonArray writeInventory(MinecraftServer server, Inventory inv) {
		JsonArray arr = new JsonArray();
		RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) {
				continue;
			}
			final int slot = i;
			ItemStack.CODEC.encodeStart(ops, stack)
					.resultOrPartial(err -> AICompanion.LOGGER.warn("Could not save item: {}", err))
					.ifPresent(json -> {
						JsonObject entry = new JsonObject();
						entry.addProperty("slot", slot);
						entry.add("item", json);
						arr.add(entry);
					});
		}
		return arr;
	}

	/** JSON -> inventory. */
	public static void readInventory(MinecraftServer server, Inventory inv, JsonArray arr) {
		if (arr == null) {
			return;
		}
		RegistryOps<JsonElement> ops = server.registryAccess().createSerializationContext(JsonOps.INSTANCE);
		for (JsonElement el : arr) {
			try {
				JsonObject entry = el.getAsJsonObject();
				int slot = entry.get("slot").getAsInt();
				if (slot < 0 || slot >= inv.getContainerSize()) {
					continue;
				}
				ItemStack.CODEC.parse(ops, entry.get("item"))
						.resultOrPartial(err -> AICompanion.LOGGER.warn("Could not load item: {}", err))
						.ifPresent(stack -> inv.setItem(slot, stack));
			} catch (Exception e) {
				AICompanion.LOGGER.warn("Skipping a broken saved item", e);
			}
		}
	}
}
