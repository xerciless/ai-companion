package com.aicompanion;

import com.aicompanion.chat.ChatBrain;
import com.aicompanion.command.CompanionCommand;
import com.aicompanion.data.CompanionConfig;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AI Companion - a buddy that joins your world as a real player.
 *
 * Server side (works in singleplayer, LAN and on servers that have the mod):
 *  - the companion is a real (fake) ServerPlayer, so it walks, jumps, swims, takes damage,
 *    picks up items, breaks and places blocks exactly like a player does
 *  - it listens to chat and answers (optionally using Claude for real conversation)
 *  - everything can be controlled with /companion ... (the Down Arrow menu sends these commands)
 */
public final class AICompanion implements ModInitializer {
	public static final String MOD_ID = "aicompanion";
	public static final Logger LOGGER = LoggerFactory.getLogger("AI Companion");

	@Override
	public void onInitialize() {
		CompanionConfig.load();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				CompanionCommand.register(dispatcher));

		ServerTickEvents.END_SERVER_TICK.register(CompanionManager::tick);

		ServerLifecycleEvents.SERVER_STARTED.register(CompanionManager::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(CompanionManager::onServerStopping);

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				CompanionManager.onPlayerJoin(handler.getPlayer()));

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				CompanionManager.onPlayerLeave(handler.getPlayer()));

		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) ->
				ChatBrain.onPlayerChat(sender, message.signedContent()));

		LOGGER.info("AI Companion loaded - press the Down Arrow in game to open the companion menu.");
	}
}
