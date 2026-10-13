package com.femboyclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;

/**
 * Hide Players (a copy of OdinClient's): other players near you are not drawn. Hide All hides every player whatever the
 * distance; Click Through also lets your clicks go through them, to the mob or block behind. Only At Devices hides them only
 * while you stand at Simon Says or the 4th device in the Floor 7 boss. NPCs (which Hypixel makes as fake players) are never
 * hidden, and nothing changes in singleplayer. Only what you see and click changes: nothing is sent to the server.
 */
public final class HidePlayers {

	public static final HandMan.Range DISTANCE = new HandMan.Range(0, 320, 5);     // tenths of a block

	private HidePlayers() {
	}

	/** True if this entity should not be drawn. Called by the mixins for every entity each frame, so it stays cheap. */
	public static boolean hidden(Entity entity) {
		if (!(entity instanceof Player other) || entity instanceof LocalPlayer) {
			return false;
		}
		FemboyClientConfig config = FemboyClientConfig.get();
		if (!config.enabled || !config.hidePlayers) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer me = mc.player;
		if (me == null || mc.hasSingleplayerServer() || other.getUUID().version() != 4) {
			return false;                    // a real player's UUID is version 4; Hypixel's NPCs use version 2
		}
		if (config.hidePlayersOnlyDevs && !atDevice(me)) {
			return false;
		}
		return config.hidePlayersAll || other.distanceTo(me) <= config.hidePlayersDistance / 10.0;
	}

	/** True if your clicks should go through this entity. */
	public static boolean clickThrough(Entity entity) {
		return FemboyClientConfig.get().hidePlayersClickThrough && hidden(entity);
	}

	/** Standing at Simon Says or the 4th device (the arrows), in the Floor 7 boss. */
	private static boolean atDevice(LocalPlayer me) {
		return "Dungeons".equals(FemboyClientMod.PLAYER_TRACKER.knownWorld())
				&& (me.distanceToSqr(108.63, 120.0, 94.0) <= 1.8 * 1.8 || me.distanceToSqr(63.5, 127.0, 35.5) <= 1.8 * 1.8);
	}
}
