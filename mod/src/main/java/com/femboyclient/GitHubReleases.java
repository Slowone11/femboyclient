package com.femboyclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The mod's updates from GitHub: the newest release of one repository, read with a fine-grained token that may only read that
 * repository's contents (the repository is private). The release holds the jar ("femboyclient-&lt;version&gt;.jar"). Its SHA-256 is
 * the digest GitHub keeps for every asset, or else the one in a "&lt;jar&gt;.sha256" asset beside it.
 *
 * A check that finds the same release as last time is nearly free: the request carries the ETag of the last answer and GitHub answers
 * 304 with no body. A download goes through GitHub's asset API, which redirects to signed storage; that address is fetched without the
 * token, which never leaves the API.
 *
 * Kept free of Minecraft classes so it can be tested against a local server.
 */
public final class GitHubReleases implements ModUpdater.Source {

	/** GitHub's REST API. Tests pass the address of a local server instead. */
	public static final String API = "https://api.github.com";

	/** Far above any real jar. A bigger download is refused rather than kept in memory. */
	static final int MAX_JAR_BYTES = 50 * 1024 * 1024;

	private static final int MAX_CHECKSUM_BYTES = 4096;
	private static final Duration TIMEOUT = Duration.ofSeconds(20);
	private static final Duration DOWNLOAD_TIMEOUT = Duration.ofMinutes(3);
	private static final String USER_AGENT = "femboyclient-updater";
	private static final Pattern JAR = Pattern.compile("femboyclient-.*\\.jar");
	private static final Pattern NOT_THE_MOD = Pattern.compile("sources|dev|javadoc");      // the same jars the website skips
	private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
	private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("femboyclient");

	/** The jar in the newest release: its asset id (for the download), its name and its SHA-256. */
	record Asset(long id, String name, String sha) {
	}

