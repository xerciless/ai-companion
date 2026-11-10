package com.aicompanion.chat;

import com.aicompanion.AICompanion;
import com.aicompanion.CompanionManager;
import com.aicompanion.brain.CompanionBrain;
import com.aicompanion.brain.Mode;
import com.aicompanion.data.CompanionConfig;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Everything the companion hears and says in chat. */
public final class CompanionChat {
	private static final Pattern ACTION_TAG = Pattern.compile("\\[ACTION:([a-z_:]+)]", Pattern.CASE_INSENSITIVE);

	private static final String[] JOKES = {
			"Why did the creeper cross the road? To get to the other ssssside!",
			"What's a skeleton's least favorite room? The living room.",
			"Why don't Endermen like parties? They always feel out of place... so they teleport.",
			"How does Steve stay fit? He runs around the block!",
			"What do you call a pig that does karate? A pork chop!",
			"Why did the zombie go to school? To improve his dead-ucation."
	};

	private record Pending(long at, String text) {
	}

	private final CompanionBrain brain;
	private final ArrayDeque<Pending> outbox = new ArrayDeque<>();
	private final List<JsonObject> history = new ArrayList<>();
	private boolean aiBusy;
	private boolean aiErrorSaid;
	private long nextChatter = 6000;
	private boolean nightSaid;
	private long hurtWarnCooldown;

	public CompanionChat(CompanionBrain brain) {
		this.brain = brain;
	}

	// ------------------------------------------------------------------ speaking

	/** Says something in chat right now, like a player would: <Name> text */
	public void say(String text) {
		if (text == null || text.isBlank()) {
			return;
		}
		MinecraftServer server = brain.server();
		if (server == null) {
			return;
		}
		for (String line : text.split("\n")) {
			if (!line.isBlank()) {
				server.getPlayerList().broadcastSystemMessage(Component.literal("<" + brain.name + "> " + line.trim()), false);
			}
		}
	}

	/** Says something after a short delay, so replies feel natural (people need a moment to type!). */
	public void sayLater(String text, int delayTicks) {
		if (text != null && !text.isBlank()) {
			outbox.add(new Pending(CompanionManager.serverTicks() + delayTicks, text));
		}
	}

	/** Called every server tick by the manager. */
	public void tick() {
		long now = CompanionManager.serverTicks();
		while (!outbox.isEmpty() && outbox.peek().at() <= now) {
			say(outbox.poll().text());
		}
	}

	// ------------------------------------------------------------------ hearing

	/** A real player said something that was meant for this companion. */
	public void hear(ServerPlayer from, String raw) {
		String text = raw.trim();
		String cleaned = text.replaceAll("(?i)\\b" + Pattern.quote(brain.name) + "\\b[,:]?", " ").replaceAll("^\\s*@", "").trim();
		String action = Intents.detect(cleaned);
		if (action != null) {
			String reply = brain.command(action, from);
			if (reply != null) {
				sayLater(reply, 12);
			}
			remember("user", from.getName().getString() + ": " + text);
			if (reply != null) {
				remember("assistant", reply);
			}
			return;
		}

		CompanionConfig cfg = CompanionConfig.get();
		if (cfg.aiChatEnabled()) {
			askAi(from, text);
		} else {
			sayLater(smallTalk(cleaned.toLowerCase(Locale.ROOT), from), 20 + brain.random.nextInt(20));
		}
	}

	private void remember(String role, String content) {
		// the API needs alternating user/assistant turns, starting with the user
		if (history.isEmpty() && !role.equals("user")) {
			return;
		}
		if (!history.isEmpty()) {
			JsonObject last = history.get(history.size() - 1);
			if (last.get("role").getAsString().equals(role)) {
				String merged = last.get("content").getAsString() + "\n" + content;
				history.set(history.size() - 1, ClaudeClient.message(role, merged));
				return;
			}
		}
		history.add(ClaudeClient.message(role, content));
		while (history.size() > 16) {
			history.remove(0);
			// keep it starting with a user message
			while (!history.isEmpty() && !history.get(0).get("role").getAsString().equals("user")) {
				history.remove(0);
			}
		}
	}

