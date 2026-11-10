package com.aicompanion.chat;

import com.aicompanion.CompanionManager;
import com.aicompanion.brain.CompanionBrain;
import com.aicompanion.entity.CompanionPlayer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides which companion a chat message is meant for.
 *  - If the message contains a companion's name, that companion answers.
 *  - Otherwise, if you're the only real player online, your own companion answers.
 *  - With friends online, say the companion's name so it knows you mean it.
 */
public final class ChatBrain {
	private ChatBrain() {
	}

	public static void onPlayerChat(ServerPlayer sender, String text) {
		if (sender == null || sender instanceof CompanionPlayer || text == null || text.isBlank() || text.startsWith("/")) {
			return;
		}
		List<CompanionBrain> all = CompanionManager.all();
		if (all.isEmpty()) {
			return;
		}
		String lower = text.toLowerCase(Locale.ROOT);
		List<CompanionBrain> named = new ArrayList<>();
		for (CompanionBrain b : all) {
			if (Pattern.compile("(^|[^a-z0-9_])" + Pattern.quote(b.name.toLowerCase(Locale.ROOT)) + "($|[^a-z0-9_])").matcher(lower).find()) {
				named.add(b);
			}
		}
		if (!named.isEmpty()) {
			for (CompanionBrain b : named) {
				b.chat().hear(sender, text);
			}
			return;
		}
		boolean everyoneCalled = lower.startsWith("everyone") || lower.startsWith("guys") || lower.startsWith("all of you");
		List<CompanionBrain> mine = CompanionManager.ownedBy(sender);
		if (mine.isEmpty()) {
			return;
		}
		if (CompanionManager.realPlayerCount(sender.level().getServer()) > 1 && !everyoneCalled) {
			return; // talking to friends - say the companion's name to talk to it
		}
		if (everyoneCalled || mine.size() == 1) {
			for (CompanionBrain b : mine) {
				b.chat().hear(sender, text);
			}
		} else {
			// several companions: the closest one answers
			CompanionBrain closest = null;
			double best = Double.MAX_VALUE;
			for (CompanionBrain b : mine) {
				if (b.hasBody() && b.body().level() == sender.level()) {
					double d = b.body().distanceToSqr(sender);
					if (d < best) {
						best = d;
						closest = b;
					}
				}
			}
			if (closest == null) {
				closest = mine.get(0);
			}
			closest.chat().hear(sender, text);
		}
	}
}
