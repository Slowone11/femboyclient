package com.femboyclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** From a chat line to the message for the website. */
class PlayerChatCollectorTest {

	private final List<JsonObject> sent = new ArrayList<>();
	private boolean allowed;
	private boolean ownAllowed;
	private Set<String> lobby;
	private String world;
	private PlayerChatCollector collector;

	@BeforeEach
	void setUp() {
		sent.clear();
		allowed = true;
		ownAllowed = true;
		lobby = Set.of("test", "alice", "bob");
		world = "The End";
		collector = new PlayerChatCollector(sent::add, () -> allowed, () -> ownAllowed, () -> "WeTrollALittle", () -> lobby, () -> world);
	}

	private JsonArray messages(int batch) {
		return sent.get(batch).getAsJsonArray("messages");
	}

	private String field(int batch, int index, String name) {
		return messages(batch).get(index).getAsJsonObject().get(name).getAsString();
	}

	@Test
	void aPlayerInTheLobbyWhoTypesIsCollected() {
		collector.onLine("[248] ❤ [MVP+] TEST: hello there");
		collector.onDisconnect();

		assertEquals(1, sent.size());
		assertEquals(1, messages(0).size());
		assertEquals("TEST", field(0, 0, "player"));
		assertEquals("hello there", field(0, 0, "text"));
		assertEquals("PUBLIC", field(0, 0, "type"));
		Instant.parse(field(0, 0, "timestamp"));
	}

	@Test
	void theMessageCarriesTheWorldAndYourName() {
		collector.onLine("Alice: hi");
		collector.onDisconnect();

		assertEquals("The End", sent.get(0).get("world").getAsString());
		assertEquals("WeTrollALittle", sent.get(0).get("me").getAsString());
		assertEquals(java.util.Set.of("me", "world", "messages"), sent.get(0).keySet());
		assertTrue(TrackerPayload.isChat(sent.get(0)));
		assertEquals("/tracker/chat", TrackerPayload.path(sent.get(0)));
	}

	@Test
	void theRankAndLevelInFrontOfTheNameAreNotKept() {
		collector.onLine("[248] ❤ [MVP+] TEST: hello");
		collector.onDisconnect();

		assertFalse(messages(0).toString().contains("MVP"));
		assertFalse(messages(0).toString().contains("248"));
	}

	@Test
	void aServerLineThatLooksLikeChatIsNotAPersonBecauseTheSenderIsNotInTheLobby() {
		collector.onLine("Sold: 5 items");
		collector.onLine("Server: mini28CW");
		collector.onLine("Loot: 5 coins");
		collector.onDisconnect();

		assertEquals(0, sent.size());
	}

	@Test
	void anEmptyOrUnknownLobbyCollectsNoPublicChat() {
		lobby = Set.of();
		collector.onLine("[MVP+] Alice: hello");
		collector.onDisconnect();

		assertEquals(0, sent.size());
	}

	@Test
	void partyGuildAndPrivateMessagesAreCollectedEvenFromSomeoneNotInTheLobby() {
		lobby = Set.of();
		collector.onLine("Party > [MVP+] Zed: ready?");
		collector.onLine("Guild > [VIP] Amy [Member]: gg");
		collector.onLine("From [MVP+] Cat: psst");
		collector.onLine("Co-op > Dan: mine");
		collector.onDisconnect();

		assertEquals(4, messages(0).size());
		assertEquals("PARTY", field(0, 0, "type"));
		assertEquals("GUILD", field(0, 1, "type"));
		assertEquals("PRIVATE_FROM", field(0, 2, "type"));
		assertEquals("COOP", field(0, 3, "type"));
	}

	@Test
	void yourOwnMessagesAreCollectedToo() {
		lobby = Set.of("wetrollalittle");
		collector.onLine("[MVP+] WeTrollALittle: my own message");
		collector.onLine("Party > [MVP+] wetrollalittle: my party message");
		collector.onDisconnect();

		assertEquals(1, sent.size());
		assertEquals(2, messages(0).size());
		assertEquals("WeTrollALittle", field(0, 0, "player"));
		assertEquals("my own message", field(0, 0, "text"));
		assertEquals("PUBLIC", field(0, 0, "type"));
		assertEquals("wetrollalittle", field(0, 1, "player"));
		assertEquals("PARTY", field(0, 1, "type"));
	}

	@Test
	void aPrivateMessageYouSendIsCollectedUnderThePlayerYouWroteTo() {
		lobby = Set.of();
		collector.onLine("To [MVP+] Alice: something I sent");
		collector.onDisconnect();

		assertEquals(1, messages(0).size());
		assertEquals("Alice", field(0, 0, "player"));
		assertEquals("PRIVATE_TO", field(0, 0, "type"));
		assertEquals("something I sent", field(0, 0, "text"));
	}

	@Test
	void everyMessageCarriesItsTypeAndNoOldChannelKey() {
		collector.onLine("[MVP+] TEST: hi");
		collector.onDisconnect();

		assertEquals(java.util.Set.of("player", "type", "text", "timestamp"), messages(0).get(0).getAsJsonObject().keySet());
	}