	private void askAi(ServerPlayer from, String text) {
		if (aiBusy) {
			sayLater(brain.pick("One sec, still thinking!", "Hang on..."), 5);
			return;
		}
		CompanionConfig cfg = CompanionConfig.get();
		MinecraftServer server = brain.server();
		if (server == null) {
			return;
		}
		remember("user", from.getName().getString() + ": " + text);
		List<JsonObject> messages = new ArrayList<>(history);
		if (messages.isEmpty() || !messages.get(messages.size() - 1).get("role").getAsString().equals("user")) {
			return;
		}
		aiBusy = true;
		String system = systemPrompt(cfg, from);
		ClaudeClient.ask(cfg.anthropicApiKey.trim(), cfg.model, system, messages).whenComplete((reply, error) ->
				server.execute(() -> {
					aiBusy = false;
					if (error != null || reply == null || reply.isEmpty()) {
						AICompanion.LOGGER.warn("AI chat failed: {}", error == null ? "empty reply" : error.getMessage());
						if (!aiErrorSaid) {
							aiErrorSaid = true;
							say("(My AI brain can't connect right now - check the API key in config/aicompanion.json. I'll use my normal brain.)");
						}
						say(smallTalk(text.toLowerCase(Locale.ROOT), from));
						return;
					}
					handleAiReply(reply, from);
				}));
	}

	private void handleAiReply(String reply, ServerPlayer from) {
		Matcher m = ACTION_TAG.matcher(reply);
		String action = null;
		if (m.find()) {
			action = m.group(1).toLowerCase(Locale.ROOT).replace("build_", "build:").replace("mine_", "mine:");
		}
		String spoken = ACTION_TAG.matcher(reply).replaceAll("").trim();
		if (spoken.length() > 256) {
			spoken = spoken.substring(0, 253) + "...";
		}
		remember("assistant", reply);
		if (!spoken.isEmpty()) {
			say(spoken);
		}
		if (action != null) {
			ServerPlayer owner = brain.owner();
			brain.command(action, owner != null ? owner : from);
		}
	}

	private String systemPrompt(CompanionConfig cfg, ServerPlayer from) {
		ServerPlayer owner = brain.owner();
		String ownerName = owner != null ? owner.getName().getString() : from.getName().getString();
		return "You are " + brain.name + ", an AI companion who plays Minecraft Java Edition as a real player alongside your friend "
				+ ownerName + ". Your personality: " + cfg.personality + ". "
				+ "You are chatting in the in-game chat, so reply in 1-2 short, casual sentences (under 200 characters), "
				+ "friendly and fun for all ages. No markdown, no emojis. "
				+ "You can really do things in the game. If (and only if) the player asks you to do something, add exactly ONE action tag "
				+ "at the very end of your reply, from this list: "
				+ "[ACTION:follow] [ACTION:stay] [ACTION:play] [ACTION:guard] [ACTION:wood] [ACTION:mine] "
				+ "[ACTION:mine_diamonds] [ACTION:mine_iron] [ACTION:mine_coal] [ACTION:collect] [ACTION:give] "
				+ "[ACTION:build_house] [ACTION:build_tower] [ACTION:build_wall] [ACTION:build_bridge] [ACTION:build_shelter] "
				+ "[ACTION:dance] [ACTION:jump] [ACTION:wave] [ACTION:leave]. "
				+ "Messages from players look like 'Name: message'. "
				+ "Game state right now: " + brain.situation();
	}

	// ------------------------------------------------------------------ built-in personality (no AI key needed)

