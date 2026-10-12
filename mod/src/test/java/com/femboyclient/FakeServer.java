package com.femboyclient;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * A tiny real HTTP server for tests. Requests are recorded, and each path can be
 * given a handler that decides the reply. Paths with no handler answer 404, so a
 * test fails loudly if the code under test calls something unexpected.
 */
final class FakeServer implements AutoCloseable {

	record Request(String method, String path, String query, Map<String, String> headers, String body) {
		String header(String name) {
			return headers.get(name.toLowerCase(Locale.ROOT));
		}
	}

	/** @param headers extra response headers; @param bytes a raw body sent instead of the text one, or null */
	record Reply(int status, String body, Map<String, String> headers, byte[] bytes) {
		Reply(int status, String body) {
			this(status, body, Map.of(), null);
		}

		static Reply ok(String body) {
			return new Reply(200, body);
		}

		static Reply status(int status) {
			return new Reply(status, "{}");
		}

		/** A 200 with a binary body, like a file download. */
		static Reply bytes(byte[] data) {
			return new Reply(200, "", Map.of("Content-Type", "application/octet-stream"), data);
		}

		/** This reply with one more response header. */
		Reply withHeader(String name, String value) {
			Map<String, String> more = new HashMap<>(headers);
			more.put(name, value);
			return new Reply(status, body, Map.copyOf(more), bytes);
		}
	}

	private final HttpServer server;
	private final Map<String, Function<Request, Reply>> routes = new ConcurrentHashMap<>();
	private final List<Request> requests = new CopyOnWriteArrayList<>();

	FakeServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setExecutor(Executors.newCachedThreadPool());
		server.createContext("/", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

			Map<String, String> headers = new HashMap<>();
			exchange.getRequestHeaders().forEach((name, values) ->
					headers.put(name.toLowerCase(Locale.ROOT), values.get(0)));

			Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
					exchange.getRequestURI().getQuery(), headers, body);
			requests.add(request);

			Reply reply = routes.getOrDefault(request.path(), r -> Reply.status(404)).apply(request);
			byte[] bytes = reply.bytes() != null ? reply.bytes() : reply.body().getBytes(StandardCharsets.UTF_8);
			if (!reply.headers().containsKey("Content-Type")) {
				exchange.getResponseHeaders().add("Content-Type", "application/json");
			}
			reply.headers().forEach(exchange.getResponseHeaders()::add);
			boolean noBody = reply.status() == 304 || reply.status() == 204;
			exchange.sendResponseHeaders(reply.status(), noBody ? -1 : bytes.length);
			if (!noBody) {
				exchange.getResponseBody().write(bytes);
			}
			exchange.close();
		});
		server.start();
	}

	/** Sets how a path answers. May be changed between calls to simulate an outage. */
	void route(String path, Function<Request, Reply> handler) {
		routes.put(path, handler);
	}

	void route(String path, Reply fixed) {
		routes.put(path, request -> fixed);
	}

	String url() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	/** An address where nothing is listening, for "connection refused". */
	static String closedPortUrl() throws IOException {
		try (FakeServer temporary = new FakeServer()) {
			return temporary.url();
		}
	}

	/** Every request to one path, oldest first. */
	List<Request> to(String path) {
		return requests.stream().filter(r -> r.path().equals(path)).toList();
	}

	int count() {
		return requests.size();
	}

	@Override
	public void close() {
		server.stop(0);
	}
}
