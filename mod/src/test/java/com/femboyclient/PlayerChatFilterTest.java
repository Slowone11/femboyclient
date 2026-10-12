package com.femboyclient;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The privacy gate. The first group of lines are real ones that leaked into the
 * database before the filter was fixed, so they must never be let through again.
 * The server has the same lists in server/tests/test_api.py.
 */
class PlayerChatFilterTest {

	@ParameterizedTest(name = "dropped: {0}")
	@ValueSource(strings = {
			// Real Hypixel lines: SkyBlock level, guild emblem and rank before the name.
			"[371] ✿ [MVP+] CatBoyLangtu_: Ming",
			"[120] ⌬ [VIP+] rogatxa: watchu doing here in the hub",
			"[365] ᛝ [MVP+] DonQuarion: [365] ᛝ [MVP+] DonQuarion: buying items",
			"[211] ☠ [VIP] Sickah: ROYO_VX get a pig pet u noob",
			"[120] ⌬ [VIP+] rogatxa: bro got the free booster cookie :skull:",
			// The other shapes.
			"[MVP+] Notch: hey",
			"Steve: hello",
			"Party > [VIP] Steve: lets go",
			"Guild > [MVP+] Alex [Officer]: gg",
			"Co-op > Dave: hello",
			"From [MVP++] Bob: hi",
			"To Bob: hi"
	})
	void playerChatIsRecognised(String line) {
		assertEquals(true, PlayerChatFilter.looksLikePlayerChat(line));
	}

	@ParameterizedTest(name = "kept: {0}")
	@ValueSource(strings = {
			"[Bazaar] Bought 64x Enchanted Bone for 56,026 coins!",
			"[Bazaar] Sold 192x Enchanted Magma Cream for 278,246 coins!",
			"[Bazaar] Executing instant sell...",
			"ALLOWANCE! You earned 50,000 coins!",
			"[MVP+] WeTrollALittle joined the lobby!",
			"Flight Duration: 21:49:48",
			"Latest update: SkyBlock v0.27.1 CLICK",
			"You have 27 items stashed away!",
			"Welcome to Hypixel SkyBlock!",
			"You collected 5,000 coins from the auction house",
			// "To " and "From " are only private messages when a name and colon follow.
			"To be claimed later",
			"From the Bazaar you collected 5,000 coins",
			// The mod's own lines.
			"Connected to the server! Meow!",
			"Disconnected from server! >_<"
	})
	void serverMessagesAreKept(String line) {
		assertEquals(false, PlayerChatFilter.looksLikePlayerChat(line));
	}

	@ParameterizedTest(name = "handled without error: [{0}]")
	@ValueSource(strings = {"", " ", ":", "::", "[", "]", "[]", "[[", "] [", ":: hi", "> :", " > ", "From ", "To "})
	void oddInputNeverThrows(String line) {
		PlayerChatFilter.looksLikePlayerChat(line); // must simply return
	}
}
