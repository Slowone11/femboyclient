package com.femboyclient;

/**
 * The player scanner's on/off switch and world filter, set from the Scanner module in the G menu ({@link ClientMenuScreen}).
 * Reporting itself lives in {@link PlayerTracker}; this class only changes the setting and tells the website about it.
 */
final class Scanner {

	private Scanner() {
	}

	/** Turns the scanner on or off, restricted to one SkyBlock world or every world, and tells the website. */
	static void apply(FemboyClientConfig config, boolean on, String world) {
		config.scanner = on;
		config.scannerWorld = world;
		config.save();
		FemboyClientMod.SERVER.setScanner(describe(config));
	}

	/** "ON (world: Hub)", "ON (all worlds)" or "OFF". */
	static String describe(FemboyClientConfig config) {
		if (!config.scanner) {
			return "OFF";
		}
		return config.scannerWorld.isEmpty() ? "ON (all worlds)" : "ON (world: " + config.scannerWorld + ")";
	}
}
