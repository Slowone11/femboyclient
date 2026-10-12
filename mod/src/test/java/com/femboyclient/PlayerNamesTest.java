package com.femboyclient;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerNamesTest {

	@ParameterizedTest(name = "valid: {0}")
	@ValueSource(strings = {"Steve", "abc", "A_B", "Player_123", "1234", "a1b2c3d4e5f6g7h8", "___", "Notch"})
	void realUsernamesAreAccepted(String name) {
		assertTrue(PlayerNames.isValid(name));
	}

	@ParameterizedTest(name = "invalid: [{0}]")
	@ValueSource(strings = {
			"", " ", "ab", "a", "abcdefghijklmnopq",       // blank, too short, 17 characters
			"!A-a", "!B-b", "Hello World", "Name!", "na-me", "na.me", "§aSteve",
			"[NPC] Bob", "René", "あいう", "tab\tname", "line\nbreak"
	})
	void fakeAndBrokenEntriesAreRejected(String name) {
		assertFalse(PlayerNames.isValid(name));
	}

	@Test
	void nullIsNotAName() {
		assertFalse(PlayerNames.isValid(null));
	}

	@Test
	void cleanKeepsOnlyValidNamesOnceEachAndSorted() {
		List<String> result = PlayerNames.clean(
				Arrays.asList("Zed", "alice", "!A-a", "", "Bob", "ALICE", "Alice", null, "x", "Bob"),
				"someoneElse");

		assertEquals(List.of("alice", "Bob", "Zed"), result, "one per name whatever the case, the first spelling kept");
	}

	@Test
	void cleanRemovesTheOwnNameIgnoringCase() {
		List<String> result = PlayerNames.clean(List.of("WeTrollALittle", "Steve", "wetrollalittle"), "WETROLLALITTLE");
		assertEquals(List.of("Steve"), result);
	}

	@Test
	void cleanRemovesEveryOwnNameAndIgnoresNullOnes() {
		List<String> result = PlayerNames.clean(List.of("Account", "InWorld", "Other"), "Account", null, "InWorld");
		assertEquals(List.of("Other"), result);
	}

	@Test
	void cleanHandlesEmptyInput() {
		assertEquals(List.of(), PlayerNames.clean(List.of()));
		assertEquals(List.of(), PlayerNames.clean(List.of("!A-a", "", "ab")));
	}

	// ------------------------------------------------------------- the payload

	@Test
	void thePayloadCarriesOnlyUsernamesAndTimes() {
		JsonObject json = TrackerPayload.build("WeTrollALittle", "Crystal Hollows", List.of("Alice", "Bob"),
				Instant.parse("2026-09-21T19:41:03.250Z"), Instant.parse("2026-09-21T19:40:55Z"));

		assertEquals(Set.of("me", "world", "players", "timestamp", "visit_started"), json.keySet(),
				"nothing else may be added: no chat, addresses or server details");
		assertEquals("Crystal Hollows", json.get("world").getAsString());
		assertEquals("WeTrollALittle", json.get("me").getAsString());
		assertEquals("Alice", json.getAsJsonArray("players").get(0).getAsString());
		assertEquals(2, json.getAsJsonArray("players").size());
		assertEquals("2026-09-21T19:41:03.250Z", json.get("timestamp").getAsString());
		assertEquals("2026-09-21T19:40:55Z", json.get("visit_started").getAsString());
	}

	@Test
	void timesAreAlwaysUtc() {
		JsonObject json = TrackerPayload.build("Me", "Hub", List.of("Alice"), Instant.EPOCH, Instant.EPOCH);
		assertTrue(json.get("timestamp").getAsString().endsWith("Z"));
		assertTrue(json.get("visit_started").getAsString().endsWith("Z"));
	}

	@Test
	void anEmptyPlayerListStillBuilds() {
		assertEquals(0, TrackerPayload.build("Me", "Hub", List.of(), Instant.EPOCH, Instant.EPOCH)
				.getAsJsonArray("players").size());
	}
}
