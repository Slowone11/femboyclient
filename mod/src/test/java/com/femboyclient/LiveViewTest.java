package com.femboyclient;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveViewTest {

	private static int[] solid(int width, int height, int argb) {
		int[] pixels = new int[width * height];
		java.util.Arrays.fill(pixels, argb);
		return pixels;
	}

	@Test
	void theSizeKeepsTheShapeAndNeverGrows() {
		assertArrayEquals(new int[] {640, 360}, LiveFrames.sizeFor(1920, 1080, 640));
		assertArrayEquals(new int[] {320, 200}, LiveFrames.sizeFor(320, 200, 640));
		assertArrayEquals(new int[] {640, 400}, LiveFrames.sizeFor(1600, 1000, 640));
	}

	@Test
	void shrinkingAveragesTheBlocks() {
		// left half red, right half blue, shrunk 4 to 1 in each direction
		int[] pixels = new int[8 * 4];
		for (int y = 0; y < 4; y++) {
			for (int x = 0; x < 8; x++) {
				pixels[y * 8 + x] = x < 4 ? 0xFFFF0000 : 0xFF0000FF;
			}
		}
		assertArrayEquals(new int[] {0xFF0000, 0x0000FF}, LiveFrames.shrink(pixels, 8, 4, 2, 1));
		// a block of two colours gives their average
		int[] mixed = {0xFF000000, 0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF};
		assertEquals(0x7F7F7F, LiveFrames.shrink(mixed, 2, 2, 1, 1)[0]);
	}

	@Test
	void aPictureBecomesASmallJpegWithTheRightColours() throws IOException {
		byte[] jpeg = LiveFrames.encode(solid(1920, 1080, 0xFFC03020), 1920, 1080, 640, 0.6f);
		assertEquals((byte) 0xFF, jpeg[0]);
		assertEquals((byte) 0xD8, jpeg[1]);
		assertTrue(jpeg.length < 20_000, "a flat picture is tiny, was " + jpeg.length);

		BufferedImage back = ImageIO.read(new ByteArrayInputStream(jpeg));
		assertEquals(640, back.getWidth());
		assertEquals(360, back.getHeight());
		int centre = back.getRGB(320, 180);
		assertTrue(Math.abs(((centre >> 16) & 0xFF) - 0xC0) < 12, "red " + Integer.toHexString(centre));
		assertTrue(Math.abs(((centre >> 8) & 0xFF) - 0x30) < 12, "green " + Integer.toHexString(centre));
		assertTrue(Math.abs((centre & 0xFF) - 0x20) < 12, "blue " + Integer.toHexString(centre));
	}

	@Test
	void aBadPictureIsRefused() {
		try {
			LiveFrames.encode(new int[4], 10, 10, 640, 0.6f);
			org.junit.jupiter.api.Assertions.fail("expected an error");
		} catch (IOException expected) {
			// fine
		}
	}

	@Test
	void picturesGoFastWhileWatchedAndSlowlyOtherwise() {
		List<byte[]> sent = new ArrayList<>();
		int[] answer = {0};
		LiveView view = new LiveView(FemboyClientConfig::new, () -> true, (jpeg, state) -> {
			sent.add(jpeg);
			return answer[0];
		});

		assertTrue(view.due(LiveView.IDLE_EVERY_MS));
		assertFalse(view.due(LiveView.IDLE_EVERY_MS - 1));
		view.process(solid(64, 36, 0xFF000001), 64, 36);
		assertEquals(1, sent.size());
		assertFalse(view.watched());

		answer[0] = 1;
		view.process(solid(64, 36, 0xFF000002), 64, 36);
		assertTrue(view.watched());
		answer[0] = -1;                                   // not taken: what the page was doing is not known, so it stays as it was
		view.process(solid(64, 36, 0xFF000003), 64, 36);
		assertTrue(view.watched());
		answer[0] = 0;
		view.process(solid(64, 36, 0xFF000004), 64, 36);
		assertFalse(view.watched());
	}

	@Test
	void theLiveViewNeverShowsAChatMessage() {
		// No notice callback exists any more: the live view is silent, always on, with no toggle to announce.
		List<byte[]> sent = new ArrayList<>();
		LiveView view = new LiveView(FemboyClientConfig::new, () -> true, (jpeg, state) -> {
			sent.add(jpeg);
			return 1;
		});
		view.process(solid(64, 36, 0xFF000000), 64, 36);
		assertEquals(1, sent.size());
		assertTrue(view.watched());
	}

	@Test
	void anUnchangedScreenIsNotSentAgainWhileWatchedButIsWhenItChanges() {
		List<byte[]> sent = new ArrayList<>();
		List<String> states = new ArrayList<>();
		LiveView view = new LiveView(FemboyClientConfig::new, () -> true, (jpeg, state) -> {
			sent.add(jpeg);
			states.add(state);
			return 1;                                              // the page is watching
		});
		int[] screen = solid(64, 36, 0xFF336699);

		view.process(screen, 64, 36, "{\"slot\":0}");           // first picture always goes
		view.process(screen, 64, 36, "{\"slot\":0}");           // same screen, same state: not sent again
		view.process(screen.clone(), 64, 36, "{\"slot\":0}");
		assertEquals(1, sent.size());

		view.process(solid(64, 36, 0xFF993366), 64, 36, "{\"slot\":0}");      // the screen changed
		assertEquals(2, sent.size());
		view.process(solid(64, 36, 0xFF993366), 64, 36, "{\"slot\":1}");      // same screen but the held slot changed
		assertEquals(3, sent.size());
		assertEquals("{\"slot\":1}", states.get(2));
	}

	@Test
	void anUnchangedScreenIsStillSentWhenNobodyIsWatching() {
		List<byte[]> sent = new ArrayList<>();
		LiveView view = new LiveView(FemboyClientConfig::new, () -> true, (jpeg, state) -> {
			sent.add(jpeg);
			return 0;
		});
		int[] screen = solid(64, 36, 0xFF336699);
		view.process(screen, 64, 36, "{}");
		view.process(screen, 64, 36, "{}");
		assertEquals(2, sent.size());       // this is how the game learns the page opened
	}

	@Test
	void theAnswerToAnInputRequestTellsWhetherThePageIsStillWatching() {
		assertEquals(Boolean.TRUE, LiveInput.watching("{\"events\":[],\"viewers\":1}"));
		assertEquals(Boolean.FALSE, LiveInput.watching("{\"events\":[],\"viewers\":0}"));
		org.junit.jupiter.api.Assertions.assertNull(LiveInput.watching("{\"events\":[]}"));
		org.junit.jupiter.api.Assertions.assertNull(LiveInput.watching("not json"));
	}
}
