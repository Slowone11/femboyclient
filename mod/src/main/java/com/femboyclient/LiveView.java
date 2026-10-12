package com.femboyclient;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The live view: sends a small picture of the game screen to the website, which shows it on its Live tab (only on the PC the
 * website runs on). Always on, silently: it never shows a chat message and has no setting to turn it off.
 *
 * The game thread only asks the game for its picture and copies the pixels. Shrinking, encoding and sending happen on one background
 * thread, and a new picture is not asked for until the last one is sent, so a slow website never slows the game.
 *
 * The website says with each answer whether its page is being watched. While it is, a picture goes every 350 ms; otherwise one every
 * 5 seconds, so the page has one ready the moment it is opened.
 */
public final class LiveView {

	static final int MAX_WIDTH = 640;
	static final float QUALITY = 0.6f;
	static final long WATCHED_EVERY_MS = 350;
	static final long IDLE_EVERY_MS = 5_000;
	static final long KEEPALIVE_MS = 2_000;                // an unchanged screen is sent again after this long, so the page can see it is live
	private static final long STUCK_AFTER_MS = 10_000;     // a picture that never arrived does not block the next one for ever
	private static final int GIVE_UP_AFTER = 5;            // failures of the game's own picture call in a row

	/** Sends one JPEG. Returns 1 if the page is watching, 0 if not, -1 if the website did not take it. */
	public interface Uploader {
		/** @param state what the game is doing, as JSON: held slot and item, menu open, position, whether it takes input */
		int send(byte[] jpeg, String state);
	}

	private final Supplier<FemboyClientConfig> config;
	private final BooleanSupplier linked;
	private final Uploader uploader;
	private final Supplier<String> inputSource;
	private ScheduledExecutorService inputPoller;
	static final long INPUT_EVERY_MS = 150;
	private final ExecutorService worker = Daemons.worker("liveview");

	private final AtomicBoolean inFlight = new AtomicBoolean();
	private volatile long startedMs;
	private volatile boolean watched;
	private volatile String pendingState = "{}";
	private int lastHash;
	private long lastSentMs;
	private String lastState = "";
	private long lastMs;
	private int failures;
	private boolean warned;

	public LiveView(Supplier<FemboyClientConfig> config, BooleanSupplier linked, Uploader uploader) {
		this(config, linked, uploader, () -> null);
	}

	/** @param inputSource fetches what the Live tab sent to this game (the website's JSON), or null; blocks, so not on the game thread */
	public LiveView(Supplier<FemboyClientConfig> config, BooleanSupplier linked, Uploader uploader, Supplier<String> inputSource) {
		this.config = config;
		this.linked = linked;
		this.uploader = uploader;
		this.inputSource = inputSource;
	}

	/**
	 * Starts asking for input from the Live tab, every 150 ms, but only while the page is being watched and the player has switched
	 * input on. Everything else about the live view works without this.
	 */
	public void startInput() {
		inputPoller = Daemons.scheduler("liveinput");
		inputPoller.scheduleWithFixedDelay(() -> pollInput(events -> {
			Minecraft mc = Minecraft.getInstance();
			mc.execute(() -> events.forEach(event -> LiveInput.apply(mc, event)));
		}), 2_000, INPUT_EVERY_MS, TimeUnit.MILLISECONDS);
	}

	/** One round of asking. The events go to {@code run}. Package-private so a test can run it. Never throws. */
	void pollInput(Consumer<List<LiveInput.Event>> run) {
		try {
			FemboyClientConfig cfg = config.get();
			if (!cfg.enabled || !cfg.liveInput || !watched || !linked.getAsBoolean()) {
				return;
			}
			String answer = inputSource.get();
			if (answer == null) {
				return;
			}
			Boolean watching = LiveInput.watching(answer);
			if (watching != null) {
				watched = watching;              // the page closed: back to the slow, cheap rhythm at once
			}
			List<LiveInput.Event> events = LiveInput.parse(answer);
			if (!events.isEmpty()) {
				run.accept(events);
			}
		} catch (RuntimeException e) {
			FemboyClientMod.LOGGER.debug("live input poll failed", e);
		}
	}

	/** True when the website's page is watching, as far as the last answer said. */
	public boolean watched() {
		return watched;
	}

