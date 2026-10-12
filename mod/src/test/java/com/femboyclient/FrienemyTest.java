package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrienemyTest {

	private static final UUID ALICE = UUID.fromString("1adf8788-e917-4675-b758-defaaee07477");
	private static final UUID BOB = UUID.fromString("22222222-2222-4222-8222-222222222222");

	@Test
	void aFullListIsRead() {
		List<Frienemy> list = Frienemy.parseList("[{\"id\":1,\"uuid\":\"" + ALICE + "\",\"name\":\"Alice\",\"tag\":\"friendly\",\"notes\":\"nice\"},"
				+ "{\"id\":2,\"uuid\":null,\"name\":\"Bob\",\"tag\":\"enemy\",\"notes\":\"\"}]");

		assertEquals(2, list.size());
		assertEquals(new Frienemy(1, ALICE, "Alice", "friendly", "nice"), list.get(0));
		assertTrue(list.get(0).friendly());
		assertFalse(list.get(0).enemy());
		assertNull(list.get(1).uuid());
		assertTrue(list.get(1).enemy());
	}

	@Test
	void oneEntryIsReadFromASingleObjectAnswer() {
		Frienemy one = Frienemy.parseOne("{\"id\":7,\"uuid\":\"" + ALICE + "\",\"name\":\"Alice\",\"tag\":\"enemy\",\"notes\":\"rude\"}");
		assertEquals(new Frienemy(7, ALICE, "Alice", "enemy", "rude"), one);
	}

	@Test
	void oneEntryFromAnUnreadableOrWrongShapedAnswerIsNull() {
		assertNull(Frienemy.parseOne("not json"));
		assertNull(Frienemy.parseOne("[1,2,3]"));                         // a list, not a single object
		assertNull(Frienemy.parseOne("{\"id\":1,\"name\":\"Alice\",\"tag\":\"nemesis\"}"));    // unknown tag
	}

	@Test
	void entriesWithAnUnknownTagAreSkipped() {
		List<Frienemy> list = Frienemy.parseList(
				"[{\"id\":1,\"name\":\"Alice\",\"tag\":\"nemesis\",\"notes\":\"\"},"
				+ "{\"id\":2,\"name\":\"Bob\",\"tag\":\"enemy\",\"notes\":\"\"}]");
		assertEquals(1, list.size());
		assertEquals("Bob", list.get(0).name());
	}

	@Test
	void unreadableBodiesGiveAnEmptyListRatherThanThrowing() {
		assertEquals(List.of(), Frienemy.parseList("not json"));
		assertEquals(List.of(), Frienemy.parseList("{\"not\":\"an array\"}"));
		assertEquals(List.of(), Frienemy.parseList(""));
	}

	@Test
	void presentInMatchesByUuidFirstThenByName() {
		Frienemy taggedByUuid = new Frienemy(1, ALICE, "Alice", "friendly", "");
		Frienemy taggedByNameOnly = new Frienemy(2, null, "Bob", "enemy", "");
		Frienemy notInLobby = new Frienemy(3, BOB, "Carol", "enemy", "");
		List<Frienemy> tagged = List.of(taggedByUuid, taggedByNameOnly, notInLobby);

		List<LobbyPlayer> lobby = List.of(
				new LobbyPlayer("Alice", ALICE),
				new LobbyPlayer("bob", UUID.fromString("33333333-3333-4333-8333-333333333333")),   // renamed uuid, matched by name
				new LobbyPlayer("Stranger", UUID.fromString("44444444-4444-4444-8444-444444444444")));

		List<Frienemy> present = Frienemy.presentIn(tagged, lobby);
		assertEquals(List.of(taggedByUuid, taggedByNameOnly), present);
	}

	@Test
	void presentInIsEmptyWhenNoLobbyPlayerIsTagged() {
		List<Frienemy> tagged = List.of(new Frienemy(1, ALICE, "Alice", "friendly", ""));
		List<LobbyPlayer> lobby = List.of(new LobbyPlayer("Stranger", BOB));
		assertEquals(List.of(), Frienemy.presentIn(tagged, lobby));
	}
}
