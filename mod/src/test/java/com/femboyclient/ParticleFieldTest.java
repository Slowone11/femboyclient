package com.femboyclient;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParticleFieldTest {

	@Test
	void everyParticleStaysInsideTheBoxAndHasAValidAlphaAtAnyTime() {
		ParticleField field = new ParticleField(40, 7);
		for (float seconds = 0; seconds < 500; seconds += 3.7f) {
			field.update(seconds, 420, 240);
			for (int i = 0; i < field.count(); i++) {
				assertTrue(field.x(i) >= 0 && field.x(i) + field.size(i) <= 420, "x " + field.x(i));
				assertTrue(field.y(i) >= 0 && field.y(i) + field.size(i) <= 240, "y " + field.y(i));
				assertTrue(field.alpha(i) >= 0f && field.alpha(i) <= 1f, "alpha " + field.alpha(i));
			}
		}
	}

	@Test
	void particlesDriftUpwardAsTimePasses() {
		ParticleField field = new ParticleField(1, 3);
		field.update(10f, 400, 400);
		float before = field.y(0);
		field.update(10.5f, 400, 400);
		float after = field.y(0);
		assertNotEquals(before, after);
		assertTrue(after < before || after - before > 300, "it moves up the screen (a smaller y), wrapping round at the top");
	}

	@Test
	void theSameSeedGivesTheSameField() {
		ParticleField a = new ParticleField(10, 99), b = new ParticleField(10, 99);
		a.update(12.3f, 300, 200);
		b.update(12.3f, 300, 200);
		for (int i = 0; i < 10; i++) {
			assertEquals(a.x(i), b.x(i));
			assertEquals(a.y(i), b.y(i));
		}
	}

	@Test
	void zeroOrNegativeCountsAndEmptyBoxesAreHarmless() {
		assertEquals(0, new ParticleField(0, 1).count());
		assertEquals(0, new ParticleField(-5, 1).count());
		ParticleField field = new ParticleField(3, 1);
		field.update(1f, 0, 0);
		field.update(1f, -4, 10);
	}
}
