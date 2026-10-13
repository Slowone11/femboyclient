package com.femboyclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecentSearchesTest {

	@TempDir
	Path dir;

	@Test
	void newestFirstNoDuplicatesAtMostSix() {
		RecentSearches recent = new RecentSearches(dir.resolve("recent.json"));
		recent.addPage("Plasma");
		recent.addCalculation("50m*4", "200m");
		recent.addPage("plasma");
		assertEquals(List.of(new RecentSearches.Entry("plasma", null), new RecentSearches.Entry("50m*4", "200m")), recent.entries());
		for (int i = 0; i < 10; i++) {
			recent.addPage("Page " + i);
		}
		assertEquals(RecentSearches.MAX, recent.entries().size());
		assertEquals("Page 9", recent.entries().get(0).text());
	}

	@Test
	void keptOnDisk() {
		RecentSearches recent = new RecentSearches(dir.resolve("recent.json"));
		recent.addCalculation("2+2", "4");
		RecentSearches again = new RecentSearches(dir.resolve("recent.json"));
		assertTrue(again.entries().get(0).isCalculation());
		assertEquals("4", again.entries().get(0).answer());
	}
}