	@Test
	void everyRepeatIsKeptHoweverCloseTogether() {
		Instant t = Instant.parse("2026-09-21T15:00:00Z");
		collector.handle("[MVP+] TEST: hello there", t);
		collector.handle("[MVP+] TEST: hello there", t.plusMillis(1));
		collector.handle("[MVP+] TEST: hello there", t.plusMillis(900));
		collector.handle("Party > [MVP+] TEST: hello there", t.plusMillis(950));
		collector.onDisconnect();

		assertEquals(4, messages(0).size());
	}

	@Test
	void theCollectedCountGoesUpWithEveryMessageKept() {
		assertEquals(0, collector.collected());
		collector.onLine("Alice: hi");
		collector.onLine("Alice: hi");
		collector.onLine("Sold: 5 items");              // not a person: not counted
		assertEquals(2, collector.collected());
	}

	@Test
	void yourOwnPublicMessageIsCollectedThoughYouAreNeverInTheLobbyNames() {
		lobby = Set.of("alice");
		collector.onLine("[MVP+] WeTrollALittle: hello lobby");
		collector.onDisconnect();

		assertEquals(1, sent.size());
		assertEquals("WeTrollALittle", field(0, 0, "player"));
		assertEquals("PUBLIC", field(0, 0, "type"));
	}

	@Test
	void yourOwnMessagesFollowTheirOwnSwitch() {
		allowed = false;
		collector.onLine("[MVP+] WeTrollALittle: public with the others' switch off");
		collector.onLine("Party > [MVP+] WeTrollALittle: party with the others' switch off");
		collector.onLine("To [VIP] Bob: a private message I sent");
		collector.onLine("Alice: someone else, not collected");
		collector.onLine("From [VIP] Bob: someone else, not collected");
		collector.onDisconnect();

		assertEquals(1, sent.size());
		assertEquals(3, messages(0).size());
		assertEquals("PUBLIC", field(0, 0, "type"));
		assertEquals("PARTY", field(0, 1, "type"));
		assertEquals("PRIVATE_TO", field(0, 2, "type"));
	}

	@Test
	void yourOwnMessagesAreNotCollectedWhenTheModIsSwitchedOff() {
		allowed = false;
		ownAllowed = false;
		collector.onLine("[MVP+] WeTrollALittle: hello");
		collector.onLine("To Bob: hi");
		collector.onDisconnect();

		assertEquals(0, sent.size());
	}

	@Test
	void othersAreNotCollectedWhenTheModIsSwitchedOff() {
		allowed = false;
		collector.onLine("Alice: hello");
		collector.onDisconnect();

		assertEquals(0, sent.size());
	}

	@Test
	void manyMessagesGoOutTogetherInOneBatchInOrder() {
		collector.onLine("Alice: one");
		collector.onLine("Bob: two");
		collector.onLine("Alice: three");
		collector.onDisconnect();

		assertEquals(1, sent.size());
		assertEquals(3, messages(0).size());
		assertEquals("one", field(0, 0, "text"));
		assertEquals("Bob", field(0, 1, "player"));
		assertEquals("three", field(0, 2, "text"));
	}

	@Test
	void aBatchIsSentOnceItHasWaitedLongEnoughAndNotBefore() throws InterruptedException {
		collector.onLine("Alice: one");
		collector.tick();
		assertEquals(0, sent.size(), "too soon");

		Thread.sleep(PlayerChatCollector.FLUSH_AFTER.toMillis() + 100);
		collector.tick();
		assertEquals(1, sent.size());

		collector.tick();
		assertEquals(1, sent.size(), "nothing left to send");
	}

	@Test
	void aFullBatchIsSentAtOnce() {
		for (int i = 0; i < PlayerChatCollector.MAX_BATCH; i++) {
			collector.onLine("Alice: message " + i);
		}

		assertEquals(1, sent.size());
		assertEquals(PlayerChatCollector.MAX_BATCH, messages(0).size());
	}

	@Test
	void anUnknownWorldIsSentAsUnknown() {
		world = null;
		collector.onLine("Alice: hi");
		collector.onDisconnect();

		assertEquals("Unknown", sent.get(0).get("world").getAsString());
	}

	@Test
	void oddInputNeverThrows() {
		collector.onLine(null);
		collector.onLine("");
		collector.onLine("\n\n");
		collector.onLine(": : :");
		collector.onLine("x".repeat(10_000));
		collector.onDisconnect();

		assertEquals(0, sent.size());
	}

	@Test
	void aBrokenSinkOrLobbyDoesNotEscapeIntoTheGame() {
		PlayerChatCollector broken = new PlayerChatCollector(message -> {
			throw new IllegalStateException("website link exploded");
		}, () -> true, () -> true, () -> "Me", () -> {
			throw new IllegalStateException("tab list exploded");
		}, () -> "Hub");

		broken.onLine("Alice: hi");
		broken.onLine("Party > Alice: hi");
		broken.onDisconnect();
		broken.tick();
	}

	@Test
	void aMessageWithSeveralLinesIsReadOneLineAtATime() {
		collector.onLine("Alice: first\nBob: second");
		collector.onDisconnect();

		assertEquals(2, messages(0).size());
	}
}
