package com.femboyclient;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The color math behind the player screen's drawing helpers. (The shape math is in ShapeMathTest.) */
class UiMathTest {

	@Test
	void alphaHelpersKeepTheColorAndChangeOnlyTheAlpha() {
		assertEquals(0x80112233, Colors.withAlpha(0xFF112233, 0x80));
		assertEquals(0x00112233, Colors.withAlpha(0xFF112233, -5));
		assertEquals(0xFF112233, Colors.withAlpha(0x00112233, 999));
		assertEquals(0xFF, Colors.alpha(0xFF000000));
	}

	@Test
	void scalingTheAlphaFadesAColor() {
		assertEquals(0x80112233, Colors.scaleAlpha(0xFF112233, 0.5f));
		assertEquals(0xFF112233, Colors.scaleAlpha(0xFF112233, 2f));
		assertEquals(0x00112233, Colors.scaleAlpha(0xFF112233, -1f));
	}

	@Test
	void lerpBlendsEveryChannelAndClampsT() {
		assertEquals(0xFF000000, Colors.lerp(0xFF000000, 0xFFFFFFFF, 0f));
		assertEquals(0xFFFFFFFF, Colors.lerp(0xFF000000, 0xFFFFFFFF, 1f));
		assertEquals(0x80808080, Colors.lerp(0x00000000, 0xFFFFFFFF, 0.5f));
		assertEquals(0xFFFFFFFF, Colors.lerp(0xFF000000, 0xFFFFFFFF, 9f));
		assertEquals(0xFF000000, Colors.lerp(0xFF000000, 0xFFFFFFFF, -9f));
	}
}