	private final HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NEVER)          // followed by hand, so the token is not sent on to the storage
			.build();

	private final String api;
	private final String repo;
	private final String token;
	private final Consumer<String> notice;

	private String etag;
	private Asset latest;
	private int lastLoggedStatus;
	private boolean refusalShown;

	/**
	 * @param api    {@link #API}, or a local server in tests
	 * @param repo   "owner/name"
	 * @param token  a token that can read that repository's contents
	 * @param notice shown once if GitHub refuses the token, which is what happens when it has expired
	 */
	public GitHubReleases(String api, String repo, String token, Consumer<String> notice) {
		this.api = api.trim().replaceAll("/+$", "");
		this.repo = repo.trim();
		this.token = token.trim();
		this.notice = notice;
	}

	@Override
	public String latestSha() throws IOException {
		HttpRequest.Builder builder = apiRequest("/repos/" + repo + "/releases/latest", "application/vnd.github+json", TIMEOUT);
		if (etag != null && latest != null) {
			builder.header("If-None-Match", etag);
		}
		HttpResponse<String> response = send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response == null) {
			return null;
		}
		int status = response.statusCode();
		if (status == 304) {
			return latest == null ? null : latest.sha();
		}
		if (status == 401) {
			refused();
			return null;
		}
		if (status != 200) {
			logStatusOnce(status);
			return null;
		}
		lastLoggedStatus = 0;
		Asset found = jarAsset(JsonParser.parseString(response.body()).getAsJsonObject());
		latest = found;
		etag = found == null ? null : response.headers().firstValue("ETag").orElse(null);
		return found == null ? null : found.sha();
	}

	@Override
	public byte[] download() throws IOException {
		Asset asset = latest;
		return asset == null ? null : downloadAsset(asset.id(), MAX_JAR_BYTES);
	}

	/** What the last check found, for tests. */
	Asset latest() {
		return latest;
	}

	/** The mod jar among a release's assets, with its SHA-256. Null when the release has none, or nothing to check it against. */
	private Asset jarAsset(JsonObject release) throws IOException {
		JsonArray assets = release.has("assets") && release.get("assets").isJsonArray() ? release.getAsJsonArray("assets") : new JsonArray();
		JsonObject jar = null;
		for (JsonElement element : assets) {
			JsonObject asset = element.getAsJsonObject();
			String name = string(asset, "name");
			if (JAR.matcher(name).matches() && !NOT_THE_MOD.matcher(name).find()) {
				jar = asset;
				break;
			}
		}
		if (jar == null) {
			LOGGER.debug("update check: the latest release of {} has no mod jar", repo);
			return null;
		}
		String name = string(jar, "name");
		String sha = shaFromDigest(string(jar, "digest"));
		if (sha == null) {
			sha = shaFromChecksumAsset(assets, name + ".sha256");
		}
		if (sha == null) {
			LOGGER.warn("update: the latest release of {} gives no SHA-256 for {}, not using it", repo, name);
			return null;
		}
		return new Asset(jar.get("id").getAsLong(), name, sha);
	}

	/** GitHub's own digest of an asset, "sha256:&lt;hex&gt;". */
	private static String shaFromDigest(String digest) {
		if (!digest.startsWith("sha256:")) {
			return null;
		}
		String hex = digest.substring("sha256:".length()).toLowerCase(Locale.ROOT);
		return SHA256.matcher(hex).matches() ? hex : null;
	}

	/** The SHA-256 in a checksum asset (the first word of its text, as sha256sum writes it), for a release without digests. */
	private String shaFromChecksumAsset(JsonArray assets, String checksumName) throws IOException {
		for (JsonElement element : assets) {
			JsonObject asset = element.getAsJsonObject();
			if (string(asset, "name").equals(checksumName)) {
				byte[] text = downloadAsset(asset.get("id").getAsLong(), MAX_CHECKSUM_BYTES);
				if (text == null) {
					return null;
				}
				String[] words = new String(text, StandardCharsets.US_ASCII).trim().split("\\s+");
				String hex = words[0].toLowerCase(Locale.ROOT);
				return SHA256.matcher(hex).matches() ? hex : null;
			}
		}
		return null;
	}

	/** One asset's bytes. The asset API answers with a redirect to signed storage, which is fetched without the token. */
	private byte[] downloadAsset(long id, int maxBytes) throws IOException {
		HttpRequest request = apiRequest("/repos/" + repo + "/releases/assets/" + id, "application/octet-stream", DOWNLOAD_TIMEOUT).build();
		HttpResponse<InputStream> response = send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response == null) {
			return null;
		}
		if (isRedirect(response.statusCode())) {
			response.body().close();
			String location = response.headers().firstValue("Location").orElse(null);
			if (location == null) {
				return null;
			}
			HttpRequest storage = HttpRequest.newBuilder(request.uri().resolve(location))
					.timeout(DOWNLOAD_TIMEOUT)
					.header("Accept", "application/octet-stream")
					.header("User-Agent", USER_AGENT)
					.GET()
					.build();
			response = send(storage, HttpResponse.BodyHandlers.ofInputStream());
			if (response == null) {
				return null;
			}
		}
		try (InputStream body = response.body()) {
			int status = response.statusCode();
			if (status == 401) {
				refused();
				return null;
			}
			if (status != 200) {
				LOGGER.debug("update: downloading asset {} of {} failed with HTTP {}", id, repo, status);
				return null;
			}
			byte[] data = body.readNBytes(maxBytes + 1);
			if (data.length > maxBytes) {
				LOGGER.warn("update: asset {} of {} is bigger than {} bytes, not using it", id, repo, maxBytes);
				return null;
			}
			return data;
		}
	}

	private static boolean isRedirect(int status) {
		return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
	}

	private HttpRequest.Builder apiRequest(String path, String accept, Duration timeout) {
		return HttpRequest.newBuilder(URI.create(api + path))
				.timeout(timeout)
				.header("Accept", accept)
				.header("Authorization", "Bearer " + token)
				.header("X-GitHub-Api-Version", "2022-11-28")
				.header("User-Agent", USER_AGENT)
				.GET();
	}

	/** Sends a request. Null only when this thread is interrupted; a failure to connect throws, as for any IOException. */
	private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
		try {
			return http.send(request, handler);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	/** GitHub refused the token. Usually it has expired; only a jar built with a new one can update again. */
	private void refused() {
		if (!refusalShown) {
			refusalShown = true;
			LOGGER.warn("update: GitHub refused the update token for {} (HTTP 401), it may have expired", repo);
			notice.accept("[Update] GitHub refused this jar's update token (it may have expired), so updates are paused. A newer jar fixes it.");
		}
	}

	/** Any other refusal, logged once per status so a check every minute does not fill the log. */
	private void logStatusOnce(int status) {
		if (status != lastLoggedStatus) {
			lastLoggedStatus = status;
			LOGGER.info("update check: GitHub answered HTTP {} for the latest release of {} (404: no release yet, or the token cannot "
					+ "read that repository)", status, repo);
		}
	}

	private static String string(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value == null || value.isJsonNull() ? "" : value.getAsString();
	}
}
