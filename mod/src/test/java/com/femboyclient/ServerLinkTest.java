package com.femboyclient;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives ServerLink against a real local HTTP server. tick() is called by hand
 * instead of waiting for the scheduler, so every test is deterministic.
 */
class ServerLinkTest {

	private static final UUID PLAYER = UUID.fromString("1adf8788-e917-4675-b758-defaaee07477");
	private static final String CONNECTED = ServerLink.CONNECTED_MESSAGE;
	private static final String DISCONNECTED = ServerLink.DISCONNECTED_MESSAGE;

	private FakeServer server;
	private FemboyClientConfig config;
	private List<String> notices;
	private ServerLink link;

	@BeforeEach
	void setUp() throws IOException {
		server = new FakeServer();
		server.route("/register", FakeServer.Reply.ok("{\"api_key\":\"KEY-1\",\"created\":true}"));
		server.route("/heartbeat", FakeServer.Reply.ok("{\"ok\":true}"));

		config = new FemboyClientConfig();
		config.serverUrl = server.url();
		config.enrollSecret = "join-secret";

		notices = new CopyOnWriteArrayList<>();
		link = newLink();
	}

	@AfterEach
	void tearDown() {
		server.close();
	}

	private ServerLink newLink() {
		ServerLink created = new ServerLink(
				new WebClient(() -> config.serverUrl),
				() -> config,
				() -> new AccountInfo(PLAYER, "Steve"),
				notices::add);
		created.activate();
		return created;
	}

	private static JsonObject object(FakeServer.Request request) {
		return JsonParser.parseString(request.body()).getAsJsonObject();
	}

	// ------------------------------------------------------------- linking

	@Test
	void linksTheAccountOnTheFirstTickAndSaysConnected() {
		link.tick();

		FakeServer.Request register = server.to("/register").get(0);
		assertEquals("join-secret", register.header("X-Enroll-Secret"));
		assertEquals(PLAYER.toString(), object(register).get("player_uuid").getAsString());
		assertEquals("Steve", object(register).get("player_name").getAsString());

		assertEquals("KEY-1", server.to("/heartbeat").get(0).header("X-API-Key"));
		assertEquals(List.of(CONNECTED), notices);
	}

	@Test
	void theKeyIsRememberedSoItLinksOnlyOnce() {
		link.tick();
		link.tick();
		link.tick();

		assertEquals(1, server.to("/register").size());
		assertEquals(List.of(CONNECTED), notices); // and connected is announced once, not every tick
	}

	@Test
	void aKeyTypedIntoTheConfigIsUsedWithoutLinking() {
		config.apiKey = "MANUAL-KEY";
		config.enrollSecret = "";

		link.tick();

		assertEquals(0, server.to("/register").size());
		assertEquals("MANUAL-KEY", server.to("/heartbeat").get(0).header("X-API-Key"));
	}

	@Test
	void withAnEnrolSecretTheLoggedInAccountLinksItselfEvenIfAKeyIsTypedIntoTheConfig() {
		config.apiKey = "SOMEONE-ELSES-KEY";               // a key belongs to one account; this one is logged in now
		server.route("/register", FakeServer.Reply.ok("{\"api_key\":\"KEY-FOR-THIS-ACCOUNT\",\"created\":false}"));

		link.tick();

		JsonObject registered = JsonParser.parseString(server.to("/register").get(0).body()).getAsJsonObject();
		assertEquals(PLAYER.toString(), registered.get("player_uuid").getAsString());
		assertEquals("Steve", registered.get("player_name").getAsString());
		assertEquals("KEY-FOR-THIS-ACCOUNT", server.to("/heartbeat").get(0).header("X-API-Key"));
	}

	@Test
	void theAccountIsOnlyLinkedOnceAndItsKeyIsKept() {
		link.tick();
		link.tick();
		link.tick();

		assertEquals(1, server.to("/register").size());
	}

	@Test
	void aRejectedEnrolSecretKeepsRetryingWithoutClaimingToBeConnected() {
		server.route("/register", FakeServer.Reply.status(401));

		link.tick();
		link.tick();

		assertEquals(2, server.to("/register").size());
		assertEquals(0, server.to("/heartbeat").size());
		assertTrue(notices.isEmpty());
	}

