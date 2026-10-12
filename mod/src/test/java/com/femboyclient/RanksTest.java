package com.femboyclient;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RanksTest {

	private static void expect(String rank, String text, Ranks.Style style) {
		Ranks.Badge badge = Ranks.badge(rank);
		assertEquals(text, badge.text(), rank);
		assertEquals(style, badge.style(), rank);
	}

	@Test
	void everyRankHasItsBadgeAndColor() {
		expect("VIP", "VIP", Ranks.Style.GREEN);
		expect("VIP+", "VIP+", Ranks.Style.GREEN);
		expect("MVP", "MVP", Ranks.Style.AQUA);
		expect("MVP+", "MVP+", Ranks.Style.AQUA);
		expect("MVP++", "MVP++", Ranks.Style.GOLD);
		expect("YOUTUBER", "YOUTUBE", Ranks.Style.RED);
		expect("HELPER", "HELPER", Ranks.Style.BLUE);
		expect("MODERATOR", "MOD", Ranks.Style.DARK_GREEN);
		expect("ADMIN", "ADMIN", Ranks.Style.RED);
	}

	@Test
	void noRankAnUnknownRankAndNoAnswerAllGiveNoBadge() {
		assertNull(Ranks.badge("none"));
		assertNull(Ranks.badge(null));
		assertNull(Ranks.badge(""));
		assertNull(Ranks.badge("SOMETHING_NEW"));
		assertNull(Ranks.badge("mvp+"));                       // the website always sends the exact spelling
	}

	@Test
	void everyStyleHasAFillAndATextColorInTheTheme() {
		assertEquals(Ranks.Style.values().length, Theme.RANK_FILL.length);
		assertEquals(Ranks.Style.values().length, Theme.RANK_TEXT.length);
		for (int color : Theme.RANK_FILL) {
			assertEquals(0xFF, Colors.alpha(color), "badges are solid");
		}
	}
}
