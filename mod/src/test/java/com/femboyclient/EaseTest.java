package com.femboyclient;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EaseTest {

	@Test
	void theCurvesStartAtZeroEndAtOneAndStayInRange() {
		assertEquals(0f, Ease.inOutCubic(0f), 1e-6);
		assertEquals(1f, Ease.inOutCubic(1f), 1e-6);
		assertEquals(0.5f, Ease.inOutCubic(0.5f), 1e-6);
		assertEquals(0f, Ease.outCubic(0f), 1e-6);
		assertEquals(1f, Ease.outCubic(1f), 1e-6);
		assertEquals(0f, Ease.inOutCubic(-3f), 1e-6);
		assertEquals(1f, Ease.inOutCubic(9f), 1e-6);
	}

	@Test
	void theCurvesNeverGoBackwards() {
		float lastInOut = 0f, lastOut = 0f;
		for (int i = 1; i <= 100; i++) {
			float t = i / 100f;
			assertTrue(Ease.inOutCubic(t) >= lastInOut);
			assertTrue(Ease.outCubic(t) >= lastOut);
			lastInOut = Ease.inOutCubic(t);
			lastOut = Ease.outCubic(t);
		}
	}

	@Test
	void inOutIsSlowerThanLinearAtTheStartAndOutIsFasterThanLinear() {
		assertTrue(Ease.inOutCubic(0.2f) < 0.2f);
		assertTrue(Ease.outCubic(0.2f) > 0.2f);
	}

	@Test
	void mixBlendsBetweenTwoNumbers() {
		assertEquals(0.94f, Ease.mix(0.94f, 1f, 0f), 1e-6);
		assertEquals(1f, Ease.mix(0.94f, 1f, 1f), 1e-6);
		assertEquals(5f, Ease.mix(0f, 10f, 0.5f), 1e-6);
	}

	@Test
	void approachMovesTowardsTheTargetWithoutOvershooting() {
		float value = 0f;
		for (int i = 0; i < 20; i++) {
			float next = Ease.approach(value, 10f, 14f, 1f / 60f);
			assertTrue(next >= value && next <= 10f);
			value = next;
		}
		assertTrue(value > 0f && value < 10f);
	}

	@Test
	void approachTakesTheSameTimeAtAnyFrameRate() {
		float slow = 0f, fast = 0f;
		for (int i = 0; i < 10; i++) {
			slow = Ease.approach(slow, 100f, 8f, 0.05f);          // 10 frames of 50 ms
		}
		for (int i = 0; i < 50; i++) {
			fast = Ease.approach(fast, 100f, 8f, 0.01f);          // 50 frames of 10 ms
		}
		assertEquals(slow, fast, 0.01f * 100f);
	}

	@Test
	void approachSnapsToTheTargetSoTheAnimationEnds() {
		assertEquals(5f, Ease.approach(4.995f, 5f, 14f, 0.016f));
		float value = 0f;
		for (int i = 0; i < 600 && value != 3f; i++) {
			value = Ease.approach(value, 3f, 14f, 1f / 60f);
		}
		assertEquals(3f, value);
	}

	@Test
	void aNegativeOrZeroFrameTimeMovesNothing() {
		assertEquals(0f, Ease.approach(0f, 10f, 14f, 0f));
		assertEquals(0f, Ease.approach(0f, 10f, 14f, -1f));
	}
}
