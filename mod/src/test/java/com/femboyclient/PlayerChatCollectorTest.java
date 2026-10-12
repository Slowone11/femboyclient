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
	private Set<String> lobby;
	private String world;
	private PlayerChatCollector collector;

	@BeforeEach
	void setUp() {
		sent.clear();
		allowed = true;
		lobby = Set.of("test", "alice", "bob");
		world = "The End";
		collector = new PlayerChatCollector(sent::add, () -> allowed, () -> "WeTrollALittle", () -> lobby, () -> world);
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
	void theSameMessageAgainWithinTwoSecondsIsDropped() {
		Instant t = Instant.parse("2026-09-21T15:00:00Z");
		collector.handle("[MVP+] TEST: hello there", t);
		collector.handle("[MVP+] TEST: hello there", t.plusMillis(1900));
		collector.onDisconnect();

		assertEquals(1, messages(0).size());
	}

	@Test
	void theSameMessageAfterTwoSecondsIsKeptAgain() {
		Instant t = Instant.parse("2026-09-21T15:00:00Z");
		collector.handle("[MVP+] TEST: hello there", t);
		collector.handle("[MVP+] TEST: hello there", t.plusMillis(2000));
		collector.onDisconnect();

		assertEquals(2, messages(0).size());
	}

	@Test
	void aRepeatIsMeasuredFromTheOneThatWasKeptSoASpammerStillGetsThrough() {
		Instant t = Instant.parse("2026-09-21T15:00:00Z");
		collector.handle("[MVP+] TEST: spam", t);
		collector.handle("[MVP+] TEST: spam", t.plusMillis(1500));           // dropped
		collector.handle("[MVP+] TEST: spam", t.plusMillis(2500));           // 2.5 s after the kept one: kept
		collector.onDisconnect();

		assertEquals(2, messages(0).size());
	}

	@Test
	void theSameTextInAnotherTypeOrFromAnotherPlayerIsNotARepeat() {
		Instant t = Instant.parse("2026-09-21T15:00:00Z");
		collector.handle("[MVP+] TEST: gg", t);
		collector.handle("Party > [MVP+] TEST: gg", t.plusMillis(100));
		collector.handle("[VIP] Alice: gg", t.plusMillis(200));
		collector.handle("From [VIP] Alice: gg", t.plusMillis(300));
		collector.onDisconnect();

		assertEquals(4, messages(0).size());
	}

	@Test
	void playersAreComparedIgnoringCapitalsForRepeats() {
		Instant t = Instant.parse("2026-09-21T15:00:00Z");
		collector.handle("[MVP+] TEST: gg", t);
		collector.handle("[MVP+] test: gg", t.plusMillis(100));
		collector.onDisconnect();

		assertEquals(1, messages(0).size());
	}

	@Test
	void yourOwnPublicMessageNeedsYouToBeInTheTabList() {
		lobby = Set.of("alice");
		collector.onLine("[MVP+] WeTrollALittle: a line that looks like mine but I am not listed");
		collector.onDisconnect();

		assertEquals(0, sent.size());
	}

	@Test
	void nothingIsCollectedWhileTheScannerOrChatSettingIsOff() {
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
		}, () -> true, () -> "Me", () -> {
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
