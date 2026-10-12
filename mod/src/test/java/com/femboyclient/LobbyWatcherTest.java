package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives LobbyWatcher tick by tick. A "world" is just an object, because the real
 * game world is only compared by identity.
 */
class LobbyWatcherTest {

	record Sent(List<String> players, Instant visitStarted, String world) {
	}

	private final List<Sent> sent = new ArrayList<>();

	/** The scanner setting: everything allowed unless a test changes it. */
	private Predicate<String> allowed = world -> true;
	private final LobbyWatcher watcher = new LobbyWatcher(
			(players, started, world) -> sent.add(new Sent(List.copyOf(players), started, world)),
			world -> allowed.test(world));

	private List<String> tabList = new ArrayList<>();
	/** What the sidebar says right now. Null means it cannot be read yet. */
	private String skyBlockWorld = "Hub";
	private int reads;
	private int worldReads;
	private Instant clock = Instant.parse("2026-09-21T15:00:00Z");

	private final Object lobbyA = new Object();
	private final Object lobbyB = new Object();

	private void ticks(Object gameWorld, int count) {
		for (int i = 0; i < count; i++) {
			watcher.tick(gameWorld, () -> {
				worldReads++;
				return skyBlockWorld;
			}, () -> {
				reads++;
				return List.copyOf(tabList);
			}, clock);
			clock = clock.plusMillis(50);
		}
	}

	private static List<String> names(String prefix, int count) {
		return IntStream.rangeClosed(1, count).mapToObj(i -> prefix + i).toList();
	}

	@Test
	void nothingHappensOutsideAWorld() {
		tabList = new ArrayList<>(List.of("Alice"));

		ticks(null, 200);

		assertTrue(sent.isEmpty());
		assertEquals(0, reads);
	}

	@Test
	void waitsForTheTabListToFillThenReportsEveryone() {
		tabList = new ArrayList<>(List.of("Alice", "Bob"));
		Instant entered = clock;

		ticks(lobbyA, LobbyWatcher.SETTLE_TICKS - 1);
		assertTrue(sent.isEmpty(), "must not report before the list has had time to fill");

		ticks(lobbyA, 1);
		assertEquals(1, sent.size());
		assertEquals(List.of("Alice", "Bob"), sent.get(0).players());
		assertEquals(entered, sent.get(0).visitStarted(), "the visit starts when the lobby was entered");
	}

	@Test
	void theTabListIsReadOnceASecondNotEveryTick() {
		tabList = new ArrayList<>(List.of("Alice"));

		ticks(lobbyA, LobbyWatcher.SETTLE_TICKS);
		assertEquals(1, reads);

		ticks(lobbyA, LobbyWatcher.POLL_TICKS - 1);
		assertEquals(1, reads);

		ticks(lobbyA, 1);
		assertEquals(2, reads);
	}

	@Test
	void anEmptyListIsRetriedEverySecondUntilPlayersAppear() {
		Instant entered = clock;

		ticks(lobbyA, LobbyWatcher.SETTLE_TICKS);
		ticks(lobbyA, LobbyWatcher.POLL_TICKS * 3);
		assertTrue(sent.isEmpty(), "an empty lobby reports nothing");

		tabList = new ArrayList<>(List.of("Carol"));
		ticks(lobbyA, LobbyWatcher.POLL_TICKS);

		assertEquals(1, sent.size());
		assertEquals(List.of("Carol"), sent.get(0).players());
		assertEquals(entered, sent.get(0).visitStarted(), "still the visit that began on entry");
	}

	@Test
	void laterJoinersAreReportedOnceAsChangesOnly() {
		tabList = new ArrayList<>(List.of("Alice", "Bob"));
		ticks(lobbyA, LobbyWatcher.SETTLE_TICKS);

		tabList.add("Carol");
		ticks(lobbyA, LobbyWatcher.POLL_TICKS);

		assertEquals(2, sent.size());
		assertEquals(List.of("Carol"), sent.get(1).players(), "only the change, not the whole list again");
		assertEquals(sent.get(0).visitStarted(), sent.get(1).visitStarted(), "same visit");
		assertEquals("Hub", sent.get(1).world(), "and the same world");

		ticks(lobbyA, LobbyWatcher.POLL_TICKS * 5);
		assertEquals(2, sent.size(), "nothing more to say while nothing changes");
	}

