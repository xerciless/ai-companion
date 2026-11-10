package com.aicompanion.chat;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Understands what you type in chat ("follow me", "build a house", "go find diamonds"...)
 * and turns it into a companion action. Works without any AI key.
 */
final class Intents {
	private Intents() {
	}

	static boolean word(String msg, String... words) {
		for (String w : words) {
			if (w.contains(" ")) {
				if (msg.contains(w)) {
					return true;
				}
			} else if (Pattern.compile("(^|[^a-z])" + Pattern.quote(w) + "($|[^a-z])").matcher(msg).find()) {
				return true;
			}
		}
		return false;
	}

	/** Returns an action string for CompanionBrain#command, or null if this isn't a request. */
	static String detect(String raw) {
		String msg = raw.toLowerCase(Locale.ROOT).replaceAll("[!?.,]", " ").trim();
		if (msg.isEmpty()) {
			return null;
		}
		if (word(msg, "where are you", "where r u")) {
			return null; // small talk handles it
		}
		if (word(msg, "stop", "stay", "wait", "halt", "freeze", "don't move", "dont move", "stand still", "hold on", "stay here")) {
			return "stay";
		}
		if (word(msg, "build", "make me", "make a", "make us", "construct")) {
			String[][] kinds = {
					{"house", "house"}, {"home", "house"}, {"base", "house"}, {"cabin", "house"},
					{"tower", "tower"}, {"castle", "tower"},
					{"wall", "wall"}, {"fort", "wall"},
					{"bridge", "bridge"}, {"path", "bridge"},
					{"shelter", "shelter"}, {"hut", "shelter"}, {"bunker", "shelter"}
			};
			for (String[] k : kinds) {
				if (word(msg, k[0], k[0] + "s")) {
					return "build:" + k[1];
				}
			}
			if (word(msg, "build")) {
				return "build:?";
			}
		}
		if (word(msg, "dance")) {
			return "dance";
		}
		if (word(msg, "jump")) {
			return "jump";
		}
		if (word(msg, "wave")) {
			return "wave";
		}
		if (word(msg, "give me", "give", "drop", "hand over", "toss")) {
			String[] things = {"everything", "all", "wood", "logs", "planks", "stone", "cobblestone", "coal", "iron", "gold",
					"diamonds", "diamond", "emerald", "dirt", "food", "ores", "redstone", "lapis", "copper", "sapling"};
			for (String t : things) {
				if (word(msg, t)) {
					return "give:" + t;
				}
			}
			return "give";
		}
		if (word(msg, "inventory", "what do you have", "what have you got", "whats in your", "what's in your", "your stuff")) {
			return "inventory";
		}
		if (word(msg, "chop", "wood", "tree", "trees", "log", "logs", "lumber", "timber")) {
			return "wood";
		}
		if (word(msg, "mine", "mining", "ore", "ores", "dig", "digging", "diamond", "diamonds", "iron", "coal", "cave", "caves")) {
			String[] ores = {"diamonds", "diamond", "iron", "coal", "gold", "copper", "redstone", "lapis", "emerald", "emeralds"};
			for (String o : ores) {
				if (word(msg, o)) {
					return "mine:" + o;
				}
			}
			return "mine";
		}
		if (word(msg, "guard", "protect", "defend", "kill", "fight", "attack", "help me", "save me", "bodyguard")) {
			return "guard";
		}
		if (word(msg, "collect", "pick up", "pickup", "grab the", "loot")) {
			return "collect";
		}
		if (word(msg, "free play", "play", "explore", "have fun", "do your thing", "do whatever", "adventure")) {
			return "play";
		}
		if (word(msg, "follow", "come", "come here", "over here", "lets go", "let's go", "with me", "this way")) {
			return "follow";
		}
		if (word(msg, "leave", "bye", "goodbye", "good bye", "go away", "log off", "logoff", "dismiss", "see you", "see ya")) {
			return "leave";
		}
		if (word(msg, "status", "what are you doing", "whatcha doing", "wyd")) {
			return "status";
		}
		return null;
	}
}
