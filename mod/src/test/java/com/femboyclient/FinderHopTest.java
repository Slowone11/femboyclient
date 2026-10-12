package com.femboyclient;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The hop loop, run on a fake clock and a fake game. The fake game moves you like SkyBlock does:
 * /is puts you on your island, and /warp puts you in that warp's world.
 */
class FinderHopTest {

	private static final String ISLAND = "Private Island";

	private static final class FakeEnv implements FinderHop.Env {
		boolean connected = true;
		String world = ISLAND;
		Set<String> lobby = new HashSet<>();
		long now = 1_000_000_000_000L;
		final List<String> commands = new ArrayList<>();
		final List<Long> commandTimes = new ArrayList<>();
		final List<String> said = new ArrayList<>();
		final List<JsonObject> reports = new ArrayList<>();
		Consumer<String> onCommand = c -> { };
		boolean warpsWork = true;         // false: a warp is refused (locked or unknown) and you stay put
		boolean lobbyThrows;

		public boolean connected() { return connected; }
		public String world() { return world; }
		public Set<String> lobbyNames() {
			if (lobbyThrows) {
				throw new IllegalStateException("tab list exploded");
			}
			return lobby;
		}
		public void sendCommand(String command) {
			commands.add(command);
			commandTimes.add(now);
			if (command.equals("is")) {
				world = ISLAND;
			} else if (warpsWork) {
				for (String w : FinderWarps.worlds()) {
					if (command.equals(FinderWarps.commandFor(w))) {
						world = w;
					}
				}
			}
			onCommand.accept(command);
		}
		public void say(String text) { said.add(text); }
		public void report(JsonObject json) { reports.add(json); }
		public long nanos() { return now; }
	}

	private FakeEnv env;
	private FinderHop hop;
	private boolean enabled;
	private FinderHop.Command command;     // what the website currently says (null = nothing)

	@BeforeEach
	void setUp() {
		env = new FakeEnv();
		enabled = true;
		hop = new FinderHop(env, () -> enabled);
		command = null;
	}

	private void target(String name, String world, long run) {
		command = new FinderHop.Command(name, world, run);
	}

	/** Runs the game for this many seconds in 50 ms ticks. The website is asked every 2 seconds, like the real mod. */
	private void run(double seconds) {
		long end = env.now + (long) (seconds * 1e9);
		long nextPoll = env.now;
		while (env.now < end) {
			if (env.now >= nextPoll) {
				hop.onServerCommand(command);
				nextPoll = env.now + 2_000_000_000L;
			}
			hop.tick();
			env.now += 50_000_000L;
		}
	}

	private JsonObject lastReport() {
		return env.reports.get(env.reports.size() - 1);
	}

	private String status(JsonObject json) {
		return json.get("status").getAsString();
	}

	private long warps() {
		return env.commands.stream().filter(c -> c.startsWith("warp")).count();
	}

	// ------------------------------------------------------------------ starting

	@Test
	void doesNothingWhileThereIsNoTarget() {
		run(30);
		assertEquals(List.of(), env.commands);
		assertEquals(List.of(), env.reports);
		assertFalse(hop.isActive());
	}

	@Test
	void aTargetStartsTheLoopAndTheWebsiteHearsSearchingWithZeroHops() {
		target("Steve", "Hub", 1);
		run(0.2);

		assertTrue(hop.isActive());
		assertEquals("searching", status(env.reports.get(0)));
		assertEquals(0, env.reports.get(0).get("hops").getAsInt());
		assertEquals("Steve", env.reports.get(0).get("target").getAsString());
		assertEquals(1, env.reports.get(0).get("run").getAsLong());
		assertTrue(env.said.get(0).contains("Looking for Steve in Hub"));
	}

	@Test
	void nothingRunsWhenTheModIsSwitchedOff() {
		enabled = false;
		target("Steve", "Hub", 1);
		run(20);
		assertEquals(List.of(), env.commands);
		assertFalse(hop.isActive());
	}

	// -------------------------------------------- the cycle: /warp, /is, /warp, /is ...

	@Test
	void itGoesWarpThenIsThenWarpEveryFiveSecondsSoEachTripGivesANewLobby() {
		target("Steve", "Hub", 1);
		run(26);        // steps at 0, 5, 10, 15, 20, 25 seconds

		assertEquals(List.of("warp hub", "is", "warp hub", "is", "warp hub", "is"), env.commands);
		for (int i = 0; i < env.commandTimes.size(); i++) {
			assertEquals(i * 5, (env.commandTimes.get(i) - env.commandTimes.get(0)) / 1_000_000_000L, "command " + i);
		}
	}