	@Test
	void playersLeavingReportNothing() {
		tabList = new ArrayList<>(List.of("Alice", "Bob"));
		ticks(lobbyA, LobbyWatcher.SETTLE_TICKS);

		tabList.remove("Bob");
		ticks(lobbyA, LobbyWatcher.POLL_TICKS * 2);

		assertEquals(1, sent.size());
	}

	@Test
	void aPlayerWhoLeavesAndComesBackIsReportedAgainAndTheWebsiteKeepsOneSighting() {
		tabList = new ArrayList<>(List.of("Alice", "Bob"));
		ticks(lobbyA, LobbyWatcher.SETTLE_TICKS);

		tabList.remove("Bob");
		ticks(lobbyA, LobbyWatcher.POLL_TICKS);
		tabList.add("Bob");
		ticks(lobbyA, LobbyWatcher.POLL_TICKS);

		assertEquals(2, sent.size());
		assertEquals(List.of("Bob"), sent.get(1).players());
	}

	@Test
	void namesAreComparedIgnoringCase() {
		tabList = new ArrayList<>(List.of("alice"));
		ticks(lobbyA, LobbyWatcher.SETTLE_TICKS);

		tabList = new ArrayList<>(List.of("ALICE"));
		ticks(lobbyA, LobbyWatcher.POLL_TICKS);

		assertEquals(1, sent.size());
	}

	@Test
	void aNewWorldIsANewVisitAndReportsEveryoneAgain() {
		tabList = new ArrayList<>(List.of("Alice", "Bob"));
		ticks(lobbyA, LobbyWatcher.SETTLE_TICKS);

		tabList = new ArrayList<>(List.of("Bob", "Dave"));   // a different lobby, Bob happens to be in it too
		ticks(lobbyB, LobbyWatcher.SETTLE_TICKS);

		assertEquals(2, sent.size());
		assertEquals(List.of("Bob", "Dave"), sent.get(1).players(), "the full list of the new lobby");
		assertNotEquals(sent.get(0).visitStarted(), sent.get(1).visitStarted());
	}

	@Test
	void everyWorldChangeStartsItsOwnVisit() {
		tabList = new ArrayList<>(names("P", 3));
		Object[] worlds = {new Object(), new Object(), new Object()};
		for (Object world : worlds) {
			ticks(world, LobbyWatcher.SETTLE_TICKS);
		}

		assertEquals(3, sent.size());
		assertEquals(3, sent.stream().map(Sent::visitStarted).distinct().count());
	}

	// ------------------------------------------------- the same-lobby guard

	@Test
	void replacingTheWorldInTheSameLobbyDoesNotStartANewVisit() {
		tabList = new ArrayList<>(names("Player", 10));
		ticks(lobbyA, LobbyWatcher.SETTLE_TICKS);

		// For example dying and respawning: a new world, the same people, one extra arrival.
		tabList.add("Newcomer");
		ticks(lobbyB, LobbyWatcher.SETTLE_TICKS);

		assertEquals(2, sent.size());
		assertEquals(List.of("Newcomer"), sent.get(1).players(), "only the newcomer, nobody is met twice");
		assertEquals(sent.get(0).visitStarted(), sent.get(1).visitStarted(), "the same visit");
	}

	@Test
	void anIdenticalListAfterAWorldChangeReportsNothingNew() {
		tabList = new ArrayList<>(names("Player", 10));
		ticks(lobbyA, LobbyWatcher.SETTLE_TICKS);
		ticks(lobbyB, LobbyWatcher.SETTLE_TICKS);

		assertEquals(1, sent.size());
	}

	@Test
	void theGuardDoesNotApplyToSmallLobbies() {
		tabList = new ArrayList<>(names("Player", LobbyWatcher.SAME_LOBBY_MIN_PLAYERS - 1));
		ticks(lobbyA, LobbyWatcher.SETTLE_TICKS);
		ticks(lobbyB, LobbyWatcher.SETTLE_TICKS);

		assertEquals(2, sent.size(), "too few people to be sure it is the same lobby");
		assertNotEquals(sent.get(0).visitStarted(), sent.get(1).visitStarted());
	}