	/** Whether the next picture is due, at the given time. */
	boolean due(long nowMs) {
		return nowMs - lastMs >= (watched ? WATCHED_EVERY_MS : IDLE_EVERY_MS);
	}

	/** Called every client tick on the game thread. Never throws: the live view must not be able to disturb the game. */
	public void tick(Minecraft mc) {
		try {
			FemboyClientConfig cfg = config.get();
			if (!cfg.enabled || !linked.getAsBoolean() || failures >= GIVE_UP_AFTER) {
				return;
			}
			long now = System.currentTimeMillis();
			if (inFlight.get()) {
				if (now - startedMs < STUCK_AFTER_MS) {
					return;
				}
				inFlight.set(false);
			}
			if (!due(now) || mc.getWindow() == null) {
				return;
			}
			com.mojang.blaze3d.pipeline.RenderTarget target = McCompat.mainRenderTarget(mc);
			if (target == null) {
				failures = GIVE_UP_AFTER;
				FemboyClientMod.LOGGER.warn("live view: this game version's screen target was not found, live view is off");
				return;
			}
			lastMs = now;
			startedMs = now;
			pendingState = stateJson(mc, cfg);
			inFlight.set(true);
			Screenshot.takeScreenshot(target, this::received);
			failures = 0;
		} catch (RuntimeException | LinkageError e) {
			inFlight.set(false);
			if (++failures >= GIVE_UP_AFTER && !warned) {
				warned = true;
				FemboyClientMod.LOGGER.warn("live view: the game's screen picture failed {} times, giving up", failures, e);
			}
		}
	}

	/** The game's answer: its picture. Copies the pixels and lets go of it at once, then hands the rest to the background thread. */
	private void received(NativeImage image) {
		int width, height;
		int[] pixels;
		try {
			width = image.getWidth();
			height = image.getHeight();
			pixels = image.getPixels();
		} catch (RuntimeException e) {
			inFlight.set(false);
			return;
		} finally {
			image.close();
		}
		String state = pendingState;
		worker.execute(() -> process(pixels, width, height, state));
	}

	/** What the game is doing right now, read on the game thread. */
	private static String stateJson(Minecraft mc, FemboyClientConfig cfg) {
		com.google.gson.JsonObject state = new com.google.gson.JsonObject();
		state.addProperty("input", cfg.liveInput);
		if (mc.player != null) {
			var inventory = mc.player.getInventory();
			state.addProperty("slot", inventory.getSelectedSlot());
			var held = inventory.getSelectedItem();
			state.addProperty("held", held.isEmpty() ? "" : held.getHoverName().getString());
			state.addProperty("menu", McCompat.screen(mc) != null);
			com.google.gson.JsonArray at = new com.google.gson.JsonArray();
			at.add(Math.round(mc.player.getX()));
			at.add(Math.round(mc.player.getY()));
			at.add(Math.round(mc.player.getZ()));
			state.add("pos", at);
		}
		return state.toString();
	}

	/** Shrinks, encodes and sends one picture. On the background thread. Package-private so a test can run it. */
	void process(int[] pixels, int width, int height) {
		process(pixels, width, height, "{}");
	}

	/**
	 * While the page is watching, a screen that has not changed since the last picture is not sent again for two seconds: nothing
	 * is encoded and nothing goes over the network, which is most of the time when standing still or in a menu.
	 */
	void process(int[] pixels, int width, int height, String state) {
		try {
			int hash = Arrays.hashCode(pixels);
			long now = System.currentTimeMillis();
			if (watched && hash == lastHash && Objects.equals(state, lastState) && now - lastSentMs < KEEPALIVE_MS) {
				return;
			}
			byte[] jpeg = LiveFrames.encode(pixels, width, height, MAX_WIDTH, QUALITY);
			int answer = uploader.send(jpeg, state);
			if (answer >= 0) {
				watched = answer > 0;
				lastHash = hash;
				lastState = state;
				lastSentMs = now;
			}
		} catch (Exception | LinkageError e) {
			FemboyClientMod.LOGGER.debug("live view: picture not sent", e);
		} finally {
			inFlight.set(false);
		}
	}

	/** Stops the background thread. */
	public void stop() {
		worker.shutdownNow();
		if (inputPoller != null) {
			inputPoller.shutdownNow();
		}
	}
}
