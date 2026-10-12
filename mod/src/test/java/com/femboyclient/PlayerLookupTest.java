package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerLookupTest {

	private static final LobbyPlayer ALICE = new LobbyPlayer("Alice", UUID.randomUUID());
	private static final LobbyPlayer BOB = new LobbyPlayer("Bob", UUID.randomUUID());

	private final java.util.Map<LobbyPlayer, CompletableFuture<PlayerInfo>> pending = new java.util.HashMap<>();
	private final java.util.List<Runnable> gameQueue = new java.util.ArrayList<>();
	private final PlayerLookup lookup = new PlayerLookup(
			player -> pending.computeIfAbsent(player, p -> new CompletableFuture<>()), gameQueue::add);

	private void runGameThread() {
		List<Runnable> now = List.copyOf(gameQueue);
		gameQueue.clear();
		now.forEach(Runnable::run);
	}

	private static PlayerInfo found(int times) {
		return new PlayerInfo(PlayerInfo.Status.FOUND, "x", null, null, times, List.of(), null);
	}

	@Test
	void nothingIsSelectedAtFirst() {
		assertNull(lookup.selected());
		assertNull(lookup.info());
		assertFalse(lookup.loading());
	}

	@Test
	void selectingStartsLoadingAndTheAnswerArrivesOnTheGameThread() {
		lookup.select(ALICE);
		assertSame(ALICE, lookup.selected());
		assertTrue(lookup.loading());

		PlayerInfo answer = found(3);
		pending.get(ALICE).complete(answer);
		assertTrue(lookup.loading(), "the answer is only stored once the game thread runs it");
		runGameThread();

		assertFalse(lookup.loading());
		assertSame(answer, lookup.info());
	}

	@Test
	void anAnswerForAPlayerWhoWasSwitchedAwayFromIsDropped() {
		lookup.select(ALICE);
		lookup.select(BOB);
		pending.get(ALICE).complete(found(1));
		runGameThread();
		assertTrue(lookup.loading());
		assertNull(lookup.info());

		PlayerInfo bobs = found(2);
		pending.get(BOB).complete(bobs);
		runGameThread();
		assertSame(bobs, lookup.info());
	}

	@Test
	void aFailedRequestBecomesAnError() {
		lookup.select(ALICE);
		pending.get(ALICE).completeExceptionally(new RuntimeException("boom"));
		runGameThread();

		assertEquals(PlayerInfo.Status.ERROR, lookup.info().status());
		assertFalse(lookup.loading());
	}

	@Test
	void aFetcherThatThrowsIsAnErrorNotACrash() {
		PlayerLookup broken = new PlayerLookup(player -> { throw new IllegalStateException("no"); }, Runnable::run);
		broken.select(ALICE);

		assertEquals(PlayerInfo.Status.ERROR, broken.info().status());
	}
}
