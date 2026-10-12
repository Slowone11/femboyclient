package com.femboyclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Looped Clicker: not a one-shot click but a loop — once started, it repeatedly clicks one spot on screen, waiting
 * the set number of milliseconds between each one, down to {@link #MIN_MS}, until Start/Stop turns it off again.
 * The spot, the speed and left/right button are set from {@link LoopedClickerOverlay}, which also draws the
 * Start/Stop control. Switching the module on in the G menu only makes that control appear; the loop never starts
 * on its own. Closing the container does not stop it either: it only pauses while no container (your inventory, a
 * chest, anything else with slots) is open, and carries on as soon as one is.
 * <p>
 * A client tick is 50 ms, too slow for the fastest speeds, so a timer thread asks for a check every
 * {@link #PUMP_MS}. The clicks themselves still run on the game's own thread, at the start of the next frame, the
 * same place incoming server packets are handled.
 */
final class LoopedClicker {

	static final int MIN_MS = 10, MAX_MS = 5000;

	private static final long PUMP_MS = 2;

	/** Most clicks fired in one go to catch up after a slow frame; anything more behind than that is skipped, not burst. */
	private static final int MAX_CATCH_UP = 5;

	private static volatile boolean running;
	private static long nextClickAt;      // System.nanoTime() the next click is due; 0 means click right away
	private static boolean firingSynthetic;
	private static int clicks;

	private static final AtomicBoolean pumpQueued = new AtomicBoolean();
	private static ScheduledExecutorService timer;

	private LoopedClicker() {
	}

	static boolean running() {
		return running;
	}

	/** How many clicks this run of the loop has fired, for the panel to show. Resets on every {@link #start}. */
	static int clicks() {
		return clicks;
	}

	/**
	 * True only for the instant a synthetic click is in flight. Fabric mixes {@code allowMouseClick}/
	 * {@code allowMouseRelease} straight into {@link Screen#mouseClicked}/{@code mouseReleased} themselves, so
	 * this class's own synthetic clicks reach {@link LoopedClickerOverlay}'s listeners too, same as a real one
	 * would. Without this flag, a synthetic click that happens to land on the marker looks exactly like the
	 * player grabbing it to drag it — {@link LoopedClickerOverlay} would "catch" every synthetic click as a
	 * drag-start and cancel it before the container ever saw it, which is why nothing but the manual Start click
	 * ever landed.
	 */
	static boolean firingSynthetic() {
		return firingSynthetic;
	}

	static void start() {
		running = true;
		nextClickAt = 0;
		clicks = 0;
		startTimer();
	}

	static void stop() {
		running = false;
	}

	static void toggle() {
		if (running) {
			stop();
		} else {
			start();
		}
	}

	/** Called every client tick. The timer does the real pacing; this is only a fallback if it ever falls behind. */
	static void tick(Minecraft mc) {
		pump(mc);
	}

	private static synchronized void startTimer() {
		if (timer != null) {
			return;
		}
		timer = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "Femboy Client looped clicker");
			t.setDaemon(true);
			return t;
		});
		Minecraft mc = Minecraft.getInstance();
		timer.scheduleAtFixedRate(() -> {
			// One queued check at a time: a slow frame must not pile up hundreds of them.
			if (running && pumpQueued.compareAndSet(false, true)) {
				mc.execute(() -> {
					pumpQueued.set(false);
					pump(mc);
				});
			}
		}, PUMP_MS, PUMP_MS, TimeUnit.MILLISECONDS);
	}

	/** Fires every click that is due. Runs on the game's thread only. */
	private static void pump(Minecraft mc) {
		if (!running) {
			return;
		}
		FemboyClientConfig config = FemboyClientConfig.get();
		if (!config.enabled || !config.clicker) {
			stop();
			return;
		}
		Screen screen = McCompat.screen(mc);
		if (!(screen instanceof AbstractContainerScreen<?>)) {
			nextClickAt = 0;      // paused; click right away once a container is open again
			return;
		}
		long now = System.nanoTime();
		long interval = config.clickerMs * 1_000_000L;
		if (nextClickAt == 0 || now - nextClickAt > interval * MAX_CATCH_UP) {
			nextClickAt = now;
		}
		for (int fired = 0; fired < MAX_CATCH_UP && now >= nextClickAt; fired++) {
			clicks++;
			click(screen, config);
			nextClickAt += interval;
			if (McCompat.screen(mc) != screen) {
				break;      // the click opened or refreshed a menu; the next check clicks on the new one
			}
		}
	}

	/**
	 * Fires one press-and-release at the configured spot, through the screen's own click handling — the same code
	 * path a real click takes, so it opens, moves and picks up items exactly like your mouse would. Called again
	 * and again by {@link #pump}, {@code clickerMs} apart, for as long as the loop keeps running.
	 */
	private static void click(Screen screen, FemboyClientConfig config) {
		// A spot saved at a smaller GUI scale can lie past the screen's edge now; click at the edge instead of nowhere.
		double x = config.clickerX < 0 ? screen.width / 2.0 : Math.min(config.clickerX, screen.width - 1);
		double y = config.clickerY < 0 ? screen.height / 2.0 : Math.min(config.clickerY, screen.height - 1);
		MouseButtonEvent event = new MouseButtonEvent(x, y, new MouseButtonInfo(config.clickerRightClick ? 1 : 0, 0));
		firingSynthetic = true;
		try {
			screen.mouseClicked(event, false);
			screen.mouseReleased(event);
		} finally {
			firingSynthetic = false;
		}
	}
}
