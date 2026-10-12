package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerInfoTest {

	private static PlayerInfo read(int status, String body) {
		return PlayerInfo.fromResponse(new WebClient.Response(status, body, null));
	}

	@Test
	void aFullAnswerIsRead() {
		PlayerInfo info = read(200, "{\"found\":true,\"name\":\"Alice\",\"discord\":\"alice_dc\",\"discordError\":null,\"timesMet\":2,"
				+ "\"chatLogs\":[{\"at\":\"t1\",\"channel\":\"Party\",\"world\":\"Hub\",\"message\":\"yo\"},"
				+ "{\"at\":\"t0\",\"channel\":\"Public\",\"world\":\"The End\",\"message\":\"hi\"}]}");

		assertEquals(PlayerInfo.Status.FOUND, info.status());
		assertEquals("Alice", info.name());
		assertEquals("alice_dc", info.discord());
		assertNull(info.discordError());
		assertEquals(2, info.timesMet());
		assertEquals(2, info.chatLogs().size());
		assertEquals(new PlayerInfo.ChatLog("t1", "Party", "Hub", "yo"), info.chatLogs().get(0));
		assertNull(info.error());
	}

	@Test
	void aPlayerNotInTheDatabaseIsNotAnError() {
		PlayerInfo info = read(200, "{\"found\":false,\"name\":null,\"discord\":null,\"timesMet\":0,\"chatLogs\":[]}");

		assertEquals(PlayerInfo.Status.NOT_IN_DATABASE, info.status());
		assertNull(info.discord());
		assertEquals(List.of(), info.chatLogs());
	}

	@Test
	void aFailedDiscordLookupKeepsTheRestOfTheAnswer() {
		PlayerInfo info = read(200, "{\"found\":true,\"discord\":null,\"discordError\":\"Hypixel took too long\",\"timesMet\":5,\"chatLogs\":[]}");

		assertEquals(PlayerInfo.Status.FOUND, info.status());
		assertEquals("Hypixel took too long", info.discordError());
		assertEquals(5, info.timesMet());
	}

	@Test
	void missingFieldsDefaultInsteadOfCrashing() {
		PlayerInfo info = read(200, "{\"found\":true}");

		assertEquals(0, info.timesMet());
		assertEquals(List.of(), info.chatLogs());
		assertNull(info.discord());
	}

	@Test
	void everyKindOfFailureIsAnErrorWithAReason() {
		assertEquals(PlayerInfo.Status.ERROR, PlayerInfo.fromResponse(new WebClient.Response(-1, "", "timed out")).status());
		assertTrue(PlayerInfo.fromResponse(new WebClient.Response(-1, "", "timed out")).error().contains("timed out"));
		assertTrue(read(401, "{}").error().contains("key"));
		assertTrue(read(500, "{}").error().contains("500"));
		assertEquals(PlayerInfo.Status.ERROR, read(200, "not json").status());
		assertEquals(PlayerInfo.Status.ERROR, read(200, "[]").status());
		assertEquals(PlayerInfo.Status.ERROR, read(200, "{\"found\":true,\"chatLogs\":[5]}").status());
	}

	@Test
	void theRankAndTheNetworthAreRead() {
		PlayerInfo info = read(200, "{\"found\":true,\"name\":\"Alice\",\"rank\":\"MVP++\",\"networth\":1725179197,\"networthNote\":null,\"timesMet\":1,\"chatLogs\":[]}");

		assertEquals("MVP++", info.rank());
		assertEquals(1_725_179_197L, info.networth());
		assertNull(info.networthNote());
	}

	@Test
	void aMissingNetworthKeepsTheReasonWhy() {
		PlayerInfo off = read(200, "{\"found\":true,\"rank\":\"none\",\"networth\":null,\"networthNote\":\"api_off\",\"chatLogs\":[]}");

		assertNull(off.networth());
		assertEquals("api_off", off.networthNote());
		assertEquals("none", off.rank());
	}

	@Test
	void anOldWebsiteWithoutRankOrNetworthStillWorks() {
		PlayerInfo info = read(200, "{\"found\":true,\"discord\":\"a_b\",\"timesMet\":2,\"chatLogs\":[]}");

		assertNull(info.rank());
		assertNull(info.networth());
		assertEquals("a_b", info.discord());
	}

	@Test
	void aNetworthThatIsNotANumberIsIgnoredNotACrash() {
		assertNull(read(200, "{\"found\":true,\"networth\":\"lots\",\"chatLogs\":[]}").networth());
		assertNull(read(200, "{\"found\":true,\"networth\":[1],\"chatLogs\":[]}").networth());
		assertEquals(PlayerInfo.Status.FOUND, read(200, "{\"found\":true,\"networth\":true,\"chatLogs\":[]}").status());
	}

	@Test
	void aFailureHasNoRankOrNetworth() {
		PlayerInfo failed = PlayerInfo.failed("nope");

		assertNull(failed.rank());
		assertNull(failed.networth());
	}
}

