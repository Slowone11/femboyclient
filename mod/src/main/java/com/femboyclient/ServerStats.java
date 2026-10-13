package com.femboyclient;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Util;

/**
 * Ping and TPS, worked out like OdinClient 1.2.5.beta7's ServerUtils: TPS from how often the server sends its time (it does
 * every second, so 20 ticks apart), ping from the game's own ping to the server, both smoothed the way Odin smooths them.
 */
public final class ServerStats {

	private static long prevTime;
	private static double averageTps = 20, averagePing;
	private static ClientLevel level;

	private ServerStats() {
	}

	/** True when a module shows ping or TPS, so the game should keep pinging the server. */
	public static boolean wanted() {
		FemboyClientConfig config = FemboyClientConfig.get();
		return config.enabled && (config.serverDisplay || config.chatCommands);
	}

	public static double tps() {
		return averageTps;
	}

	public static double ping() {
		return averagePing;
	}

	/** Called every client tick: a new world starts over. */
	public static void tick(net.minecraft.client.Minecraft mc) {
		if (mc.level != level) {
			level = mc.level;
			prevTime = 0;
			averageTps = 20;
			averagePing = 0;
		}
	}

	public static void onTimeUpdate() {
		long now = System.currentTimeMillis();
		if (prevTime != 0) {
			double tps = Math.max(0, Math.min(20, 20000.0 / (now - prevTime + 1)));
			averageTps = tps * 0.182 + averageTps * 0.818;
		}
		prevTime = now;
	}

	public static void onPong(long sentAt) {
		long ping = Math.max(0, Util.getMillis() - sentAt);
		averagePing = averagePing == 0 ? ping : ping * 0.4 + averagePing * 0.6;
	}
}
