package com.femboyclient;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Keeps the mod's copy of the website's friendly/enemy tags fresh, so the lobby overlay ({@link FrienemiesHud})
 * and the G menu screen ({@link FrienemiesScreen}) never wait on the network. Client thread only, except the
 * replies from the website, which only hand data over.
 */
final class FrienemiesSync {

	/** How often the list is re-fetched, in client ticks (20 a second). */
	static final int POLL_TICKS = 20 * 15;

	private static volatile List<Frienemy> all = List.of();
	private static int ticks;

	private FrienemiesSync() {
	}

	/** Every tagged player, as of the last successful fetch or edit. Any thread. */
	static List<Frienemy> all() {
		return all;
	}

	/** Called every client tick. Refetches the whole list every {@link #POLL_TICKS}, starting with the first tick. */
	static void tick() {
		if (ticks++ % POLL_TICKS == 0) {
			refresh();
		}
	}

	/** Fetches the whole list from the website. A manual tag or untag updates the list itself (see below) instead of
	 * waiting on this, so their own screen sees the change at once rather than on the next poll. */
	static void refresh() {
		FemboyClientMod.SERVER.frienemies().thenAccept(response -> {
			if (response.ok()) {
				all = Frienemy.parseList(response.body());
			}
		});
	}

	/**
	 * Tags a player. The website's answer is the saved row, so on success it replaces or adds that entry in the
	 * local list directly rather than asking for the whole list again.
	 */
	static CompletableFuture<WebClient.Response> tag(String name, UUID uuid, String tagWord, String notes) {
		return FemboyClientMod.SERVER.tagFrienemy(name, uuid, tagWord, notes).thenApply(response -> {
			if (response.ok()) {
				Frienemy saved = Frienemy.parseOne(response.body());
				if (saved != null) {
					upsert(saved);
				}
			}
			return response;
		});
	}

	/** Untags a player: on success removes them from the local list directly, same reasoning as {@link #tag}. */
	static CompletableFuture<WebClient.Response> untag(int playerId) {
		return FemboyClientMod.SERVER.untagFrienemy(playerId).thenApply(response -> {
			if (response.ok()) {
				remove(playerId);
			}
			return response;
		});
	}

	private static synchronized void upsert(Frienemy saved) {
		List<Frienemy> next = new ArrayList<>(all.size() + 1);
		boolean replaced = false;
		for (Frienemy one : all) {
			if (one.id() == saved.id()) {
				next.add(saved);
				replaced = true;
			} else {
				next.add(one);
			}
		}
		if (!replaced) {
			next.add(saved);
		}
		all = List.copyOf(next);
	}

	private static synchronized void remove(int playerId) {
		List<Frienemy> next = new ArrayList<>(all.size());
		for (Frienemy one : all) {
			if (one.id() != playerId) {
				next.add(one);
			}
		}
		all = List.copyOf(next);
	}
}
