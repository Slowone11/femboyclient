package com.femboyclient;

/**
 * The MansionFinder module's one bit of non-visual logic: putting the corner map back where it started. Everything else (the
 * on/off switch, dragging, resizing, turning) is a direct config field or lives in {@link HollowsMapScreen} and
 * {@link HollowsMapDrag}.
 */
final class HollowsMap {

	private HollowsMap() {
	}

	/** Puts the map back in the top left corner at its normal size. Does not touch whether it is shown at all. */
	static void reset(FemboyClientConfig config) {
		config.hudX = 4;
		config.hudY = 4;
		config.hudSize = 150;
	}
}
