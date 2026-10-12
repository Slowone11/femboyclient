package com.femboyclient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebClientTest {

	private FakeServer server;

	@BeforeEach
	void start() throws IOException {
		server = new FakeServer();
	}

	@AfterEach
	void stop() {
		server.close();
	}

	private WebClient client() {
		return new WebClient(server::url);
	}

	@Test
	void getReturnsTheStatusAndBody() {
		server.route("/health", FakeServer.Reply.ok("{\"status\":\"ok\"}"));

		WebClient.Response response = client().get("/health", Duration.ofSeconds(2), Map.of()).join();

		assertTrue(response.ok());
		assertTrue(response.reachable());
		assertEquals("{\"status\":\"ok\"}", response.body());
		assertNull(response.error());
	}

	@Test
	void postSendsTheJsonBodyAndHeaders() {
		server.route("/log", FakeServer.Reply.ok("{}"));

		client().post("/log", "[{\"a\":1}]", Duration.ofSeconds(2), Map.of("X-API-Key", "secret")).join();

		FakeServer.Request seen = server.to("/log").get(0);
		assertEquals("POST", seen.method());
		assertEquals("[{\"a\":1}]", seen.body());
		assertEquals("secret", seen.header("X-API-Key"));
		assertEquals("application/json", seen.header("Content-Type"));
	}

	@Test
	void anErrorStatusIsReachableButNotOk() {
		server.route("/health", FakeServer.Reply.status(500));

		WebClient.Response response = client().get("/health", Duration.ofSeconds(2), Map.of()).join();

		assertFalse(response.ok());
		assertTrue(response.reachable());
		assertEquals("HTTP 500", response.describe());
	}

	@Test
	void aTrailingSlashOnTheServerUrlIsHarmless() {
		server.route("/health", FakeServer.Reply.ok("{}"));

		WebClient slashed = new WebClient(() -> server.url() + "///");
		assertTrue(slashed.get("/health", Duration.ofSeconds(2), Map.of()).join().ok());
	}

	@Test
	void aClosedPortBecomesAFailedResponseNotAnException() throws IOException {
		String closed = FakeServer.closedPortUrl();

		WebClient.Response response = new WebClient(() -> closed)
				.get("/health", Duration.ofSeconds(2), Map.of()).join();

		assertFalse(response.ok());
		assertFalse(response.reachable());
		assertEquals(-1, response.status());
		assertEquals("connection refused", response.describe());
	}

	@Test
	void anAddressThatCannotBeReachedGivesWayToTheNextOneAndTheWorkingOneIsUsedFirstAfterwards() throws IOException {
		server.route("/health", FakeServer.Reply.ok("{\"status\":\"ok\"}"));
		String closed = FakeServer.closedPortUrl();
		WebClient client = WebClient.withFallbacks(() -> java.util.List.of(closed, server.url()));

		WebClient.Response first = client.get("/health", Duration.ofSeconds(2), Map.of()).join();

		assertTrue(first.ok(), "the first address is closed, so the second answers");
		assertEquals(server.url(), client.currentUrl());
		assertEquals(1, server.to("/health").size());
		assertTrue(client.get("/health", Duration.ofSeconds(2), Map.of()).join().ok());
		assertEquals(2, server.to("/health").size());
	}

	@Test
	void whenNoAddressAnswersTheReasonForTheFirstOneIsGiven() throws IOException {
		String one = FakeServer.closedPortUrl(), two = FakeServer.closedPortUrl();
		WebClient.Response response = WebClient.withFallbacks(() -> java.util.List.of(one, two))
				.get("/health", Duration.ofSeconds(2), Map.of()).join();

		assertFalse(response.reachable());
		assertEquals("connection refused", response.describe());
	}

	@Test
	void anErrorAnswerFromTheFirstAddressStopsTheSearch() {
		server.route("/health", new FakeServer.Reply(500, "oops"));
		WebClient.Response response = WebClient.withFallbacks(() -> java.util.List.of(server.url(), "http://127.0.0.1:1"))
				.get("/health", Duration.ofSeconds(2), Map.of()).join();

		assertEquals(500, response.status());
	}

	@Test
	void aSlowServerTimesOut() {
		server.route("/slow", request -> {
			try {
				Thread.sleep(1500);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			return FakeServer.Reply.ok("{}");
		});

		WebClient.Response response = client().get("/slow", Duration.ofMillis(300), Map.of()).join();

		assertFalse(response.reachable());
		assertEquals("timed out", response.describe());
	}

	@Test
	void aMalformedServerUrlIsReportedNotThrown() {
		for (String bad : new String[] {"127.0.0.1:8000", "not a url", "ftp//x", ""}) {
			WebClient.Response response = new WebClient(() -> bad)
					.get("/health", Duration.ofSeconds(1), Map.of()).join();

			assertFalse(response.reachable(), "should fail cleanly for: '" + bad + "'");
			assertTrue(response.describe().contains("bad server address"), response.describe());
		}
	}

	@Test
	void anIllegalHeaderValueIsReportedNotThrown() {
		WebClient.Response response = client()
				.get("/health", Duration.ofSeconds(1), Map.of("X-API-Key", "line\nbreak")).join();

		assertFalse(response.reachable());
		assertTrue(response.describe().contains("bad server address or header"));
	}

	@Test
	void theBaseUrlIsReadOnEveryRequest() throws IOException {
		server.route("/health", FakeServer.Reply.ok("{}"));
		String[] current = {FakeServer.closedPortUrl()};
		WebClient client = new WebClient(() -> current[0]);

		assertFalse(client.get("/health", Duration.ofSeconds(1), Map.of()).join().reachable());

		current[0] = server.url(); // the player edits the config
		assertTrue(client.get("/health", Duration.ofSeconds(1), Map.of()).join().ok());
	}
}
