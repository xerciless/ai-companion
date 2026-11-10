package com.aicompanion.entity;

import com.aicompanion.AICompanion;
import com.aicompanion.brain.CompanionBrain;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;

/**
 * The companion's body: a real server-side player that has no game client.
 *
 * Fabric's FakePlayer takes care of the fake network connection. We add the parts a
 * plain FakePlayer skips so the companion actually lives in the world: ticking, physics,
 * taking damage and showing up in the tab list.
 */
public class CompanionPlayer extends FakePlayer {
	private CompanionBrain brain;
	private int errorCooldown;

	public CompanionPlayer(ServerLevel level, GameProfile profile) {
		super(level, profile);
	}

	public void setBrain(CompanionBrain brain) {
		this.brain = brain;
	}

	public CompanionBrain getBrain() {
		return brain;
	}

	@Override
	public void tick() {
		if (this.isRemoved()) {
			return;
		}
		if (errorCooldown > 0) {
			errorCooldown--;
		}

		// 1) Think: decide what to do this tick (presses movement "keys", attacks, mines, places...)
		if (brain != null && this.isAlive()) {
			try {
				brain.tick();
			} catch (Exception e) {
				if (errorCooldown == 0) {
					AICompanion.LOGGER.error("Companion brain hit an error, recovering", e);
					errorCooldown = 200;
				}
				brain.recoverFromError();
			}
		}

		// 2) The bits of ServerPlayer#tick that matter without a client
		try {
			this.gameMode.tick();
		} catch (Exception ignored) {
			// nothing important happens here for a companion
		}
		if (this.invulnerableTime > 0) {
			this.invulnerableTime--;
		}

		// 3) The normal player body tick: walking, gravity, swimming, item pickup, hunger, healing...
		// For real players the network connection calls this; the companion has none, so we do.
		try {
			this.doTick();
		} catch (Exception e) {
			if (errorCooldown == 0) {
				AICompanion.LOGGER.error("Companion body tick failed", e);
				errorCooldown = 200;
			}
		}

		// 4) Keep chunk loading / tracking in sync with where the companion walked to.
		if (this.tickCount % 10 == 0 && !this.isRemoved()) {
			this.level().getChunkSource().move(this);
		}
	}

	/**
	 * Real players are moved by their own game client. The companion has no client,
	 * so the server simulates its movement (like it does for mobs).
	 */
	@Override
	public boolean isClientAuthoritative() {
		return false;
	}

	@Override
	public boolean canSimulateMovement() {
		return true;
	}

	/** FakePlayer can't be hurt by default; the companion can (unless Invincible is on). */
	@Override
	public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
		if (this.isRemoved()) {
			return true;
		}
		if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			return false;
		}
		if (brain != null && brain.invincible) {
			return true;
		}
		return this.isInvulnerable() || this.getAbilities().invulnerable;
	}

	/** Show the companion in the tab (player) list. */
	@Override
	public boolean allowsListing() {
		return true;
	}
}
