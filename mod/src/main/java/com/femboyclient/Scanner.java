package com.femboyclient;

/**
 * The player scanner's status, shown on the website next to the account. The scanner has no switch: it always reports the
 * players in your lobby and what players say, in every world, while the mod is enabled. Reporting itself lives in
 * {@link PlayerTracker} and {@link PlayerChatCollector}.
 */
final class Scanner {

	private Scanner() {
	}

	/** "ON (all worlds)", or "OFF" when the whole mod is switched off with enabled=false. */
	static String describe(FemboyClientConfig config) {
		return config.enabled ? "ON (all worlds)" : "OFF";
	}
}
