package com.femboyclient;

import java.util.Random;

/**
 * A handful of soft particles that drift upward and sway, for the window's background. Each one is placed from the clock
 * ({@link #update}), so nothing accumulates and the motion is the same at any frame rate. All arrays are made once in the
 * constructor and only overwritten afterwards, so updating and drawing allocate nothing. No Minecraft classes are used, so it
 * can be tested on its own.
 */
public final class ParticleField {

	private final int count;
	private final float[] baseX, speed, size, phase, sway, swayRate;      // fixed per particle, chosen once
	private final float[] x, y, alpha;                                    // where each one is right now

	/** @param seed makes the same field every time, so tests are repeatable */
	public ParticleField(int count, long seed) {
		this.count = Math.max(0, count);
		baseX = new float[this.count];
		speed = new float[this.count];
		size = new float[this.count];
		phase = new float[this.count];
		sway = new float[this.count];
		swayRate = new float[this.count];
		x = new float[this.count];
		y = new float[this.count];
		alpha = new float[this.count];

		Random random = new Random(seed);
		for (int i = 0; i < this.count; i++) {
			baseX[i] = random.nextFloat();
			speed[i] = 5f + random.nextFloat() * 14f;                    // pixels per second
			size[i] = random.nextFloat() < 0.75f ? 1f : 2f;
			phase[i] = random.nextFloat();
			sway[i] = 3f + random.nextFloat() * 7f;                      // pixels either side
			swayRate[i] = 0.3f + random.nextFloat() * 0.6f;              // radians per second
		}
	}

	/** Places every particle for this moment inside a box of this size (0,0 is its top-left corner). */
	public void update(float seconds, int width, int height) {
		if (width <= 0 || height <= 0) {
			return;
		}
		for (int i = 0; i < count; i++) {
			float travelled = (phase[i] * height + seconds * speed[i]) % height;
			float up = 1f - travelled / height;                          // 1 at the bottom, 0 at the top
			float px = baseX[i] * width + (float) Math.sin(seconds * swayRate[i] + phase[i] * 6.2831855f) * sway[i];
			x[i] = Math.max(0f, Math.min(width - size[i], px));
			y[i] = Math.max(0f, Math.min(height - size[i], height - travelled - size[i]));      // y grows downward, so going up means it shrinks
			alpha[i] = (float) Math.sin(Math.PI * Math.max(0f, Math.min(1f, up)));       // fades in at the bottom and out at the top
		}
	}

	public int count() {
		return count;
	}

	public float x(int i) {
		return x[i];
	}

	public float y(int i) {
		return y[i];
	}

	/** How visible it is, 0 to 1. */
	public float alpha(int i) {
		return alpha[i];
	}

	/** Its width and height in pixels. */
	public float size(int i) {
		return size[i];
	}
}