	@Test
	void aMostlyDifferentListIsANewLobbyEvenIfSomePeopleOverlap() {
		tabList = new ArrayList<>(names("Player", 10));
		ticks(lobbyA, LobbyWatcher.SETTLE_TICKS);

		// 7 of the 10 people are the same (70 percent), which is below the 80 percent bar.
		List<String> next = new ArrayList<>(names("Player", 7));
		next.addAll(names("Other", 3));
		tabList = next;
		ticks(lobbyB, LobbyWatcher.SETTLE_TICKS);

		assertEquals(2, sent.size());
		assertEquals(10, sent.get(1).players().size(), "everyone in the new lobby is reported");
		assertNotEquals(sent.get(0).visitStarted(), sent.get(1).visitStarted());
	}

	@Test
	void leavingTheWorldEndsTheVisitSoComingBackIsANewOne() {
		tabList = new ArrayList<>(names("Player", 10));
		ticks(lobbyA, LobbyWatcher.SETTLE_TICKS);

		ticks(null, 5);                                // disconnected
		ticks(lobbyA, LobbyWatcher.SETTLE_TICKS);      // and back, even with the same people

		assertEquals(2, sent.size());
		assertEquals(10, sent.get(1).players().size(), "the same-lobby guard must not apply after leaving");
		assertNotEquals(sent.get(0).visitStarted(), sent.get(1).visitStarted());
	}

	@Test
	void resetForgetsTheVisit() {
		tabList = new ArrayList<>(List.of("Alice"));
		ticks(lobbyA, LobbyWatcher.SETTLE_TICKS);

		watcher.reset();
		ticks(lobbyA, LobbyWatcher.SETTLE_TICKS);

		assertEquals(2, sent.size());
	}

	@Test
	void aBusyLobbyIsReportedInOneGo() {
		tabList = new ArrayList<>(names("Player", 150));

		ticks(lobbyA, LobbyWatcher.SETTLE_TICKS);

		assertEquals(1, sent.size());
		assertEquals(150, sent.get(0).players().size());
	}

	// ------------------------------------------------------ SkyBlock worlds

	@Test
	void everyReportCarriesTheWorld() {
		skyBlockWorld = "Crystal Hollows";
		tabList = new ArrayList<>(List.of("Alice"));

		ticks(lobbyA, LobbyWatcher.SETTLE_TICKS);

		assertEquals("Crystal Hollows", sent.get(0).world());
	}

	@Test
	void aLateSidebarIsWaitedForAndTheVisitKeepsItsStartTime() {
		skyBlockWorld = null;                     // the sidebar has not appeared yet
		tabList = new ArrayList<>(List.of("Alice"));
		Instant entered = clock;

		ticks(lobbyA, LobbyWatcher.SETTLE_TICKS + LobbyWatcher.POLL_TICKS);
		assertTrue(sent.isEmpty(), "must not guess a world while the sidebar is still loading");
		assertEquals(0, reads, "and must not even read the tab list yet");

		skyBlockWorld = "Hub";
		ticks(lobbyA, LobbyWatcher.POLL_TICKS);

		assertEquals(1, sent.size());
		assertEquals("Hub", sent.get(0).world());
		assertEquals(entered, sent.get(0).visitStarted());
	}

	@Test
	void ifTheWorldNeverBecomesReadableTheVisitIsCalledUnknown() {
		skyBlockWorld = null;
		tabList = new ArrayList<>(List.of("Alice"));

		ticks(lobbyA, LobbyWatcher.WORLD_GIVE_UP_TICKS - 1);
		assertTrue(sent.isEmpty());

		ticks(lobbyA, LobbyWatcher.POLL_TICKS);

		assertEquals(1, sent.size());
		assertEquals("Unknown", sent.get(0).world());
	}

	@Test
	void theWorldIsReadOnlyUntilItIsKnown() {
		tabList = new ArrayList<>(List.of("Alice"));

		ticks(lobbyA, LobbyWatcher.SETTLE_TICKS);
		int afterFirst = worldReads;
		ticks(lobbyA, LobbyWatcher.POLL_TICKS * 5);

		assertEquals(1, afterFirst);
		assertEquals(1, worldReads, "a visit stays in one world, so it is not re-read");
	}

