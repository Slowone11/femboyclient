package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PurseParserTest {

	@Test
	void parsesPlainNumbers() {
		assertEquals(12345678L, PurseParser.parseCoins(" 12,345,678"));
		assertEquals(999L, PurseParser.parseCoins(" 999"));
		assertEquals(0L, PurseParser.parseCoins(" 0"));
		assertEquals(9876543210L, PurseParser.parseCoins(" 9,876,543,210"));
	}

	@Test
	void dropsTheRecentGainSuffix() {
		assertEquals(1234L, PurseParser.parseCoins(" 1,234 (+500)"));
	}

	@Test
	void ignoresPaddingIncludingNonBreakingSpaces() {
		assertEquals(5000L, PurseParser.parseCoins(" 5,000 "));
	}

	@Test
	void refusesAnythingItCannotTrust() {
		assertNull(PurseParser.parseCoins(" 1.2M"));
		assertNull(PurseParser.parseCoins("   "));
		assertNull(PurseParser.parseCoins(" abc"));
		assertNull(PurseParser.parseCoins(" 12a3"));
		assertNull(PurseParser.parseCoins(""));
		assertNull(PurseParser.parseCoins(" 99999999999999999999999")); // overflows a long
	}

	@Test
	void doesNotAcceptDigitsFromOtherScripts() {
		assertNull(PurseParser.parseCoins(" ٥٦")); // Arabic-Indic digits
	}

	@Test
	void findsThePurseLineWhicheverNameSkyBlockUses() {
		assertEquals(5637575L, PurseParser.findPurse(List.of("10:30pm", "Piggy: 5,637,575", "Bits: 740")));
		assertEquals(42L, PurseParser.findPurse(List.of("Purse: 42")));
	}

	@Test
	void returnsNullWhenThereIsNoPurseLine() {
		assertNull(PurseParser.findPurse(List.of("Bits: 740", "www.hypixel.net")));
		assertNull(PurseParser.findPurse(List.of()));
	}

	@Test
	void theStripperTurnsHypixelsPaddedLineIntoARealNumber() {
		// Hypixel puts meaningless colour codes in the middle of text, like this line from the log.
		String raw = "Piggy: 5,637,5§j75";
		assertEquals(5637575L, PurseParser.findPurse(List.of(ScoreboardReader.strip(raw))));
	}
}
