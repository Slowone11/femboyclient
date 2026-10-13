package com.femboyclient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The applied proxy and whether its last test worked, which the Proxy button on the server list shows ("Connected" in
 * green). Tests run one at a time on a background thread. A result for settings that are no longer the applied ones only
 * goes back to whoever asked for it, so it never changes the button.
 */
final class ProxyState {

	/** Where the test tunnels to: the server this mod is made for. */
	static final String TEST_HOST = "mc.hypixel.net";
	static final int TEST_PORT = 25565;
	private static final int TIMEOUT_MS = 8000;

	/** An old result is tested again when the server list opens after this long. */
	private static final long FRESH_MS = 60_000;

	enum Status { OFF, UNTESTED, TESTING, CONNECTED, FAILED }

	private static final ExecutorService WORKER = Daemons.worker("proxy");
	private static final AtomicInteger RUN = new AtomicInteger();

	private static volatile Status status = Status.UNTESTED;
	private static volatile String message = "";
	private static volatile long testedAt;

	private ProxyState() {
	}

	/** The proxy every server connection goes through, or null to connect directly. */
	static ProxySettings active() {
		FemboyClientConfig config = FemboyClientConfig.get();
		if (!config.proxyEnabled) {
			return null;
		}
		ProxySettings settings = config.proxy();
		return settings.problem() == null ? settings : null;
	}

	static Status status() {
		FemboyClientConfig config = FemboyClientConfig.get();
		if (!config.proxyEnabled) {
			return Status.OFF;
		}
		return config.proxy().problem() != null ? Status.FAILED : status;
	}

	/** The last test's ping or error, or why the applied settings cannot work. */
	static String message() {
		FemboyClientConfig config = FemboyClientConfig.get();
		if (!config.proxyEnabled) {
			return "Off: servers are joined directly";
		}
		String problem = config.proxy().problem();
		return problem != null ? problem : message;
	}

	/** Called after Apply: the applied proxy is new, so the old result no longer counts. */
	static void applied() {
		RUN.incrementAndGet();
		status = Status.UNTESTED;
		message = "";
		refresh(true);
	}

	/** Tests the applied proxy again, unless a test is running or the last result is recent (when not forced). */
	static void refresh(boolean force) {
		ProxySettings settings = active();
		if (settings == null || status == Status.TESTING
				|| (!force && status != Status.UNTESTED && System.currentTimeMillis() - testedAt < FRESH_MS)) {
			return;
		}
		test(settings, result -> {
		});
	}

	/** Tests the given settings in the background. done runs on the background thread. */
	static void test(ProxySettings settings, Consumer<ProxyProbe.Result> done) {
		boolean isApplied = settings.equals(active());
		int run = isApplied ? RUN.incrementAndGet() : -1;
		if (isApplied) {
			status = Status.TESTING;
			message = "Testing...";
		}
		WORKER.execute(() -> {
			ProxyProbe.Result result = ProxyProbe.test(settings, TEST_HOST, TEST_PORT, TIMEOUT_MS);
			FemboyClientMod.LOGGER.info("proxy: test through {} -> {}", settings, result.message());
			if (run == RUN.get() && settings.equals(active())) {
				status = result.ok() ? Status.CONNECTED : Status.FAILED;
				message = result.message();
				testedAt = System.currentTimeMillis();
			}
			done.accept(result);
		});
	}
}