	@Test
	void withNoCredentialsAtAllItNeverCallsTheServer() {
		config.enrollSecret = "";
		config.apiKey = "";

		link.tick();
		link.tick();

		assertEquals(0, server.count());
	}

	@Test
	void nothingHappensWhenSwitchedOff() {
		config.enabled = false;

		link.tick();

		assertEquals(0, server.count());
	}

	@Test
	void aBrokenRegisterReplyIsTreatedAsAFailure() {
		server.route("/register", FakeServer.Reply.ok("not json"));

		link.tick();

		assertEquals(0, server.to("/heartbeat").size());
		assertTrue(notices.isEmpty());
	}

	// ------------------------------------------------------ outages and retry

	@Test
	void disconnectedIsAnnouncedOncePerOutageNotEveryTick() {
		AtomicBoolean down = new AtomicBoolean(false);
		server.route("/heartbeat", request -> down.get() ? FakeServer.Reply.status(500) : FakeServer.Reply.ok("{}"));

		link.tick();
		down.set(true);
		link.setStatus(true); // forces a heartbeat each tick while failing
		link.tick();
		link.tick();
		link.tick();

		assertEquals(List.of(CONNECTED, DISCONNECTED), notices);
	}

	@Test
	void anUnreachableServerBeforeEverConnectingStaysSilentThenAnnouncesConnected() throws IOException {
		server.route("/tracker/snapshot", FakeServer.Reply.ok("{}"));
		config.serverUrl = FakeServer.closedPortUrl();
		link.enqueueTracker(lobbyMessage("Kept"));

		link.tick();
		assertTrue(notices.isEmpty(), "never connected, so there is nothing to be disconnected from");

		config.serverUrl = server.url();
		link.tick();

		assertEquals(List.of(CONNECTED), notices);
		assertEquals(List.of("Kept"), firstPlayers(server.to("/tracker/snapshot")), "the message waited and was then delivered");
	}

	@Test
	void dataForAnotherAccountIsDroppedInsteadOfLinkingAgainForever() {
		server.route("/register", FakeServer.Reply.ok("{\"api_key\":\"KEY\",\"created\":false}"));
		server.route("/tracker/snapshot", new FakeServer.Reply(403, "{\"detail\":\"this API key may only be used for abc\"}"));

		link.enqueueTracker(lobbyMessage("Alice"));
		link.tick();
		link.tick();

		assertEquals(1, server.to("/register").size(), "the key is kept, not thrown away");
		assertEquals(List.of(CONNECTED), notices);
		assertEquals(1, server.to("/tracker/snapshot").size(), "the refused message is not sent again");
	}

	@Test
	void aRefusedHeartbeatIsNotRepeatedEveryTick() {
		server.route("/heartbeat", FakeServer.Reply.status(422));

		link.tick();
		link.tick();
		link.tick();

		assertEquals(1, server.to("/heartbeat").size());
	}

	// -------------------------------------------------------------- heartbeat

	@Test
	void theHeartbeatIsOnlySentWhenStatusChangesOrIsDue() {
		link.tick();
		assertEquals(1, server.to("/heartbeat").size());

		link.tick(); // nothing changed, and 15 seconds have not passed
		assertEquals(1, server.to("/heartbeat").size());

		link.setStatus(true);
		link.tick();
		assertEquals(2, server.to("/heartbeat").size());
		JsonObject inGame = object(server.to("/heartbeat").get(1));
		assertTrue(inGame.get("in_skyblock").getAsBoolean());
		assertTrue(inGame.get("online").getAsBoolean());
		assertEquals(PLAYER.toString(), inGame.get("player_uuid").getAsString());

		link.setStatus(true); // same again: not a change
		link.tick();
		assertEquals(2, server.to("/heartbeat").size());

		link.setStatus(false);
		link.tick();
		JsonObject left = object(server.to("/heartbeat").get(2));
		assertFalse(left.get("in_skyblock").getAsBoolean());
	}

