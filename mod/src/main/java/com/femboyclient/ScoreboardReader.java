package com.femboyclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Reads the sidebar scoreboard that the server has already sent to our client.
 *
 * This is purely passive: everything here comes from data the client is already
 * holding in memory. Nothing is sent to the server.
 */
public final class ScoreboardReader {

	private ScoreboardReader() {
	}

	/**
	 * A section sign plus whatever character follows it.
	 *
	 * We deliberately match ANY following character, not just the valid vanilla
	 * codes (0-9, a-f, k-o, r). Hypixel pads scoreboard lines with meaningless
	 * codes such as "§j" or "§v" so that every line is unique, and it drops them
	 * into the middle of text, e.g. "Piggy: 5,637,5§j75". Matching only the valid
	 * codes leaves those behind and breaks number parsing.
	 */
	private static final Pattern FORMAT_CODES = Pattern.compile("§.");

	/**
	 * A snapshot of the sidebar.
	 *
	 * @param title the heading above the sidebar (on SkyBlock this contains "SKYBLOCK")
	 * @param lines the sidebar rows, formatting codes already stripped
	 */
	public record Sidebar(String title, List<String> lines) {
	}

	/**
	 * Reads the current sidebar, or returns null if there is no world loaded
	 * or no objective is being displayed in the sidebar slot.
	 */
	public static Sidebar read(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level == null) {
			return null;
		}

		Scoreboard scoreboard = level.getScoreboard();
		Objective objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);
		if (objective == null) {
			return null;
		}

		String title = strip(objective.getDisplayName().getString());

		// Each sidebar row is a score entry. Servers like Hypixel put the visible
		// text in the row's team prefix/suffix, so we rebuild the line the same way
		// the vanilla HUD does: format the entry's name with its team.
		List<String> lines = new ArrayList<>();
		for (PlayerScoreEntry entry : scoreboard.listPlayerScores(objective)) {
			if (entry.isHidden()) {
				continue;
			}
			PlayerTeam team = scoreboard.getPlayersTeam(entry.owner());
			Component full = (team == null)
					? entry.ownerName()
					: PlayerTeam.formatNameForTeam(team, entry.ownerName());
			lines.add(strip(full.getString()));
		}

		return new Sidebar(title, lines);
	}

	/** Removes legacy formatting codes and trims surrounding whitespace. */
	public static String strip(String text) {
		return FORMAT_CODES.matcher(text).replaceAll("").trim();
	}
}
