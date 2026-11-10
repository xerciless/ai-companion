package com.aicompanion.brain;

/** What the companion is currently busy with. */
public enum Mode {
	IDLE("stay", "waiting here"),
	FOLLOW("follow", "following you"),
	PLAY("play", "playing on my own"),
	GUARD("guard", "guarding you"),
	WOOD("wood", "chopping wood"),
	MINE("mine", "mining"),
	COLLECT("collect", "picking up items"),
	BUILD("build", "building");

	public final String id;
	public final String description;

	Mode(String id, String description) {
		this.id = id;
		this.description = description;
	}

	public static Mode byId(String id) {
		for (Mode m : values()) {
			if (m.id.equalsIgnoreCase(id) || m.name().equalsIgnoreCase(id)) {
				return m;
			}
		}
		return FOLLOW;
	}
}
