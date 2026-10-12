package com.femboyclient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * Remembers what the website said about each player for a minute, so clicking the same player again
 * (or reopening the screen) does not ask the website again. A request that is still on its way is shared
 * by everyone who asks for that player. A failed request is never remembered, so the next click retries.
 *
 * Kept free of Minecraft classes so it can be tested on its own.
 */
public final class PlayerInfoCache implements PlayerLookup.Fetcher {

	/** How long an answer is reused. */
	static final Duration TTL = Duration.ofSeconds(60);

	/** The most players remembered at once. Older ones are dropped first when it is full. */
	static final int MAX_ENTRIES = 200;

	private record Entry(CompletableFuture<PlayerInfo> answer, long startedAt) {
	}

	private final PlayerLookup.Fetcher source;
	private final LongSupplier nanoClock;
	private final Map<UUID, Entry> entries = new HashMap<>();

	/**
	 * @param source    where a fresh answer comes from
	 * @param nanoClock a steady clock in nanoseconds, for example {@code System::nanoTime}
	 */
	public PlayerInfoCache(PlayerLookup.Fetcher source, LongSupplier nanoClock) {
		this.source = source;
		this.nanoClock = nanoClock;
	}

	@Override
	public synchronized CompletableFuture<PlayerInfo> fetch(LobbyPlayer player) {
		long now = nanoClock.getAsLong();
		Entry kept = entries.get(player.id());
		if (kept != null && now - kept.startedAt() < TTL.toNanos()) {
			return kept.answer();
		}

		entries.values().removeIf(e -> now - e.startedAt() >= TTL.toNanos());
		if (entries.size() >= MAX_ENTRIES) {
			entries.values().stream().min(java.util.Comparator.comparingLong(Entry::startedAt))
					.ifPresent(oldest -> entries.values().remove(oldest));
		}

		CompletableFuture<PlayerInfo> answer = source.fetch(player);
		Entry entry = new Entry(answer, now);
		entries.put(player.id(), entry);
		answer.whenComplete((info, error) -> {
			if (error != null || info == null || info.status() == PlayerInfo.Status.ERROR) {
				forget(player.id(), entry);
			}
		});
		return answer;
	}

	/** How many players are remembered now. For tests. */
	synchronized int size() {
		return entries.size();
	}

	/** Drops a failed answer, unless a newer request for the same player has replaced it. */
	private synchronized void forget(UUID id, Entry entry) {
		entries.remove(id, entry);
	}
}