	@Test
	void itNeverWarpsWhileAlreadyInTheTargetsWorldBecauseThatWouldKeepTheSameLobby() {
		target("Steve", "Hub", 1);
		run(200);

		for (int i = 1; i < env.commands.size(); i++) {
			if (env.commands.get(i).startsWith("warp")) {
				assertEquals("is", env.commands.get(i - 1), "a /warp must always follow a /is");
			}
		}
	}

	@Test
	void startingInTheTargetsWorldItGoesToTheIslandFirst() {
		env.world = "Hub";
		target("Steve", "Hub", 1);
		run(11);
		assertEquals(List.of("is", "warp hub", "is"), env.commands);
		assertTrue(env.said.contains("Step: /is (back to your island, so the next /warp gives a new lobby)"), env.said.toString());
	}

	@Test
	void startingInAnotherWorldItGoesToTheIslandFirstAndThatIsNotAHop() {
		env.world = "The End";
		target("Steve", "Hub", 1);

		run(1);
		assertEquals(List.of("is"), env.commands);
		assertTrue(env.said.contains("Step: /is (going to your island first)"));
		assertEquals(0, lastReport().get("hops").getAsInt(), "/is is not a hop");

		run(5);
		assertEquals(List.of("is", "warp hub"), env.commands);
		assertEquals(1, lastReport().get("hops").getAsInt(), "the first warp is hop 1");
		assertTrue(env.said.contains("Hop 1: /warp hub"));
	}

	@Test
	void startingOnTheIslandItWarpsStraightAway() {
		target("Steve", "Hub", 1);
		run(1);
		assertEquals(List.of("warp hub"), env.commands);
	}

	@Test
	void eachWorldUsesItsOwnWarpFromTheTable() {
		target("Steve", "The End", 1);
		run(1);
		assertEquals(List.of("warp end"), env.commands);
	}

	@Test
	void onlyIsAndWarpCommandsFromTheTableAreEverSent() {
		env.world = "Somewhere Else";
		target("Steve", "Dungeon Hub", 1);
		run(300);

		for (String c : env.commands) {
			assertTrue(c.equals("is") || c.equals("warp dhub"), c);
		}
	}

	@Test
	void neverMoreThanOneCommandEveryFiveSeconds() {
		target("Steve", "Hub", 1);
		run(120);

		assertTrue(env.commands.size() >= 20);
		for (int i = 1; i < env.commandTimes.size(); i++) {
			long gap = env.commandTimes.get(i) - env.commandTimes.get(i - 1);
			assertTrue(gap >= FinderHop.STEP_NANOS, "commands " + i + " were only " + gap / 1_000_000 + " ms apart");
		}
	}

	// -------------------------------------------------------------------- counting

	@Test
	void onlyWarpsAreCountedAndEachOneIsPrintedAndReportedLive() {
		target("Steve", "Hub", 1);
		run(26);

		assertTrue(env.said.contains("Hop 1: /warp hub"), env.said.toString());
		assertTrue(env.said.contains("Hop 2: /warp hub"));
		assertTrue(env.said.contains("Hop 3: /warp hub"));
		assertEquals(3, warps());

		List<Integer> counts = env.reports.stream().filter(r -> status(r).equals("searching"))
				.map(r -> r.get("hops").getAsInt()).toList();
		assertEquals(List.of(0, 1, 2, 3), counts, "one live update after each warp, none after /is");
	}

	// -------------------------------------------------------------------- finding

	@Test
	void aTargetAlreadyInTheTabListIsFoundAtOnceWithZeroHops() {
		env.lobby.add("steve");
		target("Steve", "Hub", 1);
		run(6);

		assertEquals(List.of(), env.commands, "no command needed");
		JsonObject found = lastReport();
		assertEquals("found", status(found));
		assertEquals(0, found.get("hops").getAsInt());
		assertEquals(ISLAND, found.get("world").getAsString(), "the world you were in when you found them");
		assertTrue(found.has("elapsedSeconds"));
		assertFalse(hop.isActive());
	}

	@Test
	void theNameIsMatchedWhateverItsCapitalisation() {
		env.lobby.add("steve");
		target("STEVE", "Hub", 1);
		run(6);
		assertEquals("found", status(lastReport()));
	}

