package com.femboyclient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GitHubReleasesTest {

	private static final String LATEST = "/repos/owner/mod/releases/latest";
	private static final String JAR_ASSET = "/repos/owner/mod/releases/assets/7";
	private static final String STORAGE = "/storage/7";

	@TempDir
	Path folder;

	private FakeServer server;
	private final List<String> notices = new ArrayList<>();

	@BeforeEach
	void start() throws IOException {
		server = new FakeServer();
	}

	@AfterEach
	void stop() {
		server.close();
	}

	private GitHubReleases github() {
		return new GitHubReleases(server.url(), "owner/mod", "secret-token", notices::add);
	}

	private static String release(String... assets) {
		return "{\"tag_name\":\"build-7\",\"assets\":[" + String.join(",", assets) + "]}";
	}

	private static String asset(long id, String name, String digest) {
		return "{\"id\":" + id + ",\"name\":\"" + name + "\"" + (digest == null ? "" : ",\"digest\":\"" + digest + "\"") + "}";
	}

	/** The asset API sends the download on to signed storage, as GitHub does. */
	private void serveJar(byte[] jar) {
		server.route(JAR_ASSET, new FakeServer.Reply(302, "", Map.of("Location", server.url() + STORAGE), null));
		server.route(STORAGE, FakeServer.Reply.bytes(jar));
	}

	@Test
	void theModJarOfTheLatestReleaseIsPickedWithItsDigest() throws IOException {
		String sha = "ab".repeat(32);
		server.route(LATEST, FakeServer.Reply.ok(release(
				asset(5, "femboyclient-0.1.0-sources.jar", "sha256:" + "cd".repeat(32)),
				asset(7, "femboyclient-0.1.0.jar", "sha256:" + sha.toUpperCase()))));

		GitHubReleases github = github();
		assertEquals(sha, github.latestSha());
		assertEquals(7, github.latest().id());

		FakeServer.Request request = server.to(LATEST).get(0);
		assertEquals("Bearer secret-token", request.header("Authorization"));
		assertEquals("femboyclient-updater", request.header("User-Agent"));
		assertEquals("application/vnd.github+json", request.header("Accept"));
		assertEquals("2022-11-28", request.header("X-GitHub-Api-Version"));
	}

	@Test
	void theDownloadFollowsTheRedirectWithoutTheToken() throws IOException {
		byte[] jar = ModUpdaterTest.jar("femboyclient", "new");
		server.route(LATEST, FakeServer.Reply.ok(release(asset(7, "femboyclient-0.1.0.jar", "sha256:" + ModUpdater.sha256(jar)))));
		serveJar(jar);

		GitHubReleases github = github();
		github.latestSha();

		assertArrayEquals(jar, github.download());
		FakeServer.Request api = server.to(JAR_ASSET).get(0);
		assertEquals("Bearer secret-token", api.header("Authorization"));
		assertEquals("application/octet-stream", api.header("Accept"));
		assertNull(server.to(STORAGE).get(0).header("Authorization"), "the token must never leave the API");
	}

	@Test
	void anUnchangedReleaseIsAskedForWithItsETagAndKeepsItsChecksum() throws IOException {
		String sha = "ef".repeat(32);
		server.route(LATEST, request -> "\"v1\"".equals(request.header("If-None-Match"))
				? FakeServer.Reply.status(304)
				: FakeServer.Reply.ok(release(asset(7, "femboyclient-0.1.0.jar", "sha256:" + sha))).withHeader("ETag", "\"v1\""));

		GitHubReleases github = github();
		assertEquals(sha, github.latestSha());
		assertEquals(sha, github.latestSha());

		List<FakeServer.Request> asked = server.to(LATEST);
		assertNull(asked.get(0).header("If-None-Match"));
		assertEquals("\"v1\"", asked.get(1).header("If-None-Match"));
		assertEquals(7, github.latest().id());
	}

	@Test
	void aReleaseWithoutDigestsUsesTheChecksumFileBesideTheJar() throws IOException {
		String sha = "12".repeat(32);
		server.route(LATEST, FakeServer.Reply.ok(release(
				asset(7, "femboyclient-0.1.0.jar", null),
				asset(9, "femboyclient-0.1.0.jar.sha256", null))));
		server.route("/repos/owner/mod/releases/assets/9",
				FakeServer.Reply.bytes((sha + "  femboyclient-0.1.0.jar\n").getBytes(StandardCharsets.US_ASCII)));

		assertEquals(sha, github().latestSha());
	}

	@Test
	void aReleaseWithNoWayToCheckTheJarIsNotUsed() throws IOException {
		server.route(LATEST, FakeServer.Reply.ok(release(asset(7, "femboyclient-0.1.0.jar", null))));

		assertNull(github().latestSha());
	}

	@Test
	void aRefusedTokenPausesUpdatesWithOneNotice() throws IOException {
		server.route(LATEST, FakeServer.Reply.status(401));

		GitHubReleases github = github();
		assertNull(github.latestSha());
		assertNull(github.latestSha());

		assertEquals(1, notices.size());
		assertTrue(notices.get(0).contains("expired"));
	}

	@Test
	void noReleaseOrNoModJarMeansNoUpdate() throws IOException {
		server.route(LATEST, FakeServer.Reply.status(404));
		assertNull(github().latestSha());

		server.route(LATEST, FakeServer.Reply.ok(release(asset(5, "femboyclient-0.1.0-sources.jar", "sha256:" + "cd".repeat(32)))));
		assertNull(github().latestSha());
		assertTrue(notices.isEmpty());
	}

	@Test
	void theUpdaterStagesANewReleaseFromGitHub() throws IOException {
		Path own = folder.resolve("femboyclient-0.1.0.jar");
		Files.write(own, ModUpdaterTest.jar("femboyclient", "old"));
		byte[] newer = ModUpdaterTest.jar("femboyclient", "new");
		server.route(LATEST, FakeServer.Reply.ok(release(asset(7, "femboyclient-0.1.0.jar", "sha256:" + ModUpdater.sha256(newer)))));
		serveJar(newer);
		List<Path> swaps = new ArrayList<>();

		new ModUpdater(own, github(), (update, target) -> swaps.add(update), notices::add).check();

		Path staged = folder.resolve("femboyclient-0.1.0.jar.update");
		assertArrayEquals(newer, Files.readAllBytes(staged));
		assertEquals(List.of(staged), swaps);
		assertEquals(1, notices.size());
	}

	@Test
	void aJarThatDoesNotMatchItsDigestIsNotInstalled() throws IOException {
		Path own = folder.resolve("femboyclient-0.1.0.jar");
		Files.write(own, ModUpdaterTest.jar("femboyclient", "old"));
		byte[] newer = ModUpdaterTest.jar("femboyclient", "new");
		server.route(LATEST, FakeServer.Reply.ok(release(
				asset(7, "femboyclient-0.1.0.jar", "sha256:" + ModUpdater.sha256(ModUpdaterTest.jar("femboyclient", "other"))))));
		serveJar(newer);
		List<Path> swaps = new ArrayList<>();

		new ModUpdater(own, github(), (update, target) -> swaps.add(update), notices::add).check();

		assertFalse(Files.exists(folder.resolve("femboyclient-0.1.0.jar.update")));
		assertTrue(swaps.isEmpty());
	}

	@Test
	void aJarOfAnotherModFromGitHubIsNotInstalled() throws IOException {
		Path own = folder.resolve("femboyclient-0.1.0.jar");
		Files.write(own, ModUpdaterTest.jar("femboyclient", "old"));
		byte[] other = ModUpdaterTest.jar("someothermod", "x");
		server.route(LATEST, FakeServer.Reply.ok(release(asset(7, "femboyclient-0.1.0.jar", "sha256:" + ModUpdater.sha256(other)))));
		serveJar(other);
		List<Path> swaps = new ArrayList<>();

		new ModUpdater(own, github(), (update, target) -> swaps.add(update), notices::add).check();

		assertFalse(Files.exists(folder.resolve("femboyclient-0.1.0.jar.update")));
		assertTrue(swaps.isEmpty());
	}
}
