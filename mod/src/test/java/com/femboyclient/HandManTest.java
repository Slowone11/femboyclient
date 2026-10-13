package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for HandMan's numbers and its settings in the config. */
class HandManTest {

	@Test
	void zeroSpeedKeepsTheGamesSwing() {
		assertEquals(6, HandMan.swingDuration(6, 6, 0, false));
		assertEquals(4, HandMan.swingDuration(4, 6, 0, false), "haste still counts while Ignore Haste is off");
	}

	@Test
	void speedShortensOrLengthensTheSwing() {
		assertEquals(2, HandMan.swingDuration(6, 6, 100, false));    // 6 / e
		assertEquals(44, HandMan.swingDuration(6, 6, -200, false));  // 6 * e²
	}

	@Test
	void ignoreHasteUsesTheItemsOwnLength() {
		assertEquals(6, HandMan.swingDuration(3, 6, 0, true));
	}

	@Test
	void swingIsNeverShorterThanOneTick() {
		assertEquals(1, HandMan.swingDuration(1, 1, 100, false));
	}

	@Test
	void menuTexts() {
		assertEquals("100%", HandMan.percent(0));
		assertEquals("272%", HandMan.percent(100));
		assertEquals("+0.25", HandMan.offset(25));
		assertEquals("-1.50", HandMan.offset(-150));
		assertEquals(1F, HandMan.scale(0));
	}

	@Test
	void rangesMatchOdin() {
		assertEquals(-150, HandMan.SIZE.clamp(-999));
		assertEquals(150, HandMan.SIZE.clamp(999));
		assertEquals(-250, HandMan.X.clamp(-999));
		assertEquals(300, HandMan.Z.clamp(999));
		assertEquals(180, HandMan.ROTATION.clamp(999));
		assertEquals(-200, HandMan.SPEED.clamp(-999));
		assertEquals(100, HandMan.SPEED.clamp(999));
	}

	@Test
	void offByDefaultWithTheGamesOwnLook() throws IOException {
		FemboyClientConfig config = FemboyClientConfig.fromProperties(new Properties(), new Properties());
		assertFalse(config.handMan);
		assertEquals(0, config.handSize);
		assertEquals(0, config.handSpeed);
		assertFalse(config.handNoSwing);
	}

	@Test
	void settingsAreReadClampedAndWritten() throws IOException {
		Properties file = new Properties();
		file.load(new StringReader("handMan=true\nhandSize=40\nhandYaw=500\nhandSpeed=-50\nhandBlockHit=true"));
		FemboyClientConfig config = FemboyClientConfig.fromProperties(new Properties(), file);

		assertTrue(config.handMan);
		assertEquals(40, config.handSize);
		assertEquals(180, config.handYaw, "out of range is clamped");
		assertEquals(-50, config.handSpeed);
		assertTrue(config.handBlockHit);
		Properties written = config.toProperties();
		assertEquals("40", written.getProperty("handSize"));
		assertEquals("180", written.getProperty("handYaw"));
		assertEquals("true", written.getProperty("handBlockHit"));
	}
}
