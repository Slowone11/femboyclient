package com.femboyclient;

/**
 * Easing and smoothing for the player screen's animations. Plain float math, no allocation and no Minecraft classes,
 * so it is safe to call every frame and easy to test.
 */
public final class Ease {

	private Ease() {
	}

	/** Limits a value to 0..1. */
	public static float clamp01(float value) {
		return Math.max(0f, Math.min(1f, value));
	}

	/** Starts slow, speeds up, ends slow (0 to 1). Used for the window opening. */
	public static float inOutCubic(float t) {
		float x = clamp01(t);
		return x < 0.5f ? 4f * x * x * x : 1f - (float) Math.pow(-2f * x + 2f, 3) / 2f;
	}

	/** Starts fast and settles gently (0 to 1). */
	public static float outCubic(float t) {
		float x = 1f - clamp01(t);
		return 1f - x * x * x;
	}

	/** A number between {@code from} and {@code to}. */
	public static float mix(float from, float to, float t) {
		return from + (to - from) * t;
	}

	/**
	 * Moves {@code current} towards {@code target} the way a spring settles: fast at first, then slowly, and the same
	 * speed at any frame rate. {@code rate} is how quickly it catches up (about 3 / rate seconds to be nearly there).
	 * Snaps to the target once it is closer than 0.01, so an animation always ends.
	 */
	public static float approach(float current, float target, float rate, float dtSeconds) {
		if (Math.abs(target - current) < 0.01f) {
			return target;
		}
		return current + (target - current) * (1f - (float) Math.exp(-rate * Math.max(0f, dtSeconds)));
	}
}