	@Test
	void otherSuccessfulUploadsDoNotHoldBackTheHeartbeat() throws InterruptedException {
		// Tracker messages can go out every few seconds. Each one succeeding must not count as "still here": the website says an
		// account is online only while heartbeats keep coming, and it flickered offline when they did not.
		server.route("/tracker/party", FakeServer.Reply.ok("{}"));
		link.heartbeatEvery = java.time.Duration.ofMillis(20);
		link.tick();
		int before = server.to("/heartbeat").size();

		for (int round = 0; round < 3; round++) {
			Thread.sleep(40);
			link.enqueueTracker(TrackerPayload.party("Steve", java.util.List.of("Bob"), java.time.Instant.now()));
			link.tick();
		}

		assertEquals(before + 3, server.to("/heartbeat").size(), "one heartbeat per round even though the tracker upload succeeded each time");
		assertTrue(server.to("/tracker/party").size() >= 3);
	}

	@Test
	void theHeartbeatThreadSendsWhenDueWithoutWaitingForAnUploadRound() {
		link.tick();                                            // links and sends the first heartbeat
		link.heartbeatEvery = java.time.Duration.ZERO;
		int before = server.to("/heartbeat").size();

		link.heartbeatTick();

		assertEquals(before + 1, server.to("/heartbeat").size());
	}

	// ---------------------------------------------------------------- stop()

	@Test
	void stopMarksTheAccountOffline() {
		link.tick(); // links

		link.stop();

		FakeServer.Request goodbye = server.to("/heartbeat").get(server.to("/heartbeat").size() - 1);
		assertFalse(object(goodbye).get("online").getAsBoolean());
		assertFalse(object(goodbye).get("in_skyblock").getAsBoolean());
	}

	@Test
	void stopBeforeEverLinkingSendsNothing() {
		link.stop();

		assertEquals(0, server.count());
	}

	// ------------------------------------------------------- player tracker

	private static JsonObject lobbyMessage(String... players) {
		return TrackerPayload.build("Steve", "Hub", List.of(players),
				Instant.parse("2026-09-21T19:41:03Z"), Instant.parse("2026-09-21T19:40:55Z"));
	}

	private List<String> firstPlayers(List<FakeServer.Request> requests) {
		return requests.stream()
				.map(r -> object(r).getAsJsonArray("players").get(0).getAsString())
				.toList();
	}

	@Test
	void trackerMessagesGoToTheSnapshotEndpointWithTheKeyAndExactBody() {
		server.route("/tracker/snapshot", FakeServer.Reply.ok("{\"new\":[]}"));

		link.enqueueTracker(lobbyMessage("Alice", "Bob"));
		link.tick();

		FakeServer.Request seen = server.to("/tracker/snapshot").get(0);
		assertEquals("POST", seen.method());
		assertEquals("KEY-1", seen.header("X-API-Key"));
		assertEquals(lobbyMessage("Alice", "Bob"), object(seen));
	}

	@Test
	void partyMessagesGoToThePartyEndpointNotTheSnapshotOne() {
		server.route("/tracker/party", FakeServer.Reply.ok("{\"first_time\":[\"Alice\"]}"));
		server.route("/tracker/snapshot", FakeServer.Reply.ok("{}"));

		JsonObject party = TrackerPayload.party("Steve", List.of("Alice"), Instant.parse("2026-09-21T19:41:03Z"));
		link.enqueueTracker(lobbyMessage("Bob"));
		link.enqueueTracker(party);
		link.tick();

		assertEquals(1, server.to("/tracker/party").size());
		assertEquals(party, object(server.to("/tracker/party").get(0)));
		assertEquals("KEY-1", server.to("/tracker/party").get(0).header("X-API-Key"));
		assertEquals(1, server.to("/tracker/snapshot").size());
	}

	@Test
	void aPartyMessageWaitsOutAnOutageAndArrivesOnce() {
		java.util.concurrent.atomic.AtomicBoolean up = new java.util.concurrent.atomic.AtomicBoolean(false);
		server.route("/tracker/party", request -> up.get() ? FakeServer.Reply.ok("{}") : FakeServer.Reply.status(503));

		link.enqueueTracker(TrackerPayload.party("Steve", List.of("Alice"), Instant.parse("2026-09-21T19:41:03Z")));
		link.tick();
		up.set(true);
		link.tick();
		link.tick();

		assertEquals(2, server.to("/tracker/party").size(), "one failed try, then one that got through");
	}