	@Test
	void movingBetweenWorldsStartsANewVisitInEach() {
		tabList = new ArrayList<>(List.of("Alice", "Bob"));
		Object hub = new Object(), dungeonHub = new Object(), hollows = new Object();

		skyBlockWorld = "Hub";
		ticks(hub, LobbyWatcher.SETTLE_TICKS);
		skyBlockWorld = "Dungeon Hub";
		ticks(dungeonHub, LobbyWatcher.SETTLE_TICKS);
		skyBlockWorld = "Crystal Hollows";
		ticks(hollows, LobbyWatcher.SETTLE_TICKS);

		assertEquals(List.of("Hub", "Dungeon Hub", "Crystal Hollows"), sent.stream().map(Sent::world).toList());
		assertEquals(3, sent.stream().map(Sent::visitStarted).distinct().count());
	}

	@Test
	void aWorldChangeIsANewVisitEvenIfTheTabListIsAlmostIdentical() {
		tabList = new ArrayList<>(names("Player", 12));
		skyBlockWorld = "Hub";
		ticks(lobbyA, LobbyWatcher.SETTLE_TICKS);

		skyBlockWorld = "Dungeon Hub";               // a different world, same crowd
		ticks(lobbyB, LobbyWatcher.SETTLE_TICKS);

		assertEquals(2, sent.size());
		assertEquals(12, sent.get(1).players().size(), "the guard only merges visits in the same world");
		assertEquals("Dungeon Hub", sent.get(1).world());
		assertNotEquals(sent.get(0).visitStarted(), sent.get(1).visitStarted());
	}

	// ----------------------------------------------------- the scanner setting

	@Test
	void nothingIsReportedWhileTheScannerIsSetToAnotherWorld() {
		allowed = world -> world.equals("Hub");
		skyBlockWorld = "Crystal Hollows";
		tabList = new ArrayList<>(List.of("Alice", "Bob"));

		ticks(lobbyA, LobbyWatcher.SETTLE_TICKS + LobbyWatcher.POLL_TICKS * 3);

		assertTrue(sent.isEmpty());
		assertEquals(0, reads, "not even the tab list is read for a world that is not being scanned");
	}

	@Test
	void aMatchingWorldIsReported() {
		allowed = world -> world.equals("Hub");
		skyBlockWorld = "Hub";
		tabList = new ArrayList<>(List.of("Alice"));

		ticks(lobbyA, LobbyWatcher.SETTLE_TICKS);

		assertEquals(1, sent.size());
	}

	@Test
	void theScannerFollowsYouFromOneWorldToAnother() {
		allowed = world -> world.equals("Hub");
		tabList = new ArrayList<>(List.of("Alice"));

		skyBlockWorld = "Dungeon Hub";
		ticks(lobbyA, LobbyWatcher.SETTLE_TICKS + LobbyWatcher.POLL_TICKS);
		assertTrue(sent.isEmpty(), "in the Dungeon Hub, nothing is sent");

		skyBlockWorld = "Hub";
		ticks(lobbyB, LobbyWatcher.SETTLE_TICKS);
		assertEquals(1, sent.size(), "back in the Hub, it is");
		assertEquals("Hub", sent.get(0).world());
	}

	@Test
	void changingTheSettingMidVisitReportsEveryoneThenAndNotBefore() {
		allowed = world -> false;
		skyBlockWorld = "Hub";
		tabList = new ArrayList<>(List.of("Alice", "Bob"));
		Instant entered = clock;

		ticks(lobbyA, LobbyWatcher.SETTLE_TICKS + LobbyWatcher.POLL_TICKS * 2);
		assertTrue(sent.isEmpty());

		allowed = world -> true;                   // /scanner true
		ticks(lobbyA, LobbyWatcher.POLL_TICKS);

		assertEquals(1, sent.size());
		assertEquals(List.of("Alice", "Bob"), sent.get(0).players());
		assertEquals(entered, sent.get(0).visitStarted());
	}

	@Test
	void anUnknownWorldIsScannedOnlyWhenScanningEveryWorld() {
		skyBlockWorld = null;
		tabList = new ArrayList<>(List.of("Alice"));

		allowed = world -> true;                   // every world
		ticks(lobbyA, LobbyWatcher.WORLD_GIVE_UP_TICKS);
		assertEquals(1, sent.size());
		assertEquals("Unknown", sent.get(0).world());

		sent.clear();
		allowed = world -> world.equals("Hub");    // one world only
		ticks(lobbyB, LobbyWatcher.WORLD_GIVE_UP_TICKS + LobbyWatcher.POLL_TICKS * 2);
		assertTrue(sent.isEmpty(), "an unknown world does not match a chosen world");
	}
}
