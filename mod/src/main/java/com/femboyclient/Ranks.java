package com.femboyclient;

/**
 * Turns the rank string the website sends ("MVP+", "YOUTUBER", "none", ...) into the badge the screen shows: its text and
 * which color style it uses. Kept free of Minecraft classes so it can be tested on its own.
 */
public final class Ranks {

	/** The badge color styles. Each has a fill and a text color in {@link Theme}. */
	public enum Style { GREEN, AQUA, GOLD, RED, BLUE, DARK_GREEN }

	/** What is drawn for a rank: the short text in the badge and its color style. */
	public record Badge(String text, Style style) {
	}

	private Ranks() {
	}

	/** The badge for a rank string, or null when the player has no rank or it is not known. */
	public static Badge badge(String rank) {
		if (rank == null) {
			return null;
		}
		return switch (rank) {
			case "VIP" -> new Badge("VIP", Style.GREEN);
			case "VIP+" -> new Badge("VIP+", Style.GREEN);
			case "MVP" -> new Badge("MVP", Style.AQUA);
			case "MVP+" -> new Badge("MVP+", Style.AQUA);
			case "MVP++" -> new Badge("MVP++", Style.GOLD);
			case "YOUTUBER" -> new Badge("YOUTUBE", Style.RED);
			case "HELPER" -> new Badge("HELPER", Style.BLUE);
			case "MODERATOR" -> new Badge("MOD", Style.DARK_GREEN);
			case "ADMIN" -> new Badge("ADMIN", Style.RED);
			default -> null;                        // "none", null and anything new: no badge
		};
	}
}