	@Test
	void chatMessagesGoToTheChatEndpoint() {
		server.route("/tracker/chat", FakeServer.Reply.ok("{\"saved\":1}"));
		server.route("/tracker/snapshot", FakeServer.Reply.ok("{}"));
		server.route("/tracker/party", FakeServer.Reply.ok("{}"));

		JsonObject chat = TrackerPayload.chat("Steve", "The End",
				List.of(new TrackerPayload.ChatEntry("TEST", "PUBLIC", "hello", Instant.parse("2026-09-21T19:41:03Z"))));
		link.enqueueTracker(lobbyMessage("Bob"));
		link.enqueueTracker(chat);
		link.enqueueTracker(TrackerPayload.party("Steve", List.of("Alice"), Instant.parse("2026-09-21T19:41:03Z")));
		link.tick();

		assertEquals(1, server.to("/tracker/chat").size());
		assertEquals(chat, object(server.to("/tracker/chat").get(0)));
		assertEquals("KEY-1", server.to("/tracker/chat").get(0).header("X-API-Key"));
		assertEquals(1, server.to("/tracker/snapshot").size());
		assertEquals(1, server.to("/tracker/party").size());
	}

	@Test
	void finderReportsGoToTheFinderEndpointAndArePassedInOrder() {
		server.route("/finder/report", FakeServer.Reply.ok("{\"ok\":true}"));

		JsonObject searching = new JsonObject();
		searching.addProperty("target", "Steve");
		searching.addProperty("run", 7);
		searching.addProperty("status", "searching");
		searching.addProperty("hops", 1);
		JsonObject found = searching.deepCopy();
		found.addProperty("status", "found");
		found.addProperty("hops", 2);

		link.enqueueTracker(searching);
		link.enqueueTracker(found);
		link.tick();

		List<FakeServer.Request> seen = server.to("/finder/report");
		assertEquals(2, seen.size());
		assertEquals("KEY-1", seen.get(0).header("X-API-Key"));
		assertEquals(searching, object(seen.get(0)));
		assertEquals(found, object(seen.get(1)));
	}

	@Test
	void playerInfoAsksTheWebsiteWithTheKeyAndReadsTheAnswer() throws Exception {
		server.route("/api/player/" + PLAYER, FakeServer.Reply.ok(
				"{\"found\":true,\"name\":\"Steve\",\"discord\":\"steve_dc\",\"discordError\":null,\"timesMet\":3,"
				+ "\"chatLogs\":[{\"at\":\"2026-09-21T15:01:00.000Z\",\"channel\":\"Public\",\"world\":\"Hub\",\"message\":\"hi\"}]}"));
		link.tick();                       // links the account and learns the key

		PlayerInfo info = link.playerInfo(PLAYER, "Steve").get(5, java.util.concurrent.TimeUnit.SECONDS);

		assertEquals(PlayerInfo.Status.FOUND, info.status());
		assertEquals("steve_dc", info.discord());
		assertEquals(3, info.timesMet());
		assertEquals(List.of(new PlayerInfo.ChatLog("2026-09-21T15:01:00.000Z", "Public", "Hub", "hi")), info.chatLogs());
		FakeServer.Request request = server.to("/api/player/" + PLAYER).get(0);
		assertEquals("KEY-1", request.header("X-API-Key"));
		assertEquals("name=Steve", request.query());
	}

	@Test
	void playerInfoBeforeTheAccountIsLinkedIsAnErrorNotACrash() throws Exception {
		PlayerInfo info = link.playerInfo(PLAYER, "Steve").get(5, java.util.concurrent.TimeUnit.SECONDS);

		assertEquals(PlayerInfo.Status.ERROR, info.status());
		assertTrue(server.to("/api/player/" + PLAYER).isEmpty());
	}

