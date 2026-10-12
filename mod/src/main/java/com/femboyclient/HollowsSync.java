package com.femboyclient;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MansionFinder: connects the Crystal Hollows scanner to the game and the corner map. Nothing goes to or comes from the website.
 *
 * The moment the game says you are in the Crystal Hollows, the scanner starts on the loaded chunks. A Mansion is told by what it is
 * made of ({@link MansionInfo#MANSION}, built in), so it goes straight onto the corner map as soon as its chunks have loaded, and a
 * line in chat tells you where it is.
 *
 * Client thread only.
 */
final class HollowsSync {

	/**
	 * What a Mansion is made of: kinds of block it always has together and few other chunks do, how many of them a spot must hold, and
	 * kinds that only other builds have (one of them means it is not a Mansion, however much of the rest is there).
	 */
	record MansionInfo(Set<String> distinctive, int min, Set<String> exclude) {

		/**
		 * The Mansion, as learned from many scans and confirmed by hand (the website's variant 953 on 2026-10-12). Every Mansion found
		 * so far fits it and no other grotto does.
		 */
		static final MansionInfo MANSION = new MansionInfo(
				Set.of("allium", "azure_bluet", "cracked_stone_bricks", "lilac", "mossy_stone_bricks"), 5,
				Set.of("brown_wool", "cyan_wool", "light_gray_stained_glass", "light_weighted_pressure_plate", "lily_pad", "lime_wool",
						"mossy_cobblestone", "mossy_cobblestone_wall", "orange_wool", "peony", "pink_tulip", "purple_wool",
						"white_stained_glass", "white_terracotta"));

		/**
		 * Every kind that says something either way. A grotto found by its jasper is reported with only these of its kinds, so the
		 * kinds of other builds must be among them too, or {@link #matches} could not see them.
		 */
		Set<String> telltale() {
			Set<String> all = new HashSet<>(distinctive);
			all.addAll(exclude);
			return Set.copyOf(all);
		}

		/** All the core kinds (that is what min is) and none of the kinds that show it is another build. */
		boolean matches(Set<String> kinds) {
			return min > 0 && kinds.stream().filter(distinctive::contains).count() >= min
					&& kinds.stream().noneMatch(exclude::contains);
		}
	}

	private final HollowsTracker tracker;
	private final MansionFinder scanner;
	private final MinecraftChunksHolder chunks = new MinecraftChunksHolder();
	private boolean inside;
	private final MansionInfo mansion = MansionInfo.MANSION;
	private final Map<Integer, HollowsMapModel.Structure> local = new LinkedHashMap<>();
	private final Set<Integer> announced = new HashSet<>();

	/** Keeps one reader for the game's chunks instead of making one every tick. */
	private static final class MinecraftChunksHolder {
		private Minecraft mc;
		private MinecraftChunks reader;

		MinecraftChunks of(Minecraft next) {
			if (reader == null || mc != next) {
				mc = next;
				reader = new MinecraftChunks(next);
			}
			return reader;
		}
	}

	HollowsSync(HollowsTracker tracker, MansionFinder scanner) {
		this.tracker = tracker;
		this.scanner = scanner;
		scanner.setDistinctive(mansion.telltale());
		scanner.setMansionCore(mansion.distinctive(), mansion.min(), mansion.exclude());
	}

	/** True while the scanner is running, so the game's chunk-loaded event can hand chunks over. */
	boolean scanning() {
		return inside;
	}

	void tick(Minecraft mc) {
		boolean here = tracker.inHollowsWorld() && tracker.level() == mc.level && mc.player != null && mc.level != null;
		if (!here) {
			// Not in the Hollows, or a hop happened and the tracker has not yet read the new world: nothing may be scanned, or the new
			// world's finds would be drawn in the lobby that was just left.
			if (inside) {
				inside = false;
				scanner.reset();
				local.clear();
				announced.clear();
				HollowsHud.setLocal(List.of());
				restoreRenderDistance(mc);
			}
			return;
		}

		if (!inside) {
			inside = true;
			raiseRenderDistance(mc);
		}
		scanner.useLobby(tracker.lobbyKey());
		scanner.tick(chunks.of(mc), mc.player.getBlockX(), mc.player.getBlockZ(), this::onFound);
	}

	/** A structure was recognised from its blocks: a Mansion goes onto the map at once, with a line in chat. Anything else is left out. */
	private void onFound(String structure, int id, int[] box, int jasper, Set<String> kinds) {
		if (!MansionFinder.GROTTO.equals(structure) || !mansion.matches(kinds)) {
			return;
		}
		int cx = (box[0] + box[3]) / 2, cy = (box[1] + box[4]) / 2, cz = (box[2] + box[5]) / 2;
		local.put(id, new HollowsMapModel.Structure(structure, id, cx, cy, cz, box[3] - box[0], box[4] - box[1], box[5] - box[2], jasper,
				false, null, true, true));
		HollowsHud.setLocal(new ArrayList<>(local.values()));

		if (announced.add(id)) {
			Chat.notice("Mansion grotto found at " + cx + ", " + cy + ", " + cz + " (" + jasper + " jasper)");
		}
	}

	/** The view distance to ask for while scanning: the most the game offers. The server still decides how much it really sends. */
	static final int SCAN_RENDER_DISTANCE = 32;

	private Integer savedRenderDistance;

	/**
	 * Asks for the biggest view distance while you are in the Hollows, so the server sends every chunk it is willing to and each one is
	 * scanned the moment it arrives. It goes back to what you had when you leave. Off with scanRenderBoost=false in the config.
	 */
	private void raiseRenderDistance(Minecraft mc) {
		try {
			if (!FemboyClientConfig.get().scanRenderBoost || savedRenderDistance != null) {
				return;
			}
			int now = mc.options.renderDistance().get();
			if (now < SCAN_RENDER_DISTANCE) {
				savedRenderDistance = now;
				mc.options.renderDistance().set(SCAN_RENDER_DISTANCE);
			}
		} catch (RuntimeException e) {
			savedRenderDistance = null;                   // the scan works without it
		}
	}

	private void restoreRenderDistance(Minecraft mc) {
		try {
			if (savedRenderDistance != null) {
				mc.options.renderDistance().set(savedRenderDistance);
			}
		} catch (RuntimeException e) {
			// nothing to restore to
		} finally {
			savedRenderDistance = null;
		}
	}
}
