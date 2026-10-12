package com.femboyclient;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The whole party path from a chat line to the message for the website. */
class PartyTrackerTest {

	private final List<JsonObject> sent = new ArrayList<>();
	private boolean allowed;
	private PartyTracker tracker;

	@BeforeEach
	void setUp() {
		sent.clear();
		allowed = true;
		tracker = new PartyTracker(sent::add, () -> allowed, () -> "WeTrollALittle");
	}

	private List<String> names(int message) {
		return sent.get(message).getAsJsonArray("players").asList().stream().map(e -> e.getAsString()).toList();
	}

	@Test
	void aPlayerWhoJoinsIsReportedOnce() {
		tracker.onLine("[MVP+] Alice joined the party.");
		tracker.onLine("Party > [MVP+] Alice: hi");
		tracker.onLine("Party > [MVP+] Alice: hi again");

		assertEquals(1, sent.size());
		assertEquals(List.of("Alice"), names(0));
	}

	@Test
	void theMessageHasOnlyUsernamesAndATimeNeverTheChatLine() {
		tracker.onLine("[MVP+] Alice joined the party.");

		JsonObject message = sent.get(0);
		assertEquals(java.util.Set.of("me", "players", "timestamp"), message.keySet());
		assertEquals("WeTrollALittle", message.get("me").getAsString());
		assertTrue(TrackerPayload.isParty(message));
		assertFalse(message.toString().contains("joined"));
		Instant.parse(message.get("timestamp").getAsString());
	}

	@Test
	void anInviteOrARequestNeverSendsAnything() {
		tracker.onLine("[MVP+] Alice has invited you to join their party!");
		tracker.onLine("You have 60 seconds to accept. Click here to join!");
		tracker.onLine("You invited Bob to your party! They have 60 seconds to accept.");
		tracker.onLine("Alice has requested to join your party.");
		tracker.onLine("The party invite to Bob has expired.");

		assertEquals(List.of(), sent);
	}

	@Test
	void acceptingAnInviteReportsTheLeaderAndEveryoneAlreadyThere() {
		tracker.onLine("[MVP+] Zed has invited you to join their party!");
		assertEquals(0, sent.size());

		tracker.onLine("You have joined [MVP+] Zed's party!");
		tracker.onLine("You'll be partying with: [VIP] Amy, Bob");

		assertEquals(List.of("Zed"), names(0));
		assertEquals(List.of("Amy", "Bob"), names(1));
	}

	@Test
	void yourOwnNameIsNeverReported() {
		tracker.onLine("[MVP+] WeTrollALittle joined the party.");
		tracker.onLine("Party > [MVP+] WeTrollALittle: hello");

		assertEquals(0, sent.size());
	}

	@Test
	void leavingAndRejoiningIsReportedAgain() {
		tracker.onLine("Alice joined the party.");
		tracker.onLine("Alice has left the party.");
		tracker.onLine("Alice joined the party.");

		assertEquals(2, sent.size());
	}

	@Test
	void leavingThePartyMeansTheNextPartyCountsEveryoneAgain() {
		tracker.onLine("Alice joined the party.");
		tracker.onLine("You left the party.");
		tracker.onLine("You have joined Zed's party!");
		tracker.onLine("You'll be partying with: Alice");

		assertEquals(3, sent.size());
		assertEquals(List.of("Alice"), names(2));
	}

	@Test
	void nothingIsReportedOrRememberedWhileTheScannerIsOff() {
		allowed = false;
		tracker.onLine("Alice joined the party.");
		assertEquals(0, sent.size());

		allowed = true;
		tracker.onLine("Party > Alice: hi");     // first time the tracker could see her
		assertEquals(List.of("Alice"), names(0));
	}

	@Test
	void disconnectingForgetsTheParty() {
		tracker.onLine("Alice joined the party.");
		tracker.onDisconnect();
		tracker.onLine("Party > Alice: still here");

		assertEquals(2, sent.size());
	}

	@Test
	void aMessageWithSeveralLinesIsReadOneLineAtATime() {
		tracker.onLine("-------------------\nParty Leader: [VIP] Zed ●\nParty Members: [MVP+] Alice ● Bob ●\n-------------------");

		assertEquals(List.of("Zed"), names(0));
		assertEquals(List.of("Alice", "Bob"), names(1));
	}

	@Test
	void aFakeLineInPublicChatReportsNothing() {
		tracker.onLine("[248] ❤ [MVP+] Eve: Bob joined the party.");
		tracker.onLine("Eve: You'll be partying with: Bob, Carol");

		assertEquals(0, sent.size());
	}

	@Test
	void oddInputNeverThrows() {
		tracker.onLine(null);
		tracker.onLine("");
		tracker.onLine("\n\n");
		tracker.onLine("party party party");
		tracker.onLine("Party > ");
		tracker.onLine("x".repeat(10_000));

		assertEquals(0, sent.size());
	}

	@Test
	void aBrokenSinkDoesNotEscapeIntoTheGame() {
		PartyTracker broken = new PartyTracker(message -> {
			throw new IllegalStateException("website link exploded");
		}, () -> true, () -> "Me");

		broken.onLine("Alice joined the party.");   // must not throw
	}
}