	String smallTalk(String msg, ServerPlayer from) {
		String who = from.getName().getString();
		if (Intents.word(msg, "hi", "hello", "hey", "yo", "sup", "hiya", "howdy", "greetings")) {
			return brain.pick("Hey " + who + "! What should we do? I can build, mine, chop wood, fight mobs, or just hang out!",
					"Hi " + who + "! Ready for an adventure?", "Hello! Press the Down Arrow if you want to see everything I can do.");
		}
		if (Intents.word(msg, "how are you", "how r u", "hows it going", "how's it going", "you ok", "you good")) {
			return "I'm awesome! " + brain.statusLine();
		}
		if (Intents.word(msg, "thanks", "thank you", "thx", "ty")) {
			return brain.pick("Anytime!", "No problem!", "That's what friends are for!");
		}
		if (Intents.word(msg, "joke", "funny", "make me laugh")) {
			return JOKES[brain.random.nextInt(JOKES.length)];
		}
		if (Intents.word(msg, "who are you", "your name", "what are you")) {
			return "I'm " + brain.name + ", your AI companion! I play Minecraft just like you. Press the Down Arrow to see my menu.";
		}
		if (Intents.word(msg, "where are you", "where r u")) {
			if (brain.body() == null) {
				return "I'm taking a nap!";
			}
			return "I'm at " + brain.body().getBlockX() + ", " + brain.body().getBlockY() + ", " + brain.body().getBlockZ() + ".";
		}
		if (Intents.word(msg, "help", "what can you do", "commands")) {
			return "Try: follow me, stay, guard me, chop wood, mine (or mine diamonds), collect items, build a house/tower/wall/bridge/shelter, "
					+ "give me your stuff, dance, free play, or bye. Or press the Down Arrow for my menu!";
		}
		if (Intents.word(msg, "love you", "like you", "best friend", "you're cool", "youre cool", "you are cool")) {
			return brain.pick("Aww, you're the best teammate ever!", "Right back at you, " + who + "!");
		}
		if (Intents.word(msg, "good job", "nice", "awesome", "cool", "great", "amazing", "wow", "well done", "gg")) {
			return brain.pick("Thanks! We make a great team.", "Hehe, thanks!", "Teamwork!");
		}
		if (Intents.word(msg, "scared", "sad", "lonely", "afraid")) {
			return brain.pick("Don't worry, I'm right here with you!", "I've got your back, " + who + ". Always.");
		}
		if (Intents.word(msg, "hungry", "food")) {
			return "Same! Let's find some animals or make a farm.";
		}
		if (Intents.word(msg, "night", "dark", "monsters")) {
			return "Want me to build a shelter? Just say 'build a shelter'!";
		}
		if (msg.endsWith("?")) {
			return brain.pick("Hmm, good question! I'm not sure.", "No idea, but let's find out!", "Maybe? What do you think?");
		}
		return brain.pick("Haha, nice!", "Totally!", "Sounds good to me!", "Let's keep going!",
				"I'm not sure what that means, but I'm with you! Say 'help' to see what I can do.");
	}

	/** Comments on things happening around it, now and then. */
	public void idleChatter(ServerPlayer owner) {
		if (!CompanionConfig.get().chatty || brain.body() == null || owner == null) {
			return;
		}
		long now = CompanionManager.serverTicks();
		boolean dark = brain.body().level().isDarkOutside();
		if (dark && !nightSaid && owner.level() == brain.body().level()) {
			nightSaid = true;
			sayLater(brain.pick("It's getting dark... monsters will come out. Want me to build a shelter?",
					"Night time! Stay close, I'll protect you."), 20);
		} else if (!dark) {
			nightSaid = false;
		}
		if (owner.getHealth() <= 6 && now > hurtWarnCooldown) {
			hurtWarnCooldown = now + 1200;
			sayLater(brain.pick("Careful, you're hurt! Let's be safe.", "Watch out, your health is low!"), 10);
		}
		if (now > nextChatter) {
			nextChatter = now + 3600 + brain.random.nextInt(6000);
			if (brain.mode == Mode.PLAY || brain.mode == Mode.FOLLOW) {
				sayLater(brain.pick("This is fun!", "I love exploring with you.", "Should we build something?",
						"I wonder if there are diamonds around here...", "Nice day for an adventure!",
						"Tell me if you need anything!"), 0);
			}
		}
	}
}