	@Test
	void theyAreFoundBeforeItLeavesTheLobbyTheyAreIn() {
		// The third warp lands in a lobby with them. The very next step must find them, not send /is.
		env.onCommand = c -> {
			if (warps() == 3 && c.startsWith("warp")) {
				env.lobby.add("steve");
			}
		};
		target("Steve", "Hub", 1);
		run(60);

		assertEquals(List.of("warp hub", "is", "warp hub", "is", "warp hub"), env.commands, "no /is after the third warp");
		JsonObject found = lastReport();
		assertEquals("found", status(found));
		assertEquals(3, found.get("hops").getAsInt());
		assertEquals("Hub", found.get("world").getAsString());
		assertTrue(found.get("elapsedSeconds").getAsLong() >= 24 && found.get("elapsedSeconds").getAsLong() <= 26,
				"about 25 seconds: " + found.get("elapsedSeconds"));
		assertTrue(env.said.stream().anyMatch(s -> s.startsWith("Found Steve in Hub after 3 hops (")), env.said.toString());
		assertFalse(hop.isActive());
	}

	@Test
	void afterTheyAreFoundNothingMoreIsSentEvenIfTheWebsiteStillListsThem() {
		env.lobby.add("steve");
		target("Steve", "Hub", 1);
		run(30);          // the website keeps saying the same run: it must not restart

		assertEquals(List.of(), env.commands);
		assertEquals(1, env.reports.stream().filter(r -> status(r).equals("found")).count());
	}

	// ------------------------------------------------------------------- the 30 hop limit

	@Test
	void stopsAfterThirtyHopsWithTheLimitReason() {
		target("Steve", "Hub", 1);
		run(400);

		assertEquals(30, warps());
		JsonObject last = lastReport();
		assertEquals("not_found", status(last));
		assertEquals("limit", last.get("reason").getAsString());
		assertEquals(30, last.get("hops").getAsInt());
		assertFalse(hop.isActive());
		assertTrue(env.said.stream().anyMatch(s -> s.startsWith("Not found after 30 hops")), env.said.toString());
	}

	@Test
	void aPlayerFoundInTheThirtiethLobbyStillCountsAsFound() {
		env.onCommand = c -> {
			if (warps() == 30 && c.startsWith("warp")) {
				env.lobby.add("steve");
			}
		};
		target("Steve", "Hub", 1);
		run(400);

		assertEquals(30, warps());
		assertEquals("found", status(lastReport()));
		assertEquals(30, lastReport().get("hops").getAsInt());
	}

	// ------------------------------------------------------------------ a warp that does not work

	@Test
	void stopsIfTwoWarpsInARowLeaveYouOnTheIsland() {
		env.warpsWork = false;
		target("Steve", "Galatea", 1);
		run(60);

		assertEquals(2, warps(), "two tries, then it gives up");
		JsonObject last = lastReport();
		assertEquals("stopped", status(last));
		assertEquals("warp_failed", last.get("reason").getAsString());
		assertFalse(hop.isActive());
	}

	@Test
	void aSingleSlowWarpIsForgivenAsLongAsTheNextOneWorks() {
		int[] count = {0};
		env.onCommand = c -> { };
		env.warpsWork = false;
		target("Steve", "Hub", 1);
		run(1);                      // warp 1 fails: still on the island
		env.warpsWork = true;
		run(5);                      // step 2 retries the warp and it works

		assertTrue(hop.isActive());
		assertEquals("Hub", env.world);
		assertEquals(2, warps());
	}

	// ------------------------------------------------------------------ stopping

	@Test
	void turningTheTargetOffMidHopStopsAtOnceWithTheCountSoFar() {
		target("Steve", "Hub", 1);
		run(12);       // warp at 0, /is at 5, warp at 10
		assertEquals(2, warps());

		command = null;                   // the website toggle went off
		run(3);

		JsonObject last = lastReport();
		assertEquals("stopped", status(last));
		assertEquals(2, last.get("hops").getAsInt());
		assertFalse(last.has("reason"));
		assertFalse(hop.isActive());

		run(60);
		assertEquals(2, warps(), "no more commands after the toggle went off");
	}

	@Test
	void disconnectingStopsAtOnceWithTheDisconnectedReason() {
		target("Steve", "Hub", 1);
		run(11);
		assertEquals(2, warps());

		env.connected = false;
		hop.tick();

		JsonObject last = lastReport();
		assertEquals("stopped", status(last));
		assertEquals("disconnected", last.get("reason").getAsString());
		assertEquals(2, last.get("hops").getAsInt());
		assertFalse(hop.isActive());
		run(60);
		assertEquals(2, warps());
	}

