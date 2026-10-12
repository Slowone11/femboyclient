package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HollowsTrackerTest {

	record Sent(String lobby, String structure, int x, int y, int z) {
	}

	record Box(String structure, int[] box, int jasper) {
	}

	private final List<Sent> sent = new ArrayList<>();
	private final List<Box> boxes = new ArrayList<>();
	private String salt = "secret";
	private HollowsTracker.Reading reading;
	private final HollowsTracker tracker = new HollowsTracker(
			(lobby, structure, x, y, z, at, scan) -> sent.add(new Sent(lobby, structure, x, y, z)),
			(lobby, structure, box, jasper, kinds, at) -> boxes.add(new Box(structure, box, jasper)), () -> salt);

	private Object level = new Object();

	private HollowsTracker.Reading at(String area, String code, int x, int y, int z) {
		return new HollowsTracker.Reading(level, "Crystal Hollows", area, code, "Early Autumn 12th", x, y, z);
	}

	/** The first seconds in a new world: nothing is reported until the sidebar can be trusted. */
	private void enter() {
		run(1 + HollowsTracker.SETTLE_TICKS);
	}

	/** Runs the given number of game ticks. */
	private void run(int ticks) {
		for (int i = 0; i < ticks; i++) {
			tracker.tick(() -> reading, Instant.EPOCH);
		}
	}

	@Test
	void enteringTheHollowsSaysWhichLobbyYouAreIn() {
		reading = at("Crystal Nucleus", "m18AK", 500, 100, 500);
		enter();

		assertEquals(1, sent.size());
		assertEquals("", sent.get(0).structure());
		assertEquals(32, sent.get(0).lobby().length());
	}

	@Test
	void theServerCodeNeverLeavesTheGame() {
		reading = at("Crystal Nucleus", "m18AK", 500, 100, 500);
		enter();

		assertFalse(sent.get(0).lobby().toLowerCase().contains("m18ak"));
		assertNotEquals(HollowsTracker.lobbyKey("secret", "m18AK"), HollowsTracker.lobbyKey("other", "m18AK"));
		assertEquals(HollowsTracker.lobbyKey("secret", "m18AK"), HollowsTracker.lobbyKey("secret", "M18ak"),
				"two of your PCs make the same name for the same lobby");
	}

	@Test
	void standingInAStructureReportsWhereYouAre() {
		reading = at("Mines of Divan", "m18AK", 640, 61, 310);
		enter();

		assertEquals(2, sent.size());
		assertEquals(new Sent(sent.get(0).lobby(), "Mines of Divan", 640, 61, 310), sent.get(1));
	}

	private long inStructure() {
		return sent.stream().filter(s -> !s.structure().isEmpty()).count();
	}

	@Test
	void walkingFarEnoughSendsANewPositionButStandingStillDoesNot() {
		reading = at("Jungle Temple", "m18AK", 300, 100, 300);
		enter();
		long first = inStructure();

		reading = at("Jungle Temple", "m18AK", 302, 100, 301);
		run(HollowsTracker.POLL_TICKS);
		assertEquals(first, inStructure(), "2 blocks is standing still");

		reading = at("Jungle Temple", "m18AK", 312, 100, 301);
		run(HollowsTracker.POLL_TICKS);
		assertEquals(first + 1, inStructure());
	}

	@Test
	void leavingAndReenteringAStructureReportsAgain() {
		reading = at("Jungle Temple", "m18AK", 300, 100, 300);
		enter();
		reading = at("Jungle", "m18AK", 300, 100, 300);
		run(HollowsTracker.POLL_TICKS);
		int before = sent.size();

		reading = at("Jungle Temple", "m18AK", 300, 100, 300);
		run(HollowsTracker.POLL_TICKS);
		assertEquals(before + 1, sent.size());
	}

	@Test
	void anAreaThatIsNotAStructureReportsNothingExtra() {
		reading = at("Jungle", "m18AK", 300, 100, 300);
		enter();
		run(HollowsTracker.POLL_TICKS * 3);
		assertEquals(1, sent.size(), "only the lobby message");
	}

	@Test
	void otherWorldsAreIgnored() {
		reading = new HollowsTracker.Reading(level, "Hub", "Village", "m18AK", null, 0, 70, 0);
		run(HollowsTracker.POLL_TICKS * 3);
		assertTrue(sent.isEmpty());
	}

	@Test
	void nothingIsSentWithoutAServerCodeOrASalt() {
		reading = at("Mines of Divan", null, 640, 61, 310);
		enter();
		assertTrue(sent.isEmpty());

		salt = "";
		reading = at("Mines of Divan", "m18AK", 640, 61, 310);
		run(HollowsTracker.POLL_TICKS);
		assertTrue(sent.isEmpty());
	}

	@Test
	void aNewLobbySaysSoAndPresenceIsRepeatedOnceAMinute() {
		reading = at("Crystal Nucleus", "m18AK", 500, 100, 500);
		enter();
		String first = sent.get(0).lobby();

		run(HollowsTracker.PRESENCE_TICKS);
		assertEquals(2, sent.size(), "still here");

		reading = at("Crystal Nucleus", "m22ZZ", 500, 100, 500);
		run(HollowsTracker.POLL_TICKS);
		assertNotEquals(first, sent.get(sent.size() - 1).lobby());
	}

	@Test
	void theSidebarDateLineHoldsTheServerCode() {
		assertEquals("m18AK", HollowsTracker.lobbyCode(List.of("Purse: 5", "09/26/26 m18AK", "⏣ Village")));
		assertEquals("M5B", HollowsTracker.lobbyCode(List.of("09/20/26 §7M5B")));
		assertNull(HollowsTracker.lobbyCode(List.of("Purse: 5", "⏣ Village")));
	}

	@Test
	void everyStructureNameIsRecognisedAndOthersAreNot() {
		for (String name : HollowsTracker.STRUCTURES) {
			assertEquals(name, HollowsTracker.structureFor(name));
		}
		assertNull(HollowsTracker.structureFor("Magma Fields"));
		assertNull(HollowsTracker.structureFor(null));
	}

	@Test
	void aNewWorldIsNeverFiledUnderTheLobbyYouJustLeft() {
		reading = at("Jungle Temple", "m18AK", 300, 100, 300);
		enter();
		String before = sent.get(0).lobby();
		sent.clear();

		// A lobby hop: a new world, but for a moment the sidebar still shows the old server code.
		level = new Object();
		reading = at("Jungle Temple", "m18AK", 700, 100, 700);
		run(HollowsTracker.SETTLE_TICKS - 1);
		assertTrue(sent.isEmpty(), "nothing is sent while the sidebar cannot be trusted");

		reading = at("Jungle Temple", "m99ZZ", 700, 100, 700);
		run(HollowsTracker.POLL_TICKS * 2);
		assertFalse(sent.isEmpty());
		assertNotEquals(before, sent.get(0).lobby());
	}

	@Test
	void theTrackerSaysWhichWorldItsLobbyIsFor() {
		Object first = level;
		reading = at("Jungle", "m18AK", 300, 100, 300);
		enter();
		assertTrue(tracker.inLobby());
		assertEquals(first, tracker.level());

		level = new Object();                       // a hop: the tracker has not read the game yet, so it still says the old world
		assertEquals(first, tracker.level());
		reading = at("Jungle", "m99ZZ", 300, 100, 300);
		run(HollowsTracker.POLL_TICKS);
		assertTrue(tracker.inLobby(), "a code different from the lobby just left is the new lobby, no need to wait");
		run(HollowsTracker.SETTLE_TICKS);
		assertEquals(level, tracker.level());
	}

	@Test
	void aRecognisedStructureGoesOutWithItsBoxOnceTheLobbyIsKnown() {
		tracker.reportScan("Fairy Grotto", 1, new int[] {1, 2, 3, 4, 5, 6}, 40, List.of("a"), Instant.EPOCH);
		assertTrue(boxes.isEmpty(), "not in a lobby yet");

		reading = at("Jungle", "m18AK", 300, 100, 300);
		enter();
		tracker.reportScan("Fairy Grotto", 1, new int[] {1, 2, 3, 4, 5, 6}, 40, List.of("a"), Instant.EPOCH);
		assertEquals(1, boxes.size());
		assertEquals(40, boxes.get(0).jasper());
	}

	@Test
	void walkingSendsWhereYouAreAtOnceSoTheLiveDotFollows() {
		reading = at("Jungle", "m18AK", 300, 100, 300);
		enter();
		int before = sent.size();

		reading = at("Jungle", "m18AK", 302, 100, 300);
		run(HollowsTracker.POLL_TICKS);
		assertEquals(before, sent.size(), "2 blocks is standing still");

		reading = at("Jungle", "m18AK", 320, 100, 300);
		run(HollowsTracker.POLL_TICKS);
		assertEquals(before + 1, sent.size());
		assertEquals(320, sent.get(sent.size() - 1).x());
	}

	@Test
	void theServerCodeIsShownOnlyOnceItCanBeTrustedAndNeverGoesOut() {
		reading = at("Jungle", "m174D", 300, 100, 300);
		run(1);
		assertNull(tracker.lobbyCode(), "the sidebar still shows the old lobby in the first seconds");

		enter();
		assertEquals("m174D", tracker.lobbyCode());
		for (Sent one : sent) {
			assertFalse(one.lobby().toLowerCase().contains("174d"));
		}

		level = new Object();                       // a hop: the old code must not stay on the map
		reading = at("Jungle", "m174D", 300, 100, 300);
		run(HollowsTracker.POLL_TICKS);
		assertNull(tracker.lobbyCode());
	}

	@Test
	void theWorldIsKnownOnTheFirstReadingLongBeforeTheLobbyIsNamed() {
		reading = at("Jungle", "m18AK", 300, 100, 300);
		run(1);
		assertTrue(tracker.inHollowsWorld(), "scanning and drawing can start now");
		assertFalse(tracker.inLobby(), "but the sidebar cannot be trusted yet");
		assertEquals(level, tracker.level());

		enter();
		assertTrue(tracker.inLobby());
	}

	@Test
	void aFindBeforeTheLobbyIsNamedWaitsAndGoesOutWhenItIs() {
		reading = at("Jungle", "m18AK", 300, 100, 300);
		run(1);
		tracker.reportScan("Fairy Grotto", 7, new int[] {1, 2, 3, 4, 5, 6}, 20, List.of("a"), Instant.EPOCH);
		tracker.reportScan("Fairy Grotto", 7, new int[] {1, 2, 3, 4, 5, 9}, 40, List.of("a"), Instant.EPOCH);   // the same grotto, grown
		tracker.reportScan("Fairy Grotto", 8, new int[] {9, 9, 9, 9, 9, 9}, 10, List.of(), Instant.EPOCH);
		assertTrue(boxes.isEmpty(), "nothing is filed under a lobby that is not named");

		enter();
		assertEquals(2, boxes.size(), "one per grotto, the latest of each");
		assertEquals(40, boxes.get(0).jasper());
	}

	@Test
	void aFindWaitingForTheLobbyIsDroppedWhenTheWorldChanges() {
		reading = at("Jungle", "m18AK", 300, 100, 300);
		run(1);
		tracker.reportScan("Fairy Grotto", 7, new int[] {1, 2, 3, 4, 5, 6}, 20, List.of(), Instant.EPOCH);

		level = new Object();                       // a hop before the lobby was named: the find belongs to the old world
		reading = at("Jungle", "m99ZZ", 300, 100, 300);
		enter();
		assertTrue(boxes.isEmpty());
	}

	@Test
	void theSkyblockDateIsTheLineWithTheSeasonAndTheDay() {
		java.util.List<String> sidebar = java.util.List.of("Piggy: 4,954,188", "Early Autumn 12th", "Voidgloom Seraph I", " Village",
				"09/20/26 M5B", "7:30am", "www.hypixel.net");
		assertEquals("Early Autumn 12th", HollowsTracker.skyblockDate(sidebar));
		assertEquals("Late Summer 3rd", HollowsTracker.skyblockDate(java.util.List.of("§7Late Summer 3rd")));
		assertEquals("Spring 21st", HollowsTracker.skyblockDate(java.util.List.of("Spring 21st")));
		assertNull(HollowsTracker.skyblockDate(java.util.List.of("09/20/26 M5B", "Autumn Festival")));
	}
}
