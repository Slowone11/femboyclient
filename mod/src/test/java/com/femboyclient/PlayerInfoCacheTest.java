package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class PlayerInfoCacheTest {

	private static final LobbyPlayer ALICE = new LobbyPlayer("Alice", UUID.randomUUID());
	private static final LobbyPlayer BOB = new LobbyPlayer("Bob", UUID.randomUUID());

	private final List<LobbyPlayer> asked = new ArrayList<>();
	private final List<CompletableFuture<PlayerInfo>> answers = new ArrayList<>();
	private long nanos;
	private final PlayerInfoCache cache = new PlayerInfoCache(player -> {
		asked.add(player);
		CompletableFuture<PlayerInfo> answer = new CompletableFuture<>();
		answers.add(answer);
		return answer;
	}, () -> nanos);

	private static PlayerInfo found() {
		return new PlayerInfo(PlayerInfo.Status.FOUND, "x", null, null, 1, List.of(), null);
	}

	private void advanceSeconds(long seconds) {
		nanos += TimeUnit.SECONDS.toNanos(seconds);
	}

	@Test
	void theSamePlayerWithinAMinuteIsNotAskedAgain() {
		CompletableFuture<PlayerInfo> first = cache.fetch(ALICE);
		answers.get(0).complete(found());
		advanceSeconds(59);

		assertSame(first, cache.fetch(ALICE));
		assertEquals(1, asked.size());
	}

	@Test
	void afterAMinuteThePlayerIsAskedAgain() {
		cache.fetch(ALICE);
		answers.get(0).complete(found());
		advanceSeconds(60);

		assertNotSame(answers.get(0), cache.fetch(ALICE));
		assertEquals(2, asked.size());
	}

	@Test
	void eachPlayerHasHisOwnEntry() {
		cache.fetch(ALICE);
		cache.fetch(BOB);

		assertEquals(List.of(ALICE, BOB), asked);
	}

	@Test
	void aRequestStillOnItsWayIsShared() {
		CompletableFuture<PlayerInfo> first = cache.fetch(ALICE);
		advanceSeconds(10);

		assertSame(first, cache.fetch(ALICE));
		assertEquals(1, asked.size());
	}

	@Test
	void aFailedAnswerIsNotRemembered() {
		cache.fetch(ALICE);
		answers.get(0).complete(PlayerInfo.failed("website down"));

		cache.fetch(ALICE);
		assertEquals(2, asked.size());
	}

	@Test
	void anExceptionallyFailedRequestIsNotRemembered() {
		cache.fetch(ALICE);
		answers.get(0).completeExceptionally(new RuntimeException("boom"));

		cache.fetch(ALICE);
		assertEquals(2, asked.size());
	}

	@Test
	void aPlayerNotInTheDatabaseIsRememberedToo() {
		cache.fetch(ALICE);
		answers.get(0).complete(new PlayerInfo(PlayerInfo.Status.NOT_IN_DATABASE, null, null, null, 0, List.of(), null));

		cache.fetch(ALICE);
		assertEquals(1, asked.size());
	}

	@Test
	void theCacheStaysBounded() {
		for (int i = 0; i < PlayerInfoCache.MAX_ENTRIES + 50; i++) {
			advanceSeconds(0);
			cache.fetch(new LobbyPlayer("P" + i, UUID.randomUUID()));
			nanos += 1;
		}
		assertEquals(PlayerInfoCache.MAX_ENTRIES, cache.size());
		int before = asked.size();

		cache.fetch(ALICE);           // a new player still works when it is full
		assertEquals(before + 1, asked.size());
	}
}
