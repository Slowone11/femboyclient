package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HollowsSyncTest {

	private static final HollowsSync.MansionInfo MANSION = HollowsSync.MansionInfo.MANSION;
	private static final Set<String> CORE = Set.of("allium", "azure_bluet", "cracked_stone_bricks", "lilac", "mossy_stone_bricks");

	@Test
	void aGrottoWithEveryCoreKindIsAMansion() {
		Set<String> kinds = new java.util.HashSet<>(CORE);
		kinds.add("chest");
		assertTrue(MANSION.matches(kinds));
	}

	@Test
	void missingOneCoreKindIsNotAMansion() {
		assertFalse(MANSION.matches(Set.of("allium", "azure_bluet", "cracked_stone_bricks", "lilac")),
				"other builds share some of the flowers, not all");
	}

	@Test
	void aKindOnlyOtherBuildsHaveRulesItOut() {
		Set<String> kinds = new java.util.HashSet<>(CORE);
		kinds.add("mossy_cobblestone");
		assertFalse(MANSION.matches(kinds));
	}

	@Test
	void theScannerIsToldTheKindsThatRuleAGrottoOutToo() {
		// A grotto found by its jasper is reported with only these kinds, so an excluded kind must be among them to be seen.
		assertTrue(MANSION.telltale().containsAll(CORE));
		assertTrue(MANSION.telltale().contains("mossy_cobblestone"));
	}
}
