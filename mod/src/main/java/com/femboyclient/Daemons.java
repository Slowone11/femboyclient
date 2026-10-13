package com.femboyclient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

/** Background threads that never keep the game process alive on quit. */
final class Daemons {

	private Daemons() {
	}

	private static ThreadFactory named(String name) {
		return runnable -> {
			Thread thread = new Thread(runnable, "femboyclient-" + name);
			thread.setDaemon(true);
			return thread;
		};
	}

	/** One daemon thread that runs scheduled work, called {@code femboyclient-<name>}. */
	static ScheduledExecutorService scheduler(String name) {
		return Executors.newSingleThreadScheduledExecutor(named(name));
	}

	/** {@code threads} daemon threads that share queued work, each called {@code femboyclient-<name>}. */
	static ExecutorService pool(String name, int threads) {
		return Executors.newFixedThreadPool(threads, named(name));
	}

	/** One daemon thread that runs queued work, called {@code femboyclient-<name>}. */
	static ExecutorService worker(String name) {
		return Executors.newSingleThreadExecutor(named(name));
	}
}
