package com.femboyclient;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapeMathTest {

	private static int at(int[] picture, int size, int x, int y) {
		return picture[y * size + x];
	}

	@Test
	void aPictureIsTwoCornersAndATwoPixelMiddle() {
		assertEquals(2, ShapeMath.size(0));
		assertEquals(26, ShapeMath.size(12));
		assertEquals(26 * 26, ShapeMath.fill(12).length);
	}

	@Test
	void theFilledShapeIsSolidInsideAndClearOutsideTheCorner() {
		int radius = 12, size = ShapeMath.size(radius);
		int[] fill = ShapeMath.fill(radius);

		assertEquals(255, at(fill, size, size / 2, size / 2), "the middle is solid");
		assertEquals(255, at(fill, size, radius, 0), "the straight top edge is solid");
		assertEquals(255, at(fill, size, 0, radius), "the straight left edge is solid");
		assertEquals(0, at(fill, size, 0, 0), "the very corner is outside the curve");
		assertEquals(0, at(fill, size, size - 1, size - 1));
	}

	@Test
	void theCornerFadesSmoothlyInsteadOfStairStepping() {
		int radius = 12, size = ShapeMath.size(radius);
		int[] fill = ShapeMath.fill(radius);
		int partial = 0;
		for (int i = 0; i < size * size; i++) {
			if (fill[i] > 0 && fill[i] < 255) {
				partial++;
			}
		}
		assertTrue(partial >= 4 * radius, "the curved edge has many partly covered pixels, not just on and off: " + partial);
	}

	@Test
	void theShapeIsSymmetricInBothDirections() {
		int radius = 9, size = ShapeMath.size(radius);
		for (int[] picture : new int[][] {ShapeMath.fill(radius), ShapeMath.ring(radius, 1), ShapeMath.shadow(radius, 6)}) {
			int s = picture == null ? 0 : (int) Math.sqrt(picture.length);
			for (int y = 0; y < s; y++) {
				for (int x = 0; x < s; x++) {
					assertEquals(at(picture, s, x, y), at(picture, s, s - 1 - x, y), "left and right match");
					assertEquals(at(picture, s, x, y), at(picture, s, x, s - 1 - y), "top and bottom match");
				}
			}
		}
		assertEquals(size, ShapeMath.size(radius));
	}

	@Test
	void theRingIsOnlyOnTheEdgeAndHollowInside() {
		int radius = 12, size = ShapeMath.size(radius);
		int[] ring = ShapeMath.ring(radius, 1);

		assertEquals(0, at(ring, size, size / 2, size / 2), "the inside is empty");
		assertEquals(255, at(ring, size, radius, 0), "the top edge is drawn");
		assertEquals(255, at(ring, size, 0, size / 2), "the left edge is drawn");
		assertEquals(0, at(ring, size, radius, 1), "one pixel in from the edge is already inside the ring");
		assertEquals(0, at(ring, size, 0, 0));
	}

	@Test
	void aThickerRingCoversMorePixels() {
		int radius = 12, size = ShapeMath.size(radius);
		assertEquals(255, at(ShapeMath.ring(radius, 3), size, radius, 2), "three pixels in is still on the ring");
		assertEquals(0, at(ShapeMath.ring(radius, 1), size, radius, 2));
	}

	@Test
	void aRingWithNoRadiusIsStillAValidOutline() {
		int[] ring = ShapeMath.ring(0, 1);
		assertEquals(4, ring.length);
		assertEquals(255, ring[0]);
	}

	@Test
	void theShadowIsSolidUnderTheShapeAndFadesToNothingAtTheEdgeOfThePicture() {
		int radius = 8, spread = 10, corner = radius + spread, size = ShapeMath.size(corner);
		int[] shadow = ShapeMath.shadow(radius, spread);

		assertEquals(255, at(shadow, size, size / 2, size / 2));
		assertTrue(at(shadow, size, 0, size / 2) <= 3, "practically nothing at the outer edge");
		assertEquals(0, at(shadow, size, 0, 0));

		int last = -1;
		for (int x = 0; x <= size / 2; x++) {
			int value = at(shadow, size, x, size / 2);
			assertTrue(value >= last, "it only gets stronger towards the shape");
			last = value;
		}
	}

	@Test
	void theShadowFadeIsGradualWithManyDistinctSteps() {
		int radius = 8, spread = 24, corner = radius + spread, size = ShapeMath.size(corner);
		int[] shadow = ShapeMath.shadow(radius, spread);
		java.util.Set<Integer> steps = new java.util.HashSet<>();
		for (int x = 0; x < spread; x++) {
			steps.add(at(shadow, size, x, size / 2));
		}
		assertTrue(steps.size() >= 12, "a wide soft shadow has many opacity levels: " + steps.size());
	}

	@Test
	void everyValueIsAValidOpacity() {
		for (int value : ShapeMath.shadow(5, 7)) {
			assertTrue(value >= 0 && value <= 255);
		}
		for (int value : ShapeMath.ring(7, 2)) {
			assertTrue(value >= 0 && value <= 255);
		}
	}

	@Test
	void aGlowIsEmptyUnderTheShapeAndFadesOutwards() {
		int radius = 8, spread = 10, corner = radius + spread, size = ShapeMath.size(corner);
		int[] glow = ShapeMath.glow(radius, spread);

		assertEquals(0, at(glow, size, size / 2, size / 2), "it does not tint what is drawn over the shape");
		assertTrue(at(glow, size, spread + 2, size / 2) == 0, "still inside the shape");
		int atEdge = at(glow, size, spread - 1, size / 2);
		int farOut = at(glow, size, 2, size / 2);
		assertTrue(atEdge > 200, "brightest right outside the shape: " + atEdge);
		assertTrue(farOut < atEdge, "and dimmer further out");
		assertTrue(at(glow, size, 0, size / 2) <= 3);
	}
}

