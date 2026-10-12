package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkyBlockWorldsTest {

	/** The worlds you asked for, in the order you listed them. */
	private static final List<String> REQUESTED = List.of("Hub", "Dungeon Hub", "Dungeons", "Crystal Hollows",
			"Dwarven Mines", "The End", "Crimson Isle", "Spider's Den", "The Park", "The Farming Islands",
			"Garden", "Private Island", "The Rift");

	@Test
	void everyRequestedWorldIsThere() {
		assertEquals(REQUESTED, SkyBlockWorlds.names());
	}

	@Test
	void unknownIsNotACommandWorld() {
		assertTrue(SkyBlockWorlds.byCommandName("unknown").isEmpty());
	}

	@Test
	void noAliasOrNameBelongsToTwoWorlds() {
		Set<String> seen = new HashSet<>();
		for (SkyBlockWorlds.World world : SkyBlockWorlds.ALL) {
			Set<String> mine = new HashSet<>();
			mine.add(SkyBlockWorlds.normalise(world.name()));
			world.aliases().forEach(a -> mine.add(SkyBlockWorlds.normalise(a)));
			for (String word : mine) {
				assertTrue(seen.add(word), "'" + word + "' is used by more than one world");
			}
		}
	}

	@Test
	void everyWorldNameResolvesToItself() {
		for (SkyBlockWorlds.World world : SkyBlockWorlds.ALL) {
			assertEquals(world, SkyBlockWorlds.byCommandName(world.name()).orElseThrow());
		}
	}

	@Test
	void theTableIsLoadedWithoutAreaClashes() {
		// A clash throws while the class loads, so reaching here means there are none.
		assertEquals(13, SkyBlockWorlds.ALL.size());
		assertEquals("Hub", SkyBlockWorlds.byArea("village").name());
		assertNull(SkyBlockWorlds.byArea("not an area"));
	}

	@Test
	void normalisingIgnoresCaseApostrophesAndSpacing() {
		assertEquals("spiders den", SkyBlockWorlds.normalise("Spider's Den"));
		assertEquals("spiders den", SkyBlockWorlds.normalise("  SPIDERS   DEN "));
		assertEquals("spiders den", SkyBlockWorlds.normalise("spider’s den"));
		assertEquals("", SkyBlockWorlds.normalise("  "));
	}

	@Test
	void everyWorldIsOfferedForTabCompletion() {
		List<String> words = SkyBlockWorlds.commandWords();
		for (String world : REQUESTED) {
			assertTrue(words.contains(world.toLowerCase()), world);
		}
		assertTrue(words.contains("dhub"));
		assertTrue(words.contains("nucleus"));
	}
}
