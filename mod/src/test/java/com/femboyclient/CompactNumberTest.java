package com.femboyclient;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CompactNumberTest {

	@Test
	void theExamplesFromTheSpec() {
		assertEquals("1.2B", CompactNumber.format(1_200_000_000L));
		assertEquals("350M", CompactNumber.format(350_000_000L));
		assertEquals("12.5K", CompactNumber.format(12_500L));
	}

	@Test
	void smallNumbersAreShownInFull() {
		assertEquals("0", CompactNumber.format(0));
		assertEquals("7", CompactNumber.format(7));
		assertEquals("999", CompactNumber.format(999));
	}

	@Test
	void oneDecimalBelowAHundredOfAUnitAndNoneAbove() {
		assertEquals("1K", CompactNumber.format(1_000));
		assertEquals("1.5K", CompactNumber.format(1_500));
		assertEquals("99.9K", CompactNumber.format(99_900));
		assertEquals("100K", CompactNumber.format(100_000));
		assertEquals("999K", CompactNumber.format(999_499));
		assertEquals("1.7B", CompactNumber.format(1_725_179_197L));
		assertEquals("40M", CompactNumber.format(40_041_315L));
		assertEquals("5T", CompactNumber.format(5_000_000_000_000L));
	}

	@Test
	void aNumberThatRoundsUpToAThousandMovesUpAUnit() {
		assertEquals("1M", CompactNumber.format(999_950L));
		assertEquals("1M", CompactNumber.format(999_999L));
		assertEquals("1B", CompactNumber.format(999_999_999L));
		assertEquals("1.1K", CompactNumber.format(1_050L));
	}

	@Test
	void aTrailingZeroDecimalIsDropped() {
		assertEquals("2M", CompactNumber.format(2_000_000L));
		assertEquals("2M", CompactNumber.format(2_020_000L));
		assertEquals("2.1M", CompactNumber.format(2_100_000L));
	}

	@Test
	void negativeNumbersAndTheBiggestNumbersDoNotBreak() {
		assertEquals("-1.5K", CompactNumber.format(-1_500));
		assertEquals("-350M", CompactNumber.format(-350_000_000L));
		assertEquals("9223372T", CompactNumber.format(Long.MAX_VALUE));
		assertEquals("-9.2T+", CompactNumber.format(Long.MIN_VALUE));
	}
}
