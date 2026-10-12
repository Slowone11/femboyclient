package com.femboyclient;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Which lobby player is selected on the screen and what the website said about them.
 *
 * Selecting starts the fetch and returns at once. The answer comes back on another thread and is
 * handed to the game thread through {@code gameThread} before it is stored, so the screen only ever
 * reads plain fields. An answer for a player who is no longer selected is dropped.
 *
 * Kept free of Minecraft classes so it can be tested on its own.
 */
public final class PlayerLookup {

	/** Asks the website about one player, without blocking. */
	public interface Fetcher {
		CompletableFuture<PlayerInfo> fetch(LobbyPlayer player);
	}

	private final Fetcher fetcher;
	private final Executor gameThread;

	private LobbyPlayer selected;
	private PlayerInfo info;

	/** @param gameThread runs a task on the game thread, for example {@code minecraft::execute} */
	public PlayerLookup(Fetcher fetcher, Executor gameThread) {
		this.fetcher = fetcher;
		this.gameThread = gameThread;
	}

	/** Selects a player and starts fetching them. Never blocks and never throws. */
	public void select(LobbyPlayer player) {
		selected = player;
		info = null;

		CompletableFuture<PlayerInfo> answer;
		try {
			answer = fetcher.fetch(player);
		} catch (RuntimeException e) {
			answer = CompletableFuture.completedFuture(PlayerInfo.failed("The request could not be started."));
		}

		answer.whenComplete((result, error) -> gameThread.execute(() -> {
			if (selected == player) {             // the player may have been switched while this was on its way
				info = result != null ? result : PlayerInfo.failed("The request failed.");
			}
		}));
	}

	/** The selected player, or null. */
	public LobbyPlayer selected() {
		return selected;
	}

	/** True from selecting until the answer arrives. */
	public boolean loading() {
		return selected != null && info == null;
	}

	/** The answer for the selected player, or null while it is loading or nobody is selected. */
	public PlayerInfo info() {
		return info;
	}
}
