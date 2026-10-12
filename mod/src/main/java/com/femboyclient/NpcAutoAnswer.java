package com.femboyclient;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * "Auto Conversation": clicks the right option of a Hypixel NPC dialog for you, in the background. A dialog ends in a chat
 * line like {@code Select an option: [Yes] [No]}, where every bracketed word is really a piece of chat text with its own
 * click event and color attached; clicking one sends whatever that click event says to the server exactly like a real
 * click would, and it needs no chat screen or mouse click to do that (see {@link #register}).
 *
 * Two kinds of click event show up in practice: a plain command ({@code RunCommand}/{@code SuggestCommand}, sent as chat
 * or a command), and a structured one ({@code Custom}, an id plus an optional NBT payload — this is what Hypixel's own
 * dialog options actually use) sent back as the same {@code ServerboundCustomClickActionPacket} a real click would send,
 * built the same way {@code Screen.defaultHandleGameClickEvent} builds it (see {@link NpcAutoGame}).
 *
 * With one option offered there is nothing to decide, so it is always sent. With two or more, Hypixel colors the option
 * that continues the dialog green, so that one is sent; if none of them is green, nothing is sent (see {@link NpcDialog}).
 *
 * The decision itself lives in {@link NpcDialog} and is Minecraft-free; this class only reads the game's chat, walks a
 * message's styled runs to find the real click events and colors, and does the sending, all on the client thread so it
 * never conflicts with the player's own movement or typing.
 */
public final class NpcAutoAnswer {

	/** What a click event actually does: run/suggest a command, or send back a structured custom click. */
	sealed interface Action {
		record Command(String value) implements Action {
		}

		record Custom(Identifier id, Optional<Tag> payload) implements Action {
		}
	}

	/** An offered option: what it says, whether it is colored green, and what its click event actually does. */
	record Option(String label, boolean green, Action action) {
	}

	/** What this needs from the real game: sending, and a local chat hint. Kept small so tests can fake it. */
	public interface Env {
		/** Types a command, without the leading slash. */
		void sendCommand(String command);

		/** Sends plain chat text, exactly as if typed and enter was pressed. */
		void sendChat(String text);

		/** Sends a structured "custom" click back to the server, exactly as a real click on it would. */
		void sendCustomClick(Identifier id, Optional<Tag> payload);

		/** Prints a line in your own chat. */
		void say(String text);

		long nanos();
	}

	static final long DELAY_MIN_NANOS = 250_000_000L;
	static final long DELAY_MAX_NANOS = 600_000_000L;

	private record Pending(Action action, long fireAt) {
	}

	private final Env env;
	private final BooleanSupplier enabled;
	private final java.util.random.RandomGenerator random;

	private Pending pending;

	public NpcAutoAnswer(Env env, BooleanSupplier enabled) {
		this(env, enabled, new java.util.Random());
	}

	/** @param random only for tests: makes the delay before sending deterministic */
	NpcAutoAnswer(Env env, BooleanSupplier enabled, java.util.random.RandomGenerator random) {
		this.env = env;
		this.enabled = enabled;
		this.random = random;
	}

	// ---------------------------------------------------------------- the real game

	/** Hooks up the chat events. Call once during client init. */
	public void register() {
		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			if (!overlay) {
				handle(message);
			}
		});
	}

	/** Called every client tick, to send a decision once its short delay has passed. Never throws. */
	public void tick(long now) {
		try {
			if (pending != null && now >= pending.fireAt()) {
				Action action = pending.action();
				pending = null;
				if (enabled.getAsBoolean()) {
					send(action);
				}
			}
		} catch (RuntimeException e) {
			pending = null;
		}
	}

	private void handle(Component message) {
		if (Chat.isSendingOwn() || !enabled.getAsBoolean()) {
			return;
		}
		String plain = ScoreboardReader.strip(message.getString());
		if (NpcDialog.isDialogLine(plain)) {
			List<Option> found = options(message);
			// What was found on a line that looked like a dialog, so a miss can be diagnosed from the log. Debug level:
			// nothing is built unless debug logging is on.
			if (FemboyClientMod.LOGGER.isDebugEnabled()) {
				if (found.isEmpty()) {
					List<String> runs = new ArrayList<>();
					message.visit((style, text) -> {
						ClickEvent ce = style.getClickEvent();
						runs.add("\"" + text + "\" color=" + style.getColor() + " click="
								+ (ce == null ? "none" : ce.action() + " " + ce));
						return Optional.empty();
					}, message.getStyle());
					FemboyClientMod.LOGGER.debug("[Auto Conversation] dialog line \"{}\" -> 0 options; every styled run: {}", plain, runs);
				} else {
					FemboyClientMod.LOGGER.debug("[Auto Conversation] dialog line \"{}\" -> {} clickable option(s): {}",
							plain, found.size(), found);
				}
			}
			onOptions(found, env.nanos());
		}
	}

	/** The offered options in a "Select an option: ..." message: every styled run with a click event, in order. */
	private static List<Option> options(Component message) {
		List<Option> found = new ArrayList<>();
		message.visit((style, text) -> {
			String label = normalize(text);
			Action action = switch (style.getClickEvent()) {
				case ClickEvent.RunCommand rc -> new Action.Command(rc.command());
				case ClickEvent.SuggestCommand sc -> new Action.Command(sc.command());
				case ClickEvent.Custom c -> new Action.Custom(c.id(), c.payload());
				case null, default -> null;
			};
			if (action != null && !label.isEmpty()) {
				TextColor color = style.getColor();
				// TextColor.GREEN only exists from 26.2 on; fromLegacyFormat is the same call on 26.1.2 and 26.2.
				boolean green = TextColor.fromLegacyFormat(ChatFormatting.GREEN).equals(color)
						|| TextColor.fromLegacyFormat(ChatFormatting.DARK_GREEN).equals(color);
				found.add(new Option(label, green, action));
			}
			return Optional.empty();          // never stop early: every run must be visited
		}, message.getStyle());
		return List.copyOf(found);
	}

	/** "[Yes]" -> "Yes": the brackets are Hypixel's own formatting, not part of the answer. */
	private static String normalize(String text) {
		String trimmed = text.trim();
		if (trimmed.length() >= 2 && trimmed.startsWith("[") && trimmed.endsWith("]")) {
			trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
		}
		return trimmed;
	}

	private void send(Action action) {
		switch (action) {
			case Action.Command c -> {
				if (NpcDialog.isCommand(c.value())) {
					env.sendCommand(NpcDialog.stripSlash(c.value()));
				} else {
					env.sendChat(c.value());
				}
			}
			case Action.Custom c -> env.sendCustomClick(c.id(), c.payload());
		}
	}

	// ---------------------------------------------------------------- decision (package-private: tested directly)

	/** The options a dialog just offered. Decides, and if something is chosen, schedules it a short, natural moment later. */
	void onOptions(List<Option> options, long now) {
		if (options.isEmpty() || !enabled.getAsBoolean()) {
			return;
		}
		List<NpcDialog.Offer> offers = options.stream().map(o -> new NpcDialog.Offer(o.label(), o.green())).toList();
		String chosen = NpcDialog.pick(offers);
		if (chosen == null) {
			env.say("[Auto Conversation] No green option to click: "
					+ options.stream().map(Option::label).reduce((a, b) -> a + " / " + b).orElse("") + ". Click it yourself.");
			return;
		}
		Action action = options.stream().filter(o -> o.label().equals(chosen)).map(Option::action).findFirst().orElse(null);
		if (action == null) {
			return;
		}
		long delay = DELAY_MIN_NANOS + (long) (random.nextDouble() * (DELAY_MAX_NANOS - DELAY_MIN_NANOS));
		pending = new Pending(action, now + delay);
	}

	/** Whether something is waiting to be sent. For tests. */
	boolean hasPending() {
		return pending != null;
	}
}
