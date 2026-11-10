package com.aicompanion.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The companion menu (opened with the Down Arrow).
 * Every button just sends a /companion command, so it works in singleplayer and on servers with the mod.
 */
public class CompanionScreen extends Screen {
	private static String lastName = "Buddy";

	private EditBox nameBox;

	public CompanionScreen() {
		super(Component.literal("AI Companion"));
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int left = cx - 155;
		int y = Math.max(8, this.height / 2 - 118);

		Button header = Button.builder(Component.literal("AI Companion - you can also just talk to me in chat!"), b -> {
		}).bounds(left, y, 310, 20).build();
		header.active = false;
		this.addRenderableWidget(header);
		y += 24;

		this.nameBox = new EditBox(this.font, left, y, 150, 20, Component.literal("Companion name"));
		this.nameBox.setMaxLength(16);
		this.nameBox.setValue(lastName);
		this.nameBox.setHint(Component.literal("Name"));
		this.addRenderableWidget(this.nameBox);
		this.addRenderableWidget(Button.builder(Component.literal("Join World"), b -> send("summon", true))
				.bounds(cx + 5, y, 150, 20).build());
		y += 26;

		String[][] actions = {
				{"Follow Me", "follow"}, {"Stay Here", "stay"},
				{"Free Play (plays on its own)", "play"}, {"Guard Me", "guard"},
				{"Chop Wood", "wood"}, {"Go Mining", "mine"},
				{"Collect Items", "collect"}, {"Give Me Items", "give"},
		};
		for (int i = 0; i < actions.length; i++) {
			final String cmd = actions[i][1];
			int x = left + (i % 2) * 160;
			int row = y + (i / 2) * 24;
			this.addRenderableWidget(Button.builder(Component.literal(actions[i][0]), b -> send(cmd, true))
					.bounds(x, row, 150, 20).build());
		}
		y += (actions.length / 2) * 24 + 4;

		Button buildLabel = Button.builder(Component.literal("Build:"), b -> {
		}).bounds(left, y, 40, 20).build();
		buildLabel.active = false;
		this.addRenderableWidget(buildLabel);
		String[] builds = {"House", "Tower", "Wall", "Bridge", "Shelter"};
		for (int i = 0; i < builds.length; i++) {
			final String kind = builds[i].toLowerCase();
			this.addRenderableWidget(Button.builder(Component.literal(builds[i]), b -> send("build " + kind, true))
					.bounds(left + 44 + i * 54, y, 52, 20).build());
		}
		y += 28;

		this.addRenderableWidget(Button.builder(Component.literal("Free Blocks: toggle"), b -> send("free", false))
				.bounds(left, y, 100, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Invincible: toggle"), b -> send("invincible", false))
				.bounds(left + 105, y, 100, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Dance!"), b -> send("dance", true))
				.bounds(left + 210, y, 100, 20).build());
		y += 24;

		this.addRenderableWidget(Button.builder(Component.literal("Leave World"), b -> send("dismiss", true))
				.bounds(left, y, 150, 20).build());
		this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
				.bounds(cx + 5, y, 150, 20).build());
	}

	private void send(String command, boolean close) {
		String name = this.nameBox == null ? "" : this.nameBox.getValue().replaceAll("[^A-Za-z0-9_]", "");
		if (!name.isEmpty()) {
			lastName = name;
		}
		String full = "companion " + command + (name.isEmpty() ? "" : " " + name);
		if (this.minecraft != null && this.minecraft.player != null) {
			this.minecraft.player.connection.sendCommand(full);
		}
		if (close) {
			this.onClose();
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
