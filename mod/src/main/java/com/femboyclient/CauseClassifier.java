package com.femboyclient;

import java.util.List;
import java.util.Locale;

/**
 * Works out why the purse changed, by matching the chat messages from the last
 * few seconds against a list of known phrases.
 *
 * TO ADD YOUR OWN PATTERNS: edit the RULES list below. Each rule is a label plus
 * the phrases that identify it. Matching is case-insensitive "contains", so
 * "you sold" matches "You sold 3x Enchanted Bone for 1,234 coins!".
 * Rules are checked in order, so put the most specific ones first.
 */
public final class CauseClassifier {

	private CauseClassifier() {
	}

	/** A label plus the phrases that identify it. */
	public record Rule(String label, List<String> phrases) {
	}

	/** ---- EDIT THIS LIST TO ADD MORE PATTERNS ---- */
	public static final List<Rule> RULES = List.of(
			// Bazaar. Hypixel prefixes these with "[Bazaar]".
			new Rule("bazaar", List.of(
					"[bazaar]",
					"sell offer",
					"buy order",
					"claimed")),

			// Auction house.
			new Rule("auction", List.of(
					"auction",
					"collected")),

			// Direct sales and purchases, including NPC shops.
			new Rule("sell", List.of(
					"you sold")),

			new Rule("buy", List.of(
					"you bought",
					"you purchased")),

			new Rule("npc_shop", List.of(
					"npc",
					"shop")),

			// Quests and objectives.
			new Rule("quest", List.of(
					"quest complete",
					"objective complete",
					"quest reward")),

			// Coins picked up from mobs and drops.
			new Rule("mob_drop", List.of(
					"coin purse",
					"you found",
					"dropped"))
	);

	/**
	 * ---- EDIT THIS LIST TOO ----
	 *
	 * Messages containing any of these are skipped entirely when looking for a
	 * cause. They are either status chatter with no amount in them, or notices
	 * Hypixel repeats on a timer that would otherwise get blamed for whatever
	 * happened to change at that moment.
	 */
	public static final List<String> IGNORED = List.of(
			"executing instant",   // "[Bazaar] Executing instant sell..." - status, not a result
			"to be claimed",       // "You have 12 pending Bestiary Milestones to be claimed!"
			"pending",
			"click here",
			"stashed away",
			"totals",
			"welcome to",
			"sending to server",
			"latest update");

	/** Label used when nothing in the window matched a rule. */
	public static final String UNKNOWN = "unknown";

	/**
	 * The result of classifying a purse change.
	 *
	 * @param label   one of the rule labels above, or "unknown"
	 * @param message the chat line we blamed, or an empty string if there was none
	 */
	public record Result(String label, String message) {
	}

	/**
	 * Picks the most likely cause from the buffered messages.
	 *
	 * Searches newest first, because the message that caused the change is almost
	 * always the last thing to arrive before we noticed it.
	 */
	public static Result classify(List<ChatMessage> messages) {
		for (int i = messages.size() - 1; i >= 0; i--) {
			String text = messages.get(i).text();
			String lower = text.toLowerCase(Locale.ROOT);

			if (isIgnored(lower)) {
				continue;
			}

			for (Rule rule : RULES) {
				for (String phrase : rule.phrases()) {
					if (lower.contains(phrase)) {
						return new Result(rule.label(), text);
					}
				}
			}
		}

		// Nothing matched. Keep the newest non-ignored message so there is some
		// context, but do not blame a line we already decided is noise.
		for (int i = messages.size() - 1; i >= 0; i--) {
			String text = messages.get(i).text();
			if (!isIgnored(text.toLowerCase(Locale.ROOT))) {
				return new Result(UNKNOWN, text);
			}
		}

		return new Result(UNKNOWN, "");
	}

	/** True if the message is on the IGNORED list. */
	private static boolean isIgnored(String lowerText) {
		for (String phrase : IGNORED) {
			if (lowerText.contains(phrase)) {
				return true;
			}
		}
		return false;
	}
}