	@Test
	void playerInfoDoesNotWaitForTheWebsite() {
		server.route("/api/player/" + PLAYER, request -> {
			try {
				Thread.sleep(600);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return FakeServer.Reply.ok("{\"found\":false}");
		});
		link.tick();

		long start = System.nanoTime();
		CompletableFuture<PlayerInfo> future = link.playerInfo(PLAYER, "Steve");

		assertTrue((System.nanoTime() - start) / 1_000_000 < 300, "playerInfo must return before the answer arrives");
		assertEquals(PlayerInfo.Status.NOT_IN_DATABASE, future.join().status());
	}

	@Test
	void theFinderCommandIsAskedForWithTheKeyAndPassedToTheListener() {
		server.route("/finder/command", FakeServer.Reply.ok("{\"target\":\"Steve\",\"world\":\"Hub\",\"run\":42}"));
		server.route("/register", FakeServer.Reply.ok("{\"api_key\":\"KEY-1\"}"));
		java.util.List<FinderHop.Command> heard = new java.util.ArrayList<>();
		link.setFinderListener(heard::add);

		link.tick();                       // links the account and learns the key
		link.pollFinder();

		assertEquals(List.of(new FinderHop.Command("Steve", "Hub", 42)), heard);
		assertEquals("KEY-1", server.to("/finder/command").get(0).header("X-API-Key"));
	}

	@Test
	void anEmptyFinderCommandIsPassedOnAsNull() {
		server.route("/finder/command", FakeServer.Reply.ok("{\"target\":null}"));
		java.util.List<FinderHop.Command> heard = new java.util.ArrayList<>();
		heard.add(new FinderHop.Command("x", "y", 1));
		link.setFinderListener(c -> { heard.clear(); heard.add(c); });

		link.tick();
		link.pollFinder();

		assertEquals(1, heard.size());
		assertEquals(null, heard.get(0));
	}

	@Test
	void aFailedOrBrokenFinderPollIsIgnoredAndNeverThrows() {
		java.util.List<FinderHop.Command> heard = new java.util.ArrayList<>();
		link.setFinderListener(heard::add);
		link.tick();

		server.route("/finder/command", FakeServer.Reply.status(500));
		link.pollFinder();
		server.route("/finder/command", FakeServer.Reply.ok("not json at all"));
		link.pollFinder();
		server.route("/finder/command", FakeServer.Reply.ok("[1,2]"));
		link.pollFinder();

		assertEquals(List.of(), heard);
	}

	@Test
	void theFinderIsNotPolledBeforeTheAccountIsLinked() {
		config.enrollSecret = "";
		link.setFinderListener(c -> { throw new AssertionError("must not be called"); });
		link.pollFinder();
		assertEquals(0, server.to("/finder/command").size());
	}

	@Test
	void trackerMessagesArriveInTheOrderTheyWereQueued() {
		server.route("/tracker/snapshot", FakeServer.Reply.ok("{}"));

		link.enqueueTracker(lobbyMessage("First"));
		link.enqueueTracker(lobbyMessage("Second"));
		link.enqueueTracker(lobbyMessage("Third"));
		link.tick();

		assertEquals(List.of("First", "Second", "Third"), firstPlayers(server.to("/tracker/snapshot")));
	}

	@Test
	void trackerMessagesAreNotSentBeforeTheAccountIsLinked() {
		config.enrollSecret = "";
		config.apiKey = "";

		link.enqueueTracker(lobbyMessage("Alice"));
		link.tick();

		assertEquals(0, server.count());
	}

	@Test
	void trackerMessagesSurviveAnOutageAndArriveOnceInOrder() {
		List<String> delivered = new CopyOnWriteArrayList<>();
		AtomicBoolean down = new AtomicBoolean(false);
		server.route("/tracker/snapshot", request -> {
			if (down.get()) {
				return FakeServer.Reply.status(503);
			}
			delivered.add(object(request).getAsJsonArray("players").get(0).getAsString());
			return FakeServer.Reply.ok("{}");
		});

		link.tick();                                   // links and connects
		down.set(true);
		link.enqueueTracker(lobbyMessage("One"));
		link.enqueueTracker(lobbyMessage("Two"));
		link.tick();
		assertEquals(List.of(CONNECTED, DISCONNECTED), notices, "the outage is announced once");
		assertTrue(delivered.isEmpty());

		down.set(false);
		link.tick();

		assertEquals(List.of("One", "Two"), delivered);
		assertEquals(List.of(CONNECTED, DISCONNECTED, CONNECTED), notices);

		link.tick();
		assertEquals(List.of("One", "Two"), delivered, "nothing is sent twice");
	}

	@Test
	void aMessageTheWebsiteRefusesIsDroppedWithoutBlockingTheOnesBehindIt() {
		List<String> delivered = new CopyOnWriteArrayList<>();
		server.route("/tracker/snapshot", request -> {
			if (request.body().contains("BadOne")) {
				return FakeServer.Reply.status(422);
			}
			delivered.add(object(request).getAsJsonArray("players").get(0).getAsString());
			return FakeServer.Reply.ok("{}");
		});

		link.enqueueTracker(lobbyMessage("BadOne"));
		link.enqueueTracker(lobbyMessage("GoodOne"));
		link.tick();
		link.tick();

		assertEquals(List.of("GoodOne"), delivered);
		assertEquals(2, server.to("/tracker/snapshot").size(), "one try each: the bad one was dropped, not retried");
	}

	@Test
	void onlyTheNewestTwoHundredMessagesAreKeptWhileTheWebsiteIsUnreachable() throws IOException {
		List<String> delivered = new CopyOnWriteArrayList<>();
		server.route("/tracker/snapshot", request -> {
			delivered.add(object(request).getAsJsonArray("players").get(0).getAsString());
			return FakeServer.Reply.ok("{}");
		});

		link.tick();                                    // link first, so the key is known
		String real = config.serverUrl;
		config.serverUrl = FakeServer.closedPortUrl();
		for (int i = 1; i <= 250; i++) {
			link.enqueueTracker(lobbyMessage("Msg" + i));
		}
		link.tick();
		assertTrue(delivered.isEmpty());

		config.serverUrl = real;
		link.tick();

		assertEquals(200, delivered.size());
		assertEquals("Msg51", delivered.get(0), "the oldest 50 were dropped");
		assertEquals("Msg250", delivered.get(199));
	}

	@Test
	void aRejectedKeyOnATrackerMessageLinksAgainAndTheMessageStillArrives() {
		AtomicInteger registrations = new AtomicInteger();
		server.route("/register", request -> FakeServer.Reply.ok(
				"{\"api_key\":\"KEY-" + registrations.incrementAndGet() + "\",\"created\":false}"));
		server.route("/tracker/snapshot", request ->
				"KEY-1".equals(request.header("X-API-Key")) ? FakeServer.Reply.status(401) : FakeServer.Reply.ok("{}"));

		link.enqueueTracker(lobbyMessage("Alice"));
		link.tick();   // KEY-1 is rejected, the message is kept
		link.tick();   // linked again as KEY-2, the message is delivered

		assertEquals(2, server.to("/register").size());
		assertEquals("KEY-2", server.to("/tracker/snapshot").get(1).header("X-API-Key"));
		assertEquals(List.of(CONNECTED, DISCONNECTED, CONNECTED), notices);
	}

	@Test
	void stopSendsTheLastTrackerMessages() {
		server.route("/tracker/snapshot", FakeServer.Reply.ok("{}"));
		link.tick();   // links

		link.enqueueTracker(lobbyMessage("Last"));
		link.stop();

		assertEquals(List.of("Last"), firstPlayers(server.to("/tracker/snapshot")));
	}

	@Test
	void nothingIsQueuedForTheTrackerWhenSwitchedOff() {
		config.enabled = false;

		link.enqueueTracker(lobbyMessage("Alice"));
		link.tick();

		assertEquals(0, server.count());
	}

	// ------------------------------------------------------- commands from the page

	private List<ServerLink.RemoteCommand> ranCommands(java.util.function.Function<ServerLink.RemoteCommand, String> outcome) {
		List<ServerLink.RemoteCommand> ran = new CopyOnWriteArrayList<>();
		link.setCommandRunner(command -> {
			ran.add(command);
			return CompletableFuture.completedFuture(outcome.apply(command));
		});
		return ran;
	}

	@Test
	void aChatCommandIsRunAndReportedDone() {
		server.route("/commands/next", FakeServer.Reply.ok("{\"command\":{\"id\":7,\"type\":\"CHAT\",\"payload\":\"hello there\"}}"));
		server.route("/commands/7/result", FakeServer.Reply.ok("{\"ok\":true}"));
		List<ServerLink.RemoteCommand> ran = ranCommands(c -> null);

		link.tick();
		link.pollCommands();

		assertEquals(List.of(new ServerLink.RemoteCommand(7, "CHAT", "hello there")), ran);
		assertEquals("KEY-1", server.to("/commands/next").get(0).header("X-API-Key"));
		FakeServer.Request result = server.to("/commands/7/result").get(0);
		assertEquals("KEY-1", result.header("X-API-Key"));
		assertEquals("DONE", object(result).get("status").getAsString());
	}

	@Test
	void aChatCommandTheGameCouldNotSendIsReportedFailedWithTheReason() {
		server.route("/commands/next", FakeServer.Reply.ok("{\"command\":{\"id\":8,\"type\":\"CHAT\",\"payload\":\"hi\"}}"));
		server.route("/commands/8/result", FakeServer.Reply.ok("{\"ok\":true}"));
		ranCommands(c -> "not in a world");

		link.tick();
		link.pollCommands();

		JsonObject result = object(server.to("/commands/8/result").get(0));
		assertEquals("FAILED", result.get("status").getAsString());
		assertEquals("not in a world", result.get("reason").getAsString());
	}

	@Test
	void aChatMessageStartingWithASlashIsNeverRun() {
		server.route("/commands/next", FakeServer.Reply.ok("{\"command\":{\"id\":9,\"type\":\"CHAT\",\"payload\":\"/warp hub\"}}"));
		server.route("/commands/9/result", FakeServer.Reply.ok("{\"ok\":true}"));
		List<ServerLink.RemoteCommand> ran = ranCommands(c -> null);

		link.tick();
		link.pollCommands();

		assertTrue(ran.isEmpty());
		assertEquals("FAILED", object(server.to("/commands/9/result").get(0)).get("status").getAsString());
	}

	@Test
	void anUnknownCommandIsReportedFailedWithoutRunning() {
		server.route("/commands/next", FakeServer.Reply.ok("{\"command\":{\"id\":10,\"type\":\"DANCE\",\"payload\":null}}"));
		server.route("/commands/10/result", FakeServer.Reply.ok("{\"ok\":true}"));
		List<ServerLink.RemoteCommand> ran = ranCommands(c -> null);

		link.tick();
		link.pollCommands();

		assertTrue(ran.isEmpty());
		assertEquals("FAILED", object(server.to("/commands/10/result").get(0)).get("status").getAsString());
	}

	@Test
	void aQuitIsReportedDoneBeforeTheGameIsTold() {
		server.route("/commands/next", FakeServer.Reply.ok("{\"command\":{\"id\":11,\"type\":\"QUIT\",\"payload\":null}}"));
		server.route("/commands/11/result", FakeServer.Reply.ok("{\"ok\":true}"));
		AtomicBoolean reportedFirst = new AtomicBoolean();
		link.setCommandRunner(command -> {
			reportedFirst.set(server.to("/commands/11/result").size() == 1);
			return CompletableFuture.completedFuture(null);
		});

		link.tick();
		link.pollCommands();

		assertTrue(reportedFirst.get());
		assertEquals("DONE", object(server.to("/commands/11/result").get(0)).get("status").getAsString());
	}

	@Test
	void noCommandMeansNothingRunsAndNothingIsReported() {
		server.route("/commands/next", FakeServer.Reply.ok("{\"command\":null}"));
		List<ServerLink.RemoteCommand> ran = ranCommands(c -> null);

		link.tick();
		link.pollCommands();

		assertTrue(ran.isEmpty());
		assertEquals(1, server.to("/commands/next").size());
	}

	@Test
	void commandsAreNotAskedForBeforeTheAccountIsLinked() {
		ranCommands(c -> null);

		link.pollCommands();

		assertEquals(0, server.to("/commands/next").size());
	}

	@Test
	void aFailedOrBrokenCommandPollIsIgnoredAndNeverThrows() {
		List<ServerLink.RemoteCommand> ran = ranCommands(c -> null);
		link.tick();

		server.route("/commands/next", FakeServer.Reply.status(500));
		link.pollCommands();
		server.route("/commands/next", FakeServer.Reply.ok("not json at all"));
		link.pollCommands();

		assertTrue(ran.isEmpty());
	}
}
