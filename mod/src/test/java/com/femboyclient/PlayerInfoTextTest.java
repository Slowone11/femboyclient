package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerInfoTextTest {

	private static PlayerInfo info(String discord, String discordError, int times, List<PlayerInfo.ChatLog> logs) {
		return new PlayerInfo(PlayerInfo.Status.FOUND, "Alice", discord, discordError, times, logs, null);
	}

	@Test
	void aUtcTimeIsShownInTorontoTime() {
		assertEquals("Sep 21, 11:01 AM", PlayerInfoText.when("2026-09-21T15:01:00.000Z"));       // daylight time, UTC-4
		assertEquals("Jan 5, 7:30 PM", PlayerInfoText.when("2026-01-06T00:30:00.000Z"));          // standard time, UTC-5
	}

	@Test
	void anUnreadableTimeIsShownAsItIs() {
		assertEquals("yesterday", PlayerInfoText.when("yesterday"));
		assertEquals("unknown time", PlayerInfoText.when(null));
	}

	@Test
	void theDiscordLineSaysWhatIsKnown() {
		assertEquals("Discord: alice_dc", PlayerInfoText.discordLine(info("alice_dc", null, 1, List.of())));
		assertEquals("Discord: none linked", PlayerInfoText.discordLine(info(null, null, 1, List.of())));
		assertEquals("Discord: unavailable (Hypixel took too long)", PlayerInfoText.discordLine(info(null, "Hypixel took too long", 1, List.of())));
	}

	@Test
	void timesMetAndTheChatTitle() {
		PlayerInfo.ChatLog log = new PlayerInfo.ChatLog("2026-09-21T15:01:00.000Z", "Public", "Hub", "hi");
		assertEquals("Times met: 4", PlayerInfoText.timesMetLine(info(null, null, 4, List.of())));
		assertEquals("Chat logs: none yet", PlayerInfoText.chatTitle(info(null, null, 4, List.of())));
		assertEquals("Chat logs (2)", PlayerInfoText.chatTitle(info(null, null, 4, List.of(log, log))));
	}

	@Test
	void aLogHeaderShowsTheChannelAndWorldOnlyWhenTheySaySomething() {
		String t = "2026-09-21T15:01:00.000Z";
		assertEquals("Sep 21, 11:01 AM · Hub", PlayerInfoText.logHeader(new PlayerInfo.ChatLog(t, "Public", "Hub", "x")));
		assertEquals("Sep 21, 11:01 AM · Party · The End", PlayerInfoText.logHeader(new PlayerInfo.ChatLog(t, "Party", "The End", "x")));
		assertEquals("Sep 21, 11:01 AM", PlayerInfoText.logHeader(new PlayerInfo.ChatLog(t, "Public", "Unknown", "x")));
		assertEquals("Sep 21, 11:01 AM", PlayerInfoText.logHeader(new PlayerInfo.ChatLog(t, null, null, "x")));
	}

	@Test
	void theDiscordValueHasNoLabelBecauseTheScreenShowsAnIcon() {
		assertEquals("alice_dc", PlayerInfoText.discordValue(info("alice_dc", null, 1, List.of())));
		assertEquals("none linked", PlayerInfoText.discordValue(info(null, null, 1, List.of())));
		assertEquals("unavailable (Hypixel took too long)", PlayerInfoText.discordValue(info(null, "Hypixel took too long", 1, List.of())));
	}

	@Test
	void theNetworthShowsShortOrSaysWhyThereIsNone() {
		PlayerInfo with = new PlayerInfo(PlayerInfo.Status.FOUND, "A", null, null, 0, List.of(), null, "MVP+", 1_725_179_197L, null);
		PlayerInfo off = new PlayerInfo(PlayerInfo.Status.FOUND, "A", null, null, 0, List.of(), null, "MVP+", null, "api_off");
		PlayerInfo none = new PlayerInfo(PlayerInfo.Status.FOUND, "A", null, null, 0, List.of(), null, "MVP+", null, "no_profile");
		PlayerInfo broken = new PlayerInfo(PlayerInfo.Status.FOUND, "A", null, null, 0, List.of(), null, "MVP+", null, "Hypixel took too long");
		PlayerInfo unknown = new PlayerInfo(PlayerInfo.Status.FOUND, "A", null, null, 0, List.of(), null);

		assertEquals("1.7B", PlayerInfoText.networthValue(with));
		assertEquals("API off", PlayerInfoText.networthValue(off));
		assertEquals("No profile", PlayerInfoText.networthValue(none));
		assertEquals("N/A", PlayerInfoText.networthValue(broken));
		assertEquals("N/A", PlayerInfoText.networthValue(unknown));
	}

	@Test
	void theSearchIgnoresCapitalsAndMatchesAnyPartOfTheName() {
		assertEquals(true, PlayerInfoText.matches("WeTrollALittle", "troll"));
		assertEquals(true, PlayerInfoText.matches("WeTrollALittle", "  LITTLE "));
		assertEquals(false, PlayerInfoText.matches("WeTrollALittle", "bob"));
		assertEquals(true, PlayerInfoText.matches("Alice", ""));
		assertEquals(true, PlayerInfoText.matches("Alice", "   "));
		assertEquals(true, PlayerInfoText.matches("Alice", null));
	}
}

