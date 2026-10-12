package com.femboyclient;

/**
 * Reads a Hypixel NPC dialog line and works out which bracketed option to click.
 *
 * With one option there is nothing to decide: it is always clicked. With two or more, Hypixel colors the option that
 * continues the dialog green and the rest a different color, so that one is clicked; if none of them is green, nothing is
 * sent and the player is left to click it themselves rather than the mod guessing.
 *
 * Kept free of Minecraft classes so it can be tested on its own. The actual clicking (working out the option's underlying
 * command from its click event, and sending it) is {@link NpcAutoAnswer}'s job; this class only decides which label to use.
 */
final class NpcDialog {

	/** Hypixel's own marker for a choice prompt, usually at the very start of the line, once color codes are stripped. */
	private static final String MARKER = "Select an option:";

	/** Hypixel's own tag on every line an NPC speaks, e.g. "[NPC] Bank Teller: ...". Also used to spot dialog lines. */
	private static final String NPC_TAG = "[NPC]";

	/** One offered option, with whether it is colored green. */
	record Offer(String label, boolean green) {
	}

	private NpcDialog() {
	}

	/**
	 * True for a line worth checking for clickable options: either Hypixel's "Select an option:" prompt, or any line an NPC
	 * spoke (tagged "[NPC]"), once §-codes are already stripped (see ScoreboardReader.strip). Ordinary "[NPC]" lines with no
	 * clickable option in them are harmless to check — {@link NpcAutoAnswer} only acts when it actually finds one.
	 */
	static boolean isDialogLine(String plainText) {
		if (plainText == null) {
			return false;
		}
		String trimmed = plainText.trim();
		return trimmed.startsWith(MARKER) || trimmed.contains(NPC_TAG);
	}

	/**
	 * Which of the offers to click, or null to click nothing (and let the player choose by hand): the only one, if there is
	 * only one; otherwise the first one colored green; otherwise null.
	 */
	static String pick(java.util.List<Offer> offers) {
		if (offers.size() == 1) {
			return offers.get(0).label();
		}
		return offers.stream().filter(Offer::green).map(Offer::label).findFirst().orElse(null);
	}

	/** True for a click event value the game would run as a command ("/warp foo"), rather than send as plain chat. */
	static boolean isCommand(String value) {
		return value != null && value.startsWith("/");
	}

	/** The command without its leading slash, ready for {@code ClientPacketListener.sendCommand}. Call only when isCommand. */
	static String stripSlash(String value) {
		return value.substring(1);
	}
}
