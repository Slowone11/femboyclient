package com.femboyclient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WikiClientTest {

	private static final String PAGE = "{\"parse\":{\"title\":\"Hyperion\",\"pageid\":1,\"text\":{\"*\":\"<p>Hi</p>\"}}}";
	private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0};

	@TempDir
	Path cache;
	private FakeServer server;
	private ExecutorService worker;

	@BeforeEach
	void start() throws IOException {
		server = new FakeServer();
		worker = Executors.newSingleThreadExecutor();
	}

	@AfterEach
	void stop() {
		server.close();
		worker.shutdownNow();
	}

	private WikiClient client(Path dir) {
		return new WikiClient(server.url(), "FemboyClient/test", dir, Duration.ofHours(24), worker, System::currentTimeMillis);
	}

	@Test
	void searchSendsUserAgentAndReadsTitles() {
		server.route("/api.php", FakeServer.Reply.ok("[\"hyp\",[\"Hyperion\",\"Hyper Cleaver\"],[\"\",\"\"],[\"a\",\"b\"]]"));
		List<String> titles = client(null).search("hyp", 8).join();
		assertEquals(List.of("Hyperion", "Hyper Cleaver"), titles);
		FakeServer.Request request = server.to("/api.php").get(0);
		assertEquals("FemboyClient/test", request.header("User-Agent"));
		assertTrue(request.query().contains("action=opensearch"));
		assertTrue(request.query().contains("search=hyp"));
	}

	@Test
	void searchesAreRememberedInMemory() {
		server.route("/api.php", FakeServer.Reply.ok("[\"hyp\",[\"Hyperion\"],[],[]]"));
		WikiClient client = client(null);
		client.search("hyp", 8).join();
		client.search("HYP ", 8).join();
		assertEquals(1, server.to("/api.php").size());
	}

	@Test
	void pageIsCachedOnDisk() {
		server.route("/api.php", FakeServer.Reply.ok(PAGE));
		WikiClient.Page page = client(cache).page("hyperion").join();
		assertEquals("Hyperion", page.title());
		assertEquals("<p>Hi</p>", page.html());
		// A new client (as after a restart) reads it from disk, with the server gone.
		server.route("/api.php", FakeServer.Reply.status(500));
		assertEquals("Hyperion", client(cache).page("hyperion").join().title());
		assertEquals(1, server.to("/api.php").size());
	}

	@Test
	void missingPageIsNotFound() {
		server.route("/api.php", FakeServer.Reply.ok("{\"error\":{\"code\":\"missingtitle\",\"info\":\"nope\"}}"));
		CompletionException e = assertThrows(CompletionException.class, () -> client(null).page("Nope").join());
		assertInstanceOf(WikiClient.NotFound.class, e.getCause());
	}

	@Test
	void serverErrorsAndDeadServersFailTheFuture() throws IOException {
		server.route("/api.php", FakeServer.Reply.status(503));
		assertThrows(CompletionException.class, () -> client(null).search("x", 8).join());
		WikiClient dead = new WikiClient(FakeServer.closedPortUrl(), "ua", null, Duration.ofHours(1), worker, System::currentTimeMillis);
		assertThrows(CompletionException.class, () -> dead.page("x").join());
	}

	@Test
	void imagesMustBePngAndAskForTheOriginal() {
		server.route("/images/a.png/revision/latest", FakeServer.Reply.bytes(PNG));
		server.route("/images/b.webp/revision/latest", FakeServer.Reply.bytes(new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 0, 0}));
		WikiClient client = client(cache);
		assertArrayEquals(PNG, client.image(server.url() + "/images/a.png/revision/latest/scale-to-width-down/32?cb=1").join());
		assertTrue(server.to("/images/a.png/revision/latest").get(0).query().contains("format=original"));
		assertThrows(CompletionException.class, () -> client.image(server.url() + "/images/b.webp/revision/latest").join());
	}

	@Test
	void requestsAreSpacedOut() {
		server.route("/api.php", FakeServer.Reply.ok("[\"q\",[],[],[]]"));
		WikiClient client = client(null);
		long start = System.currentTimeMillis();
		client.search("a", 8).join();
		client.search("b", 8).join();
		client.search("c", 8).join();
		assertTrue(System.currentTimeMillis() - start >= 2 * WikiClient.MIN_GAP_MS);
	}

	@Test
	void textFallsBackToAnOldCopy() {
		server.route("/items", FakeServer.Reply.ok("{\"items\":[]}"));
		WikiClient client = client(cache);
		assertEquals("{\"items\":[]}", client.text(server.url() + "/items", "items.json", Duration.ZERO).join());
		server.route("/items", FakeServer.Reply.status(500));
		assertEquals("{\"items\":[]}", client.text(server.url() + "/items", "items.json", Duration.ZERO).join());
	}

	@Test
	void originalImageAddress() {
		assertEquals("https://x/a.png/revision/latest?cb=1&format=original",
				WikiClient.originalImage("https://x/a.png/revision/latest/scale-to-width-down/24?cb=1"));
		assertEquals("https://x/a.png?format=original", WikiClient.originalImage("https://x/a.png"));
	}

	@Test
	void pageUrlForTheBrowser() {
		assertEquals("https://w/wiki/Necron%27s_Blade", new WikiClient("https://w", "ua", null, Duration.ZERO, worker,
				System::currentTimeMillis).pageUrl("Necron's Blade"));
	}

	@Test
	void gifBecomesPng() throws Exception {
		java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
		java.io.ByteArrayOutputStream gif = new java.io.ByteArrayOutputStream();
		javax.imageio.ImageIO.write(image, "gif", gif);
		assertTrue(WikiClient.isGif(gif.toByteArray()));
		assertTrue(WikiClient.isPng(WikiClient.gifToPng(gif.toByteArray())));
	}

	@Test
	void thumbnailsFollowRedirectsAndSkipPagesWithout() throws IOException {
		String body = "{\"query\":{\"redirects\":[{\"from\":\"Necron's Blade (Unrefined)\",\"to\":\"Necron's Blade\"}],"
				+ "\"pages\":{\"1\":{\"title\":\"Plasma\",\"thumbnail\":{\"source\":\"https://img/Plasma.png\"}},"
				+ "\"2\":{\"title\":\"Hyperion\"},\"3\":{\"title\":\"Necron's Blade\",\"thumbnail\":{\"source\":\"https://img/NB.png\"}}}}}";
		assertEquals(java.util.Map.of("Plasma", "https://img/Plasma.png", "Necron's Blade (Unrefined)", "https://img/NB.png"),
				WikiClient.parseThumbnails(body, List.of("Plasma", "Hyperion", "Necron's Blade (Unrefined)")));
	}

	@Test
	void gzippedAnswersAreUnzipped() throws IOException {
		java.io.ByteArrayOutputStream zipped = new java.io.ByteArrayOutputStream();
		try (java.util.zip.GZIPOutputStream out = new java.util.zip.GZIPOutputStream(zipped)) {
			out.write(PAGE.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		server.route("/api.php", FakeServer.Reply.bytes(zipped.toByteArray()).withHeader("Content-Encoding", "gzip"));
		assertEquals("<p>Hi</p>", client(null).page("Hyperion").join().html());
		assertEquals("gzip", server.to("/api.php").get(0).header("Accept-Encoding"));
	}
}
