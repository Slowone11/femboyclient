package com.femboyclient;

import net.minecraft.client.Minecraft;

import java.util.Locale;

/**
 * Tells the website whether this account is in SkyBlock, for the "In SkyBlock" dot on the page and the account the Player Finder
 * picks. Checked once a second from the sidebar, whose heading contains "SKYBLOCK" on SkyBlock. Warping between islands clears the
 * sidebar for a moment, so it only counts as left after 30 seconds without it.
 */
final class SkyBlockStatus {

	/** The client ticks 20 times a second, so 20 ticks is one check per second. */
	private static final int TICKS_PER_CHECK = 20;

	/** One-second checks without a SkyBlock sidebar before it counts as left. */
	private static final int LEAVE_AFTER_CHECKS = 30;

	private int tickCounter;
	private int checksWithout;
	private boolean inSkyBlock;

	/** Called every client tick; looks once per second. */
	void onClientTick(Minecraft mc) {
		if (++tickCounter < TICKS_PER_CHECK) {
			return;
		}
		tickCounter = 0;

		if (mc.player == null) {
			set(false);
			return;
		}
		ScoreboardReader.Sidebar sidebar = ScoreboardReader.read(mc);
		if (sidebar != null && sidebar.title().toUpperCase(Locale.ROOT).contains("SKYBLOCK")) {
			checksWithout = 0;
			set(true);
		} else if (inSkyBlock && ++checksWithout >= LEAVE_AFTER_CHECKS) {
			set(false);
		}
	}

	/** Called when the client disconnects from a server. */
	void onDisconnect() {
		set(false);
	}

	private void set(boolean value) {
		if (value != inSkyBlock) {
			inSkyBlock = value;
			checksWithout = 0;
			FemboyClientMod.SERVER.setStatus(value);
		}
	}
}
