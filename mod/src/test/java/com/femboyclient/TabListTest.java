package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tab list contains real players and Hypixel's layout entries, which have random
 * profile names such as "0hw2b27c53". Only real players may be reported. These names
 * are from a real recording: the first run of the tracker stored the random ones.
 */
class TabListTest {

	private static TabList.Entry player(String name, String shown) {
		return new TabList.Entry(name, shown, 4);
	}

	private static TabList.Entry npc(String name, String shown) {
		return new TabList.Entry(name, shown, 2);
	}

	@Test
	void aRealPlayerShowsTheirNameAmongRanksAndIcons() {
		assertTrue(TabList.isPlayer(player("Ant6048", "[233] Ant6048")));
		assertTrue(TabList.isPlayer(player("BananaTrees", "§b[MVP§c+§b] BananaTrees")));
		assertTrue(TabList.isPlayer(player("Bode_Y1", "[120] ⌬ [VIP+] Bode_Y1 ♲")));
		assertTrue(TabList.isPlayer(player("Steve", "Steve")));
		assertTrue(TabList.isPlayer(player("Steve", "steve (Lv 5)")), "the comparison ignores case");
		assertTrue(TabList.isPlayer(player("C0SM0SS_", "C0SM0SS_♲")), "an icon stuck to the name");
	}

	@Test
	void theRandomIdEntriesFromTheRealRecordingAreNotPlayers() {
		String[] randomIds = {"0hw2b27c53", "3b0n5s0859", "3jknd1vt81", "63yhh606p9", "8599ltn19z",
				"c371m17rw4", "g9by3w3uk4", "gbcwa9c3cl", "1ml12q9a7m", "82bsw3w84t"};
		// Layout lines show text such as these under a random profile name.
		String[] shown = {"Area: Village", "Players (28)", "Gems: 1,234", "Fairy Souls: 12/242", "Profile: Mango",
				"", "   ", "§a§lInfo", "Bank: 4.5M", "Server: mini28CW"};

		for (int i = 0; i < randomIds.length; i++) {
			assertFalse(TabList.isPlayer(player(randomIds[i], shown[i])),
					randomIds[i] + " showing \"" + shown[i] + "\" must be ignored");
		}
	}

	@Test
	void randomIdEntriesThatShowTheirOwnNameAreNotPlayersBecauseTheirUuidIsNotAnAccountUuid() {
		// A helper entry with no display name shows its own random profile name, so the text
		// check alone passes it. These are the ids that filled a 21 player Hub with 59 names.
		for (String id : new String[] {"0z63b7pkfa", "cme6z00nw2", "k60p9g26rq", "2qx7u2d52n", "s3b6ca9q0k", "87u92x233g"}) {
			assertFalse(TabList.isPlayer(npc(id, id)), id + " must be ignored");
		}
		assertEquals(List.of("Steve"), TabList.players(List.of(npc("cme6z00nw2", "cme6z00nw2"), player("Steve", "Steve")), "Me"));
	}

	@Test
	void countsEntriesPerUuidVersionForTheLog() {
		List<TabList.Entry> tab = List.of(npc("a1b2c3d4e5", "x"), npc("f1b2c3d4e5", "y"), player("Steve", "Steve"));
		assertEquals("{2=2, 4=1}", TabList.versionCounts(tab).toString());
	}

	@Test
	void aLayoutLineIsIgnoredEvenIfItsTextLooksLikeAUsername() {
		// "Players" is a valid username shape, but this entry's profile name is random.
		assertFalse(TabList.isPlayer(player("0hw2b27c53", "Players")));
	}

	@Test
	void anEntryWithNoShownTextIsNotAPlayer() {
		assertFalse(TabList.isPlayer(player("Steve", null)));
		assertFalse(TabList.isPlayer(player("Steve", "")));
	}

	@Test
	void aProfileNameThatIsNotAUsernameIsNeverAPlayer() {
		assertFalse(TabList.isPlayer(player("!A-a", "!A-a")));
		assertFalse(TabList.isPlayer(player("ab", "ab")));
		assertFalse(TabList.isPlayer(player("", "")));
		assertFalse(TabList.isPlayer(player(null, "Steve")));
	}

	@Test
	void aNameMustBeAWholeWordNotJustPartOfOne() {
		assertFalse(TabList.isPlayer(player("Ant", "Antarctica")));
		assertFalse(TabList.isPlayer(player("Ant6048", "Ant60480")));
	}

	@Test
	void playersKeepsOnlyRealPlayersWithoutYouAndWithoutDuplicates() {
		List<TabList.Entry> tab = List.of(
				player("0hw2b27c53", "Area: Village"),
				player("Ant6048", "[233] Ant6048"),
				player("WeTrollALittle", "[371] WeTrollALittle"),
				player("3b0n5s0859", "Players (28)"),
				player("BananaTrees", "[MVP+] BananaTrees"),
				player("bananatrees", "[VIP] bananatrees"),
				player("!A-a", ""),
				player("Oxidia", "Oxidia"));

		assertEquals(List.of("Ant6048", "BananaTrees", "Oxidia"), TabList.players(tab, "WeTrollALittle", null));
	}

	@Test
	void anEmptyTabListHasNoPlayers() {
		assertEquals(List.of(), TabList.players(List.of()));
	}

	@Test
	void shownTextsFeedsTheAreaLine() {
		List<TabList.Entry> tab = List.of(player("0hw2b27c53", "Area: Village"), player("Steve", null));
		assertEquals(List.of("Area: Village"), TabList.shownTexts(tab));
	}

	// ------------------------------------------------------------ players with their UUIDs

	private static final java.util.UUID ID_A = java.util.UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
	private static final java.util.UUID ID_B = java.util.UUID.fromString("bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee");

	@Test
	void lobbyPlayersHaveTheirUuidsAndAreSortedWithoutYou() {
		List<TabList.Entry> entries = List.of(
				new TabList.Entry("Bob", "[MVP+] Bob", 4, ID_B),
				new TabList.Entry("alice", "alice", 4, ID_A),
				new TabList.Entry("WeTrollALittle", "WeTrollALittle", 4, java.util.UUID.randomUUID()),
				new TabList.Entry("0hw2b27c53", "Area: Hub", 2, java.util.UUID.randomUUID()),
				new TabList.Entry("Bob", "[MVP+] Bob", 4, java.util.UUID.randomUUID()));

		assertEquals(List.of(new LobbyPlayer("alice", ID_A), new LobbyPlayer("Bob", ID_B)),
				TabList.lobbyPlayers(entries, "WeTrollALittle"));
	}

	@Test
	void aPlayerWithoutAKnownUuidIsLeftOut() {
		assertEquals(List.of(), TabList.lobbyPlayers(List.of(new TabList.Entry("Bob", "Bob", 4)), "me"));
	}
}

