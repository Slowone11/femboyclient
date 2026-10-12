package com.femboyclient;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinderWarpsTest {

	@Test
	void aWorldGivesItsWarpCommand() {
		assertEquals("warp hub", FinderWarps.commandFor("Hub"));
		assertEquals("warp dhub", FinderWarps.commandFor("Dungeon Hub"));
		assertEquals("warp end", FinderWarps.commandFor("The End"));
	}

	@Test
	void theWorldNameIsMatchedWhateverItsCase() {
		assertEquals("warp hub", FinderWarps.commandFor("hub"));
		assertEquals("warp hub", FinderWarps.commandFor("  HUB "));
	}

	@Test
	void worldsWithNoWarpGiveNothing() {
		assertNull(FinderWarps.commandFor("Private Island"));
		assertNull(FinderWarps.commandFor("Dungeons"));
		assertNull(FinderWarps.commandFor("The Rift"));
		assertNull(FinderWarps.commandFor("Unknown"));
		assertNull(FinderWarps.commandFor(""));
		assertNull(FinderWarps.commandFor(null));
	}

	@Test
	void textThatIsNotInTheTableCanNeverBecomeACommand() {
		assertNull(FinderWarps.commandFor("hub; op me"));
		assertNull(FinderWarps.commandFor("warp hub"));
		assertNull(FinderWarps.commandFor("/warp hub"));
	}

	@Test
	void everyCommandIsAPlainWarpNameWithNothingElse() {
		for (String world : FinderWarps.worlds()) {
			assertTrue(FinderWarps.commandFor(world).matches("warp [a-z]+"), world);
		}
	}

	@Test
	void theIslandIsNeverAWarpTarget() {
		assertFalse(FinderWarps.worlds().contains(FinderWarps.ISLAND_WORLD));
	}

	@Test
	void sameWorldIgnoresCaseAndSpaces() {
		assertTrue(FinderWarps.sameWorld("Hub", " hub"));
		assertFalse(FinderWarps.sameWorld("Hub", "The End"));
		assertFalse(FinderWarps.sameWorld(null, "Hub"));
	}

	// ---------------------------------------------------------------- how a report is routed

	@Test
	void aFinderReportGoesToTheFinderEndpointAndTheOtherMessagesKeepTheirs() {
		JsonObject finder = new JsonObject();
		finder.addProperty("status", "found");
		finder.addProperty("target", "Steve");

		assertEquals("/finder/report", TrackerPayload.path(finder));
		assertEquals("player finder", TrackerPayload.label(finder));
		assertEquals(1, TrackerPayload.size(finder));
		assertFalse(TrackerPayload.isParty(finder));

		Instant t = Instant.parse("2026-09-21T19:41:03Z");
		assertEquals("/tracker/snapshot", TrackerPayload.path(TrackerPayload.build("Me", "Hub", List.of("A"), t, t)));
		assertEquals("/tracker/party", TrackerPayload.path(TrackerPayload.party("Me", List.of("A"), t)));
		assertEquals("/tracker/chat", TrackerPayload.path(TrackerPayload.chat("Me", "Hub", List.of())));
	}
}
