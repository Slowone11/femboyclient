package com.femboyclient;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/**
 * The key (G by default, changeable in Options > Controls > Femboy Client) that opens the G menu, the hub for every module.
 * It only opens while you are in a world, and never sends anything to the game server.
 */
public final class PlayerInfoKey {

	private static KeyMapping key;

	/** The "Femboy Client" group in Options > Controls. */
	static KeyMapping.Category category;

	/**
	 * Answers are kept for a minute, across openings of the screen. While Lobby Networth is switched off, nothing is asked of
	 * the website at all; the screen still lists who is in the lobby, from the game's own tab list.
	 */
	private static final PlayerInfoCache CACHE = new PlayerInfoCache(
			player -> FemboyClientConfig.get().lobbyNetworth
					? FemboyClientMod.SERVER.playerInfo(player.id(), player.name())
					: java.util.concurrent.CompletableFuture.completedFuture(
							PlayerInfo.failed("Lobby Networth is off. Turn it on in the G menu to look players up."))
			, System::nanoTime);

	private PlayerInfoKey() {
	}

	/** Registers the key. Call once at start-up. */
	public static void register() {
		category = KeyMapping.Category.register(
				Identifier.fromNamespaceAndPath(FemboyClientMod.MOD_ID, "main"));
		key = KeyMappingHelper.registerKeyMapping(
				new KeyMapping("key." + FemboyClientMod.MOD_ID + ".players", InputConstants.KEY_G, category));
	}

	/** Called every client tick: opens the screen when the key was pressed. */
	public static void onClientTick(Minecraft client) {
		if (key == null) {
			return;
		}
		while (key.consumeClick()) {          // drains the presses so one tap opens it once
			if (client.player != null && client.getConnection() != null) {
				client.setScreenAndShow(new ClientMenuScreen());
			}
		}
	}

	/** A fresh players screen, for {@link ClientMenuScreen}'s "Lobby Networth" row. */
	public static PlayerInfoScreen playersScreen() {
		return new PlayerInfoScreen(FemboyClientMod.PLAYER_TRACKER.lobbyPlayers(), CACHE);
	}
}
