package com.aicompanion.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;

/** Client side: the Down Arrow key opens the companion menu. */
public final class AICompanionClient implements ClientModInitializer {
	private static KeyMapping openMenu;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("aicompanion", "main"));
		openMenu = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.aicompanion.menu",
				InputConstants.Type.KEYBOARD,
				InputConstants.KEY_DOWN, // the Down Arrow (you can change it in Options > Controls)
				category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openMenu.consumeClick()) {
				if (client.player != null) {
					client.gui.setScreen(new CompanionScreen());
				}
			}
		});
	}
}
