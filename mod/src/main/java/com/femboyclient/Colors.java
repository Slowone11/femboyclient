package com.femboyclient;

/**
 * Small ARGB color helpers ({@code 0xAARRGGBB}). Plain integer math, nothing is allocated, and no Minecraft classes are used,
 * so it is safe to call every frame and easy to test.
 */
public final class Colors {

	private Colors() {
	}

	/** The alpha (0-255) of a color. */
	public static int alpha(int color) {
		return color >>> 24;
	}

	/** The red, green and blue of {@code rgb} with a new alpha (0-255). */
	public static int withAlpha(int rgb, int alpha) {
		return (Math.max(0, Math.min(255, alpha)) << 24) | (rgb & 0xFFFFFF);
	}

	/** The same color with its alpha multiplied by {@code factor} (0 = invisible, 1 = unchanged). Used for fades. */
	public static int scaleAlpha(int color, float factor) {
		return withAlpha(color, Math.round(alpha(color) * Ease.clamp01(factor)));
	}

	/** A blend of two colors, channel by channel, alpha included. {@code t} 0 gives {@code a}, 1 gives {@code b}. */
	public static int lerp(int a, int b, float t) {
		float u = Ease.clamp01(t);
		return (mix(a >>> 24, b >>> 24, u) << 24) | (mix(a >>> 16 & 0xFF, b >>> 16 & 0xFF, u) << 16)
				| (mix(a >>> 8 & 0xFF, b >>> 8 & 0xFF, u) << 8) | mix(a & 0xFF, b & 0xFF, u);
	}

	private static int mix(int from, int to, float t) {
		return Math.round(from + (to - from) * t);
	}
}
