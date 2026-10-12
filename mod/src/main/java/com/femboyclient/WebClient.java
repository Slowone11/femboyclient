package com.femboyclient;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * A thin wrapper around java.net.http.HttpClient for talking to the website.
 *
 * Every call returns a future that always completes normally: a failure to
 * connect becomes a Response with status -1 and an explanation, rather than an
 * exception, so callers have one path to handle.
 *
 * Kept free of Minecraft classes so it can be tested against a local server.
 */
public final class WebClient {

	/**
	 * The outcome of one request.
	 *
	 * @param status HTTP status, or -1 if the server could not be reached at all
	 * @param body   the response body, empty if there was none
	 * @param error  why the request failed to complete, or null if it did complete
	 */
	public record Response(int status, String body, String error) {

		/** True for any 2xx answer. */
		public boolean ok() {
			return status >= 200 && status < 300;
		}

		/** True if the server answered at all, even with an error. */
		public boolean reachable() {
			return status >= 0;
		}

		/** A short plain-English reason, fit to show the player. */
		public String describe() {
			return reachable() ? "HTTP " + status : error;
		}
	}

	private final HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(5))
			.build();

	private final Supplier<java.util.List<String>> baseUrls;

	/** The address that answered last: tried first, so once the website is found nothing waits on a wrong address. */
	private volatile String working;

	/** @param baseUrl read on every request, so a changed config takes effect at once */
	public WebClient(Supplier<String> baseUrl) {
		this.baseUrls = () -> java.util.List.of(baseUrl.get());
	}

	private WebClient(java.util.List<String> unused, Supplier<java.util.List<String>> baseUrls) {
		this.baseUrls = baseUrls;
	}

	/**
	 * A client that knows several addresses for the website. A request that cannot connect at one goes on to the next, and the first one
	 * that answers is used from then on (until it stops answering).
	 *
	 * @param baseUrls read on every request, best guess first
	 */
	public static WebClient withFallbacks(Supplier<java.util.List<String>> baseUrls) {
		return new WebClient(java.util.List.of(), baseUrls);
	}

	/** The address the website is at, as far as is known: the one that answered last, else the first to try. For showing in chat. */
	public String currentUrl() {
		java.util.List<String> all = baseUrls.get();
		String now = working;
		if (now != null && all.contains(now)) {
			return now;
		}
		return all.isEmpty() ? "" : all.get(0);
	}

	/** The addresses to try, in order: the one that answered last first. */
	private java.util.List<String> order() {
		java.util.List<String> all = new java.util.ArrayList<>();
		String now = working;
		java.util.List<String> given = baseUrls.get();
		if (now != null && given.contains(now)) {
			all.add(now);
		}
		for (String url : given) {
			if (!all.contains(url)) {
				all.add(url);
			}
		}
		return all;
	}

	public CompletableFuture<Response> get(String path, Duration timeout, Map<String, String> headers) {
		return request(path, timeout, headers, HttpRequest.Builder::GET);
	}

	public CompletableFuture<Response> post(String path, String json, Duration timeout, Map<String, String> headers) {
		return request(path, timeout, headers, builder -> builder
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)));
	}

	public CompletableFuture<Response> delete(String path, Duration timeout, Map<String, String> headers) {
		return request(path, timeout, headers, HttpRequest.Builder::DELETE);
	}

	/** Posts raw bytes, for example a JPEG. */
	public CompletableFuture<Response> postBytes(String path, byte[] body, String contentType, Duration timeout, Map<String, String> headers) {
		return request(path, timeout, headers, builder -> builder
				.header("Content-Type", contentType)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body)));
	}

	private CompletableFuture<Response> request(String path, Duration timeout, Map<String, String> headers,
			UnaryOperator<HttpRequest.Builder> method) {
		java.util.List<String> candidates = order();
		if (candidates.isEmpty()) {
			return CompletableFuture.completedFuture(new Response(-1, "", "no server address"));
		}
		return attempt(candidates, 0, path, timeout, headers, method, null);
	}

	/** Tries the address at {@code index}; when it cannot be reached, the next one. Any answer, even an error, ends the search. */
	private CompletableFuture<Response> attempt(java.util.List<String> candidates, int index, String path, Duration timeout,
			Map<String, String> headers, UnaryOperator<HttpRequest.Builder> method, Response firstFailure) {
		String base = candidates.get(index).trim().replaceAll("/+$", "");
		HttpRequest request;
		try {
			// Everything that can reject a bad serverUrl or header is inside this
			// block, so a typo in the config becomes a normal failed Response.
			HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(base + path)).timeout(timeout);
			headers.forEach(builder::header);
			request = method.apply(builder).build();
		} catch (IllegalArgumentException e) {
			Response bad = new Response(-1, "", "bad server address or header (" + e.getMessage() + ")");
			return next(candidates, index, path, timeout, headers, method, firstFailure == null ? bad : firstFailure, bad);
		}

		return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.handle((response, failure) -> failure == null
						? new Response(response.statusCode(), response.body(), null)
						: new Response(-1, "", describe(failure)))
				.thenCompose(result -> {
					if (result.reachable()) {
						working = candidates.get(index);           // this address answered: use it first from now on
						return CompletableFuture.completedFuture(result);
					}
					return next(candidates, index, path, timeout, headers, method, firstFailure == null ? result : firstFailure, result);
				});
	}

	private CompletableFuture<Response> next(java.util.List<String> candidates, int index, String path, Duration timeout,
			Map<String, String> headers, UnaryOperator<HttpRequest.Builder> method, Response firstFailure, Response last) {
		if (index + 1 < candidates.size()) {
			return attempt(candidates, index + 1, path, timeout, headers, method, firstFailure);
		}
		return CompletableFuture.completedFuture(firstFailure);         // none answered: the reason for the address tried first
	}

	/** Turns a connection failure into something readable in chat. */
	static String describe(Throwable failure) {
		Throwable cause = failure;
		while (cause instanceof CompletionException && cause.getCause() != null) {
			cause = cause.getCause();
		}

		if (cause instanceof HttpTimeoutException) {
			return "timed out";
		}
		if (cause instanceof ConnectException) {
			return "connection refused";
		}
		if (cause instanceof UnresolvedAddressException) {
			return "unknown host";
		}
		String message = cause.getMessage();
		return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
	}
}
