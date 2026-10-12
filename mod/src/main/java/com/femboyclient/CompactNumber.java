package com.femboyclient;

/**
 * Short numbers for the stat cards: 1.2B, 350M, 12.5K. Three digits are kept: one decimal below 100 of a unit, none from
 * 100 up, and a trailing ".0" is dropped. Kept free of Minecraft classes so it can be tested on its own.
 */
public final class CompactNumber {

	private static final String[] SUFFIXES = {"", "K", "M", "B", "T"};

	private CompactNumber() {
	}

	/** Formats a number of coins, for example 1_725_179_197 as "1.7B". Negative numbers keep their sign. */
	public static String format(long value) {
		if (value < 0) {
			return value == Long.MIN_VALUE ? "-9.2T+" : "-" + format(-value);
		}
		double scaled = value;
		int unit = 0;
		while (scaled >= 1000 && unit < SUFFIXES.length - 1) {
			scaled /= 1000;
			unit++;
		}
		if (unit == 0) {
			return Long.toString(value);
		}

		// Round to the digits that are shown, and move up a unit if that rounds to 1000 (999.96K is "1M", not "1000K").
		long shown = scaled >= 100 ? Math.round(scaled) : Math.round(scaled * 10);
		double display = scaled >= 100 ? shown : shown / 10.0;
		if (display >= 1000 && unit < SUFFIXES.length - 1) {
			return "1" + SUFFIXES[unit + 1];
		}
		if (scaled >= 100 || shown % 10 == 0) {
			return (scaled >= 100 ? shown : shown / 10) + SUFFIXES[unit];
		}
		return (shown / 10) + "." + (shown % 10) + SUFFIXES[unit];
	}
}