	@Test
	void theDisconnectEventStopsItToo() {
		target("Steve", "Hub", 1);
		run(2);
		hop.onDisconnect();

		assertEquals("disconnected", lastReport().get("reason").getAsString());
		assertFalse(hop.isActive());
	}

	@Test
	void aDisconnectWhileNothingIsRunningSaysNothing() {
		hop.onDisconnect();
		assertEquals(List.of(), env.reports);
	}

	@Test
	void losingTheWebsiteEndsTheSearchRatherThanHoppingBlind() {
		target("Steve", "Hub", 1);
		run(1);
		while (env.now < 60_000_000_000L + 1_000_000_000_000L) {       // the website goes away: no more polls
			hop.tick();
			env.now += 50_000_000L;
		}

		assertEquals("website_unreachable", lastReport().get("reason").getAsString());
		assertTrue(warps() < FinderHop.MAX_HOPS);
	}

	@Test
	void notBeingInSkyBlockStopsAfterAFewSteps() {
		env.world = null;
		target("Steve", "Hub", 1);
		run(40);

		assertEquals(List.of(), env.commands, "no command without knowing where you are");
		assertEquals("not_in_skyblock", lastReport().get("reason").getAsString());
	}

	@Test
	void aWorldThatIsStillLoadingJustWaitsAStep() {
		env.world = null;
		target("Steve", "Hub", 1);
		run(1);
		assertEquals(List.of(), env.commands);
		assertTrue(env.said.contains("Waiting for the world to load…"));

		env.world = ISLAND;
		run(6);
		assertEquals(List.of("warp hub"), env.commands);
		assertTrue(hop.isActive());
	}

	// --------------------------------------------------------------- bad or changing commands

	@Test
	void aWorldWithNoWarpIsRefusedAndReported() {
		target("Steve", "Private Island", 1);
		run(20);

		assertEquals(List.of(), env.commands);
		assertEquals("stopped", status(lastReport()));
		assertEquals("no_warp", lastReport().get("reason").getAsString());
		assertEquals(1, env.reports.size(), "reported once, not every poll");
	}

	@Test
	void aBadNameIsRefusedAndNeverTyped() {
		target("/op me", "Hub", 1);
		run(20);

		assertEquals(List.of(), env.commands);
		assertEquals("bad_target", lastReport().get("reason").getAsString());
	}

	@Test
	void aNewSearchReplacesTheOldOneAndTheOldOneIsReportedStopped() {
		target("Steve", "Hub", 1);
		run(6);
		target("Alice", "The End", 2);
		run(3);

		JsonObject stopped = env.reports.stream().filter(r -> status(r).equals("stopped")).findFirst().orElseThrow();
		assertEquals("Steve", stopped.get("target").getAsString());
		assertEquals(1, stopped.get("run").getAsLong());
		assertTrue(hop.isActive());
		assertEquals("Alice", lastReport().get("target").getAsString());
	}

	@Test
	void aNewSearchStartedRightAfterAStopStillWaitsFiveSecondsAfterTheLastCommand() {
		target("Steve", "Hub", 1);
		run(6);
		command = null;
		run(0.5);
		target("Alice", "Hub", 2);
		run(20);

		for (int i = 1; i < env.commandTimes.size(); i++) {
			assertTrue(env.commandTimes.get(i) - env.commandTimes.get(i - 1) >= FinderHop.STEP_NANOS);
		}
	}

	@Test
	void aBrokenGameNeverEscapesAndStopsTheSearch() {
		env.lobbyThrows = true;
		target("Steve", "Hub", 1);
		run(6);

		assertEquals("error", lastReport().get("reason").getAsString());
		assertFalse(hop.isActive());
	}

	@Test
	void theSameFinishedSearchIsNotStartedAgain() {
		target("Steve", "Hub", 1);
		run(6);
		command = null;
		run(3);                                // toggled off
		assertFalse(hop.isActive());

		target("Steve", "Hub", 1);             // the very same run arriving again (a late poll)
		run(20);
		assertFalse(hop.isActive());
	}

	@Test
	void aFreshRunOfTheSameNameStartsNormally() {
		target("Steve", "Hub", 1);
		run(6);
		command = null;
		run(3);

		target("Steve", "Hub", 2);
		run(1);
		assertTrue(hop.isActive());
	}

	@Test
	void theDurationIsEasyToRead() {
		assertEquals("0s", FinderHop.duration(0));
		assertEquals("45s", FinderHop.duration(45));
		assertEquals("1m 12s", FinderHop.duration(72));
		assertEquals("10m 0s", FinderHop.duration(600));
	}
}
