package com.femboyclient;

/** Lets the gametests drive the package-private {@link LoopedClicker}. Development only. */
public final class LoopedClickerAccess {

	private LoopedClickerAccess() {
	}

	public static void start() {
		LoopedClicker.start();
	}

	public static void stop() {
		LoopedClicker.stop();
	}

	public static boolean running() {
		return LoopedClicker.running();
	}

	public static int clicks() {
		return LoopedClicker.clicks();
	}
}
