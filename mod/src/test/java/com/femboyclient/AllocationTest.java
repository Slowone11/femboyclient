package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that the math the player screen runs every frame allocates nothing. Each piece runs many times and the
 * JVM's own count of bytes allocated by this thread must not grow. (The drawing calls themselves need a running game,
 * so they are checked by reading the code and by the in-game checklist.)
 */
class AllocationTest {

	private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
	private static final int WARM_UP = 30_000;
	private static final int RUNS = 300_000;

	/** Written to so the JIT cannot throw the work away. */
	private static volatile long sink;

	/** How many bytes this thread allocated while running {@code work} {@link #RUNS} times, after a warm-up. */
	private static long allocatedBy(java.util.function.IntConsumer work) {
		for (int i = 0; i < WARM_UP; i++) {
			work.accept(i);
		}
		long before = THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId());
		for (int i = 0; i < RUNS; i++) {
			work.accept(i);
		}
		return THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
	}

	private static void assertNoAllocation(String what, java.util.function.IntConsumer work) {
		long bytes = allocatedBy(work);
		assertTrue(bytes < 16_384, what + " allocated " + bytes + " bytes over " + RUNS + " runs");
	}

	@Test
	void colorMathAllocatesNothing() {
		assertNoAllocation("Colors", i -> sink += Colors.lerp(Theme.PANEL, Theme.ACCENT, (i % 100) / 100f)
				+ Colors.scaleAlpha(Theme.PANEL, (i % 50) / 50f) + Colors.withAlpha(Theme.ACCENT, i & 255) + Colors.alpha(Theme.TEXT));
	}

	@Test
	void easingAllocatesNothing() {
		assertNoAllocation("Ease", i -> sink += (long) (Ease.inOutCubic((i % 100) / 100f) + Ease.outCubic((i % 100) / 100f)
				+ Ease.approach(i % 10, 5f, 14f, 0.016f) + Ease.mix(0f, 1f, 0.5f)));
	}

	@Test
	void theParticleFieldAllocatesNothingWhenItMoves() {
		ParticleField field = new ParticleField(Theme.PARTICLES, 1);
		assertNoAllocation("ParticleField.update", i -> {
			field.update(i * 0.016f, 420, 240);
			sink += (long) (field.x(0) + field.y(1) + field.alpha(2));
		});
	}
}
