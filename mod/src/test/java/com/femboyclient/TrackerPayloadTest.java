package com.femboyclient;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TrackerPayloadTest {

	@Test
	void aCrystalHollowsMessageAnOlderJarLeftInTheQueueIsSentAsOneAndNeverThrows() {
		// The website no longer takes these: it refuses the message and it is dropped. Logging its size must not throw first.
		JsonObject old = JsonParser.parseString(
				"{\"me\":\"Me\",\"lobby\":\"abc\",\"structure\":\"\",\"x\":1,\"y\":2,\"z\":3,\"timestamp\":\"2026-10-12T00:00:00Z\"}")
				.getAsJsonObject();

		assertEquals(1, TrackerPayload.size(old));
		assertEquals("/tracker/party", TrackerPayload.path(old));
	}

	@Test
	void aPartyMessageCountsItsPlayers() {
		JsonObject party = TrackerPayload.party("Me", java.util.List.of("Bob", "Ann"), java.time.Instant.now());

		assertEquals(2, TrackerPayload.size(party));
		assertEquals("/tracker/party", TrackerPayload.path(party));
	}
}
