package com.femboyclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * Talks to the SkyBlock wiki (the Fandom one: the official wiki.hypixel.net closed in July 2026). It is a MediaWiki, so
 * autocomplete is {@code api.php?action=opensearch} and a page is {@code api.php?action=parse}.
 *
 * Every request runs on one background thread, at most one every {@link #MIN_GAP_MS}, with a User-Agent naming this mod, as
 * MediaWiki asks. Answers are kept in memory (the most recent ones) and pages and images also on disk for {@code maxAge}, so a
 * page read again is instant and works offline. Failures complete the future exceptionally; nothing here ever blocks the caller.
 * Kept free of Minecraft classes, so it is tested against a local server.
 */
public final class WikiClient {

	public static final String FANDOM = "https://hypixel-skyblock.fandom.com";
	static final long MIN_GAP_MS = 150;
	private static final Duration TIMEOUT = Duration.ofSeconds(10);
	private static final int MAX_IMAGE_BYTES = 2 * 1024 * 1024;

	/** A page: its real title (after redirects) and its HTML. */
	public record Page(String title, String html) {
	}

	/** The wiki has no page by that name. */
	public static final class NotFound extends IOException {
		NotFound(String title) {
			super("no page called " + title);
		}
	}

	private final String site;
	private final String userAgent;
	private final Path cacheDir;               // null: memory only
	private final Duration maxAge;
	private final Executor worker, imageWorker;
	private final LongSupplier clock;
	private final HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_2)            // falls back to 1.1 by itself
			.connectTimeout(Duration.ofSeconds(5))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();

	private final Map<String, List<String>> searches = lru(64);
	private final Map<String, Page> pages = lru(24);
	private long lastRequest;

	/**
	 * @param site     the wiki's address without a trailing slash, e.g. {@link #FANDOM}
	 * @param cacheDir where pages and images are kept, or null
	 * @param clock    milliseconds now (System::currentTimeMillis outside tests)
	 */
	public WikiClient(String site, String userAgent, Path cacheDir, Duration maxAge, Executor worker, LongSupplier clock) {
		this(site, userAgent, cacheDir, maxAge, worker, worker, clock);
	}

	/** @param imageWorker where pictures download, so they never wait behind the wiki's API (several threads is fine there) */
	public WikiClient(String site, String userAgent, Path cacheDir, Duration maxAge, Executor worker, Executor imageWorker, LongSupplier clock) {
		this.imageWorker = imageWorker;
		this.site = site;
		this.userAgent = userAgent;
		this.cacheDir = cacheDir;
		this.maxAge = maxAge;
		this.worker = worker;
		this.clock = clock;
	}

	/** The page in a browser. */
	public String pageUrl(String title) {
		return site + "/wiki/" + URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8).replace("%2F", "/").replace("%3A", ":");
	}

	/** Up to {@code limit} page titles for what is typed so far. */
	public CompletableFuture<List<String>> search(String query, int limit) {
		String key = query.strip().toLowerCase(java.util.Locale.ROOT) + "|" + limit;
		synchronized (searches) {
			List<String> known = searches.get(key);
			if (known != null) {
				return CompletableFuture.completedFuture(known);
			}
		}
		return run(() -> {
			String body = get(site + "/api.php?action=opensearch&format=json&namespace=0&limit=" + limit + "&search="
					+ encode(query.strip()), "application/json");
			List<String> titles = parseSearch(body);
			synchronized (searches) {
				searches.put(key, titles);
			}
			return titles;
		});
	}

	/**
	 * The small picture the wiki has for each of these pages (searched-for items), by the title asked for. Pages without one
	 * are left out. One request for all of them.
	 */
	public CompletableFuture<Map<String, String>> thumbnails(List<String> titles) {
		if (titles.isEmpty()) {
			return CompletableFuture.completedFuture(Map.of());
		}
		return run(() -> parseThumbnails(get(site + "/api.php?action=query&format=json&prop=pageimages&piprop=thumbnail&pithumbsize=32"
				+ "&redirects=1&titles=" + encode(String.join("|", titles)), "application/json"), titles));
	}

	static Map<String, String> parseThumbnails(String body, List<String> asked) throws IOException {
		try {
			JsonObject query = JsonParser.parseString(body).getAsJsonObject().getAsJsonObject("query");
			Map<String, String> renamed = new java.util.HashMap<>();          // asked title -> real title
			for (String list : new String[] {"normalized", "redirects"}) {
				if (query.has(list)) {
					for (JsonElement step : query.getAsJsonArray(list)) {
						JsonObject o = step.getAsJsonObject();
						renamed.put(o.get("from").getAsString(), o.get("to").getAsString());
					}
				}
			}
			Map<String, String> byTitle = new java.util.HashMap<>();
			for (var page : query.getAsJsonObject("pages").entrySet()) {
				JsonObject o = page.getValue().getAsJsonObject();
				if (o.has("thumbnail")) {
					byTitle.put(o.get("title").getAsString(), o.getAsJsonObject("thumbnail").get("source").getAsString());
				}
			}
			Map<String, String> result = new java.util.HashMap<>();
			for (String title : asked) {
				String real = title;
				for (int i = 0; i < 3 && renamed.containsKey(real); i++) {
					real = renamed.get(real);
				}
				String url = byTitle.get(real);
				if (url != null) {
					result.put(title, url);
				}
			}
			return result;
		} catch (RuntimeException e) {
			throw new IOException("bad picture answer");
		}
	}

	/** A page's HTML, following redirects. Completes with {@link NotFound} when there is no such page. */
	public CompletableFuture<Page> page(String title) {
		String key = title.strip();
		synchronized (pages) {
			Page known = pages.get(key);
			if (known != null) {
				return CompletableFuture.completedFuture(known);
			}
		}
		return run(() -> {
			Path file = cacheFile("pages", key, ".json");
			Page page = readCachedPage(file);
			if (page == null) {
				String body = get(site + "/api.php?action=parse&format=json&prop=text&redirects=1&disablelimitreport=1&page="
						+ encode(key), "application/json");
				page = parsePage(body, key);
				writeCachedPage(file, page);
			}
			synchronized (pages) {
				pages.put(key, page);
			}
			return page;
		});
	}

	/**
	 * A picture from the wiki as PNG bytes. The wiki sends small pictures as WebP, which the game cannot read, so the original
	 * file is asked for instead. Animated GIFs (enchanted items) become a PNG of their first frame. Completes exceptionally for
	 * anything else.
	 */
	public CompletableFuture<byte[]> image(String url) {
		return run(imageWorker, () -> {
			Path file = cacheFile("images", url, ".png");
			if (file != null && fresh(file)) {
				return Files.readAllBytes(file);
			}
			byte[] bytes = getBytes(originalImage(url));
			if (isGif(bytes)) {
				bytes = gifToPng(bytes);
			}
			if (!isPng(bytes)) {
				throw new IOException("not a PNG");
			}
			write(file, bytes);
			return bytes;
		});
	}

	/** Any text from any address (the Hypixel item list), kept on disk under {@code cacheName} for {@code maxAge}. */
	public CompletableFuture<String> text(String url, String cacheName, Duration maxAge) {
		return run(() -> {
			Path file = cacheDir == null ? null : cacheDir.resolve(cacheName);
			if (file != null && Files.exists(file) && clock.getAsLong() - Files.getLastModifiedTime(file).toMillis() < maxAge.toMillis()) {
				return Files.readString(file);
			}
			try {
				String body = get(url, "application/json");
				write(file, body.getBytes(StandardCharsets.UTF_8));
				return body;
			} catch (IOException e) {
				if (file != null && Files.exists(file)) {
					return Files.readString(file);            // an old copy beats nothing
				}
				throw e;
			}
		});
	}

	// ------------------------------------------------------------ parsing the answers

	static List<String> parseSearch(String body) throws IOException {
		try {
			JsonArray answer = JsonParser.parseString(body).getAsJsonArray();
			List<String> titles = new ArrayList<>();
			for (JsonElement title : answer.get(1).getAsJsonArray()) {
				titles.add(title.getAsString());
			}
			return List.copyOf(titles);
		} catch (RuntimeException e) {
			throw new IOException("bad search answer");
		}
	}

	static Page parsePage(String body, String asked) throws IOException {
		JsonObject answer;
		try {
			answer = JsonParser.parseString(body).getAsJsonObject();
		} catch (RuntimeException e) {
			throw new IOException("bad page answer");
		}
		if (answer.has("error")) {
			JsonObject error = answer.getAsJsonObject("error");
			String code = error.has("code") ? error.get("code").getAsString() : "";
			if (code.equals("missingtitle") || code.equals("invalidtitle")) {
				throw new NotFound(asked);
			}
			throw new IOException("wiki error " + code);
		}
		try {
			JsonObject parse = answer.getAsJsonObject("parse");
			return new Page(parse.get("title").getAsString(), parse.getAsJsonObject("text").get("*").getAsString());
		} catch (RuntimeException e) {
			throw new IOException("bad page answer");
		}
	}

	/** The full-size original of a wiki picture: no "/scale-to-width-down/N", and "format=original" so it stays a PNG. */
	static String originalImage(String url) {
		String plain = url.replaceAll("/scale-to-(width|height)-down/\\d+", "").replaceAll("/smart/width/\\d+/height/\\d+", "");
		return plain + (plain.contains("?") ? "&" : "?") + "format=original";
	}

	static boolean isGif(byte[] bytes) {
		return bytes.length > 6 && bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F';
	}

	/** The first frame of a GIF as a PNG. */
	static byte[] gifToPng(byte[] gif) throws IOException {
		try {
			java.awt.image.BufferedImage frame = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(gif));
			if (frame == null) {
				throw new IOException("unreadable GIF");
			}
			java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
			javax.imageio.ImageIO.write(frame, "png", png);
			return png.toByteArray();
		} catch (LinkageError e) {
			throw new IOException("no image support in this Java");      // a Java without java.desktop: do without
		}
	}

	static boolean isPng(byte[] bytes) {
		return bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
	}

	// ------------------------------------------------------------ requests

	private interface Job<T> {
		T run() throws IOException;
	}

	private <T> CompletableFuture<T> run(Job<T> job) {
		return run(worker, job);
	}

	private <T> CompletableFuture<T> run(Executor on, Job<T> job) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return job.run();
			} catch (IOException e) {
				throw new CompletionException(e);
			}
		}, on);
	}

	private String get(String url, String accept) throws IOException {
		return new String(send(url, accept), StandardCharsets.UTF_8);
	}

	private byte[] getBytes(String url) throws IOException {
		byte[] body = send(url, "image/png");
		if (body.length > MAX_IMAGE_BYTES) {
			throw new IOException("picture too big");
		}
		return body;
	}

	/** The answer's body, unzipped when it came gzipped (a page is about six times smaller that way). */
	private byte[] send(String url, String accept) throws IOException {
		if (url.contains("/api.php")) {
			waitTurn();                                 // the wiki's own API is spaced out; pictures come from its image server
		}
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(TIMEOUT)
				.header("User-Agent", userAgent)
				.header("Accept", accept)
				.header("Accept-Encoding", "gzip")
				.GET()
				.build();
		try {
			HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() != 200) {
				throw new IOException("HTTP " + response.statusCode());
			}
			boolean gzip = response.headers().firstValue("Content-Encoding").map(e -> e.equalsIgnoreCase("gzip")).orElse(false);
			if (!gzip) {
				return response.body();
			}
			try (java.util.zip.GZIPInputStream in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(response.body()))) {
				return in.readAllBytes();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted");
		} catch (IllegalArgumentException e) {
			throw new IOException("bad address");
		}
	}

	/** Waits so requests are at least {@link #MIN_GAP_MS} apart. Runs on the worker thread only. */
	private void waitTurn() throws IOException {
		long wait = lastRequest + MIN_GAP_MS - System.currentTimeMillis();
		if (wait > 0) {
			try {
				Thread.sleep(wait);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("interrupted");
			}
		}
		lastRequest = System.currentTimeMillis();
	}

	// ------------------------------------------------------------ the disk cache

	private Path cacheFile(String folder, String key, String extension) {
		if (cacheDir == null) {
			return null;
		}
		try {
			byte[] hash = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
			return cacheDir.resolve(folder).resolve(HexFormat.of().formatHex(hash) + extension);
		} catch (NoSuchAlgorithmException e) {
			return null;
		}
	}

	private boolean fresh(Path file) throws IOException {
		return Files.exists(file) && clock.getAsLong() - Files.getLastModifiedTime(file).toMillis() < maxAge.toMillis();
	}

	private Page readCachedPage(Path file) {
		try {
			if (file == null || !fresh(file)) {
				return null;
			}
			JsonObject saved = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
			return new Page(saved.get("title").getAsString(), saved.get("html").getAsString());
		} catch (IOException | RuntimeException e) {
			return null;                                    // a broken file is fetched again
		}
	}

	private void writeCachedPage(Path file, Page page) {
		JsonObject saved = new JsonObject();
		saved.addProperty("title", page.title());
		saved.addProperty("html", page.html());
		write(file, saved.toString().getBytes(StandardCharsets.UTF_8));
	}

	/** Writes through a temporary file. A failed write only means no cache. */
	private static void write(Path file, byte[] bytes) {
		if (file == null) {
			return;
		}
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.write(temp, bytes);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ignored) {
		}
	}

	private static String encode(String text) {
		return URLEncoder.encode(text, StandardCharsets.UTF_8);
	}

	private static <K, V> Map<K, V> lru(int size) {
		return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
				return size() > size;
			}
		});
	}
}
