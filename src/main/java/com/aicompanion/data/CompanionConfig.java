package com.aicompanion.data;

import com.aicompanion.AICompanion;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Settings stored in .minecraft/config/aicompanion.json
 */
public final class CompanionConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** Optional. Paste an Anthropic API key here to give your companion real AI conversations. */
	public String anthropicApiKey = "";
	/** Claude model used for chat when an API key is set. */
	public String model = "claude-haiku-5-5";
	/** Personality used for AI chat (and to flavour built-in replies). */
	public String personality = "cheerful, brave and a little bit goofy best friend who loves building and adventures";
	/** Name used when you summon a companion without typing a name. */
	public String defaultName = "Buddy";
	/** How many companions one player may have at the same time. */
	public int maxCompanionsPerPlayer = 3;
	/** If true, companions build structures without needing the blocks in their inventory. */
	public boolean freeBuildByDefault = true;
	/** If true, companions fight hostile mobs near you by default. */
	public boolean defendByDefault = true;
	/** If true, companions occasionally say things on their own. */
	public boolean chatty = true;

	private static CompanionConfig instance = new CompanionConfig();

	public static CompanionConfig get() {
		return instance;
	}

	public boolean aiChatEnabled() {
		return anthropicApiKey != null && !anthropicApiKey.isBlank();
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("aicompanion.json");
	}

	public static void load() {
		Path p = path();
		try {
			if (Files.exists(p)) {
				try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
					CompanionConfig loaded = GSON.fromJson(r, CompanionConfig.class);
					if (loaded != null) {
						instance = loaded;
					}
				}
			}
			save(); // writes defaults / new fields
		} catch (Exception e) {
			AICompanion.LOGGER.warn("Could not read {}, using defaults", p, e);
		}
	}

	public static void save() {
		Path p = path();
		try {
			Files.createDirectories(p.getParent());
			try (Writer w = Files.newBufferedWriter(p, StandardCharsets.UTF_8)) {
				GSON.toJson(instance, w);
			}
		} catch (Exception e) {
			AICompanion.LOGGER.warn("Could not write {}", p, e);
		}
	}
}
