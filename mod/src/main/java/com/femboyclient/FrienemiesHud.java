package com.femboyclient;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;

import java.util.List;

/**
 * The top-left overlay: any player you have tagged friendly or enemy who is in your current lobby right now, with
 * their face, name and tag, color coded (green for friendly, red for enemy). Draws nothing the rest of the time.
 * Switched on from the Frienemys row in the G menu ({@link ClientMenuScreen}); the tags themselves come from
 * {@link FrienemiesSync}, kept fresh from the website, and are made either from {@link FrienemiesScreen} or the
 * website.
 */
final class FrienemiesHud {

	private static final int X = 4, Y = 4;
	private static final int PAD = 5;
	private static final int GAP = 6;
	private static final int PANEL = 0xF01A121C;      // the website's dark panel colour, same as HollowsHud
	private static final int LINE = 0xFF2A1E30;

	private static final long REFRESH_NANOS = 250_000_000L;
	private static List<Frienemy> cached = List.of();
	private static List<Frienemy> cachedFor;
	private static long cachedAt;

	private FrienemiesHud() {
	}

	/** Called by the game every frame. Draws nothing unless the module is on and a tagged player is in the lobby. */
	static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		FemboyClientConfig config = FemboyClientConfig.get();
		ClientPacketListener connection = mc.getConnection();
		if (!config.enabled || !config.frienemys || mc.player == null || connection == null) {
			return;
		}
		List<Frienemy> tagged = FrienemiesSync.all();
		if (tagged.isEmpty()) {
			return;                                   // nobody tagged: do not read the tab list every frame for nothing
		}
		List<Frienemy> present = present(tagged);
		if (present.isEmpty()) {
			return;
		}
		UiDraw.setScale(mc.getWindow().getGuiScale());
		UiDraw.setFade(1f);
		draw(g, mc.font, connection, present);
	}

	/** Who is in the lobby, worked out at most every quarter second: the tab list changes far slower than frames. */
	private static List<Frienemy> present(List<Frienemy> tagged) {
		long now = System.nanoTime();
		if (cachedFor != tagged || now - cachedAt > REFRESH_NANOS) {
			cached = Frienemy.presentIn(tagged, FemboyClientMod.PLAYER_TRACKER.lobbyPlayers());
			cachedFor = tagged;
			cachedAt = now;
		}
		return cached;
	}

	private static void draw(GuiGraphicsExtractor g, Font font, ClientPacketListener connection, List<Frienemy> present) {
		int row = Math.max(Theme.HEAD_SIZE, font.lineHeight + 2);
		int textLeft = PAD + Theme.HEAD_SIZE + GAP;
		int width = textLeft + PAD;
		for (Frienemy one : present) {
			width = Math.max(width, textLeft + rowTextWidth(font, one) + PAD);
		}
		int height = PAD * 2 + present.size() * row;

		UiDraw.roundRect(g, X, Y, X + width, Y + height, 4, PANEL);
		UiDraw.border(g, X, Y, X + width, Y + height, 4, LINE);

		int y = Y + PAD;
		for (Frienemy one : present) {
			PlayerInfo entry = one.uuid() == null ? null : connection.getPlayerInfo(one.uuid());
			if (entry != null) {
				UiDraw.head(g, entry.getSkin(), X + PAD, y + (row - Theme.HEAD_SIZE) / 2, Theme.HEAD_SIZE);
			}
			int textY = y + (row - font.lineHeight) / 2;
			int nameWidth = font.width(one.name());
			g.text(font, one.name(), X + textLeft, textY, Theme.TEXT, false);
			g.text(font, " " + tagWord(one), X + textLeft + nameWidth, textY, tagColor(one), false);
			y += row;
		}
	}

	private static int rowTextWidth(Font font, Frienemy one) {
		return font.width(one.name() + " " + tagWord(one));
	}

	private static String tagWord(Frienemy one) {
		return one.friendly() ? "Friendly" : "Enemy";
	}

	private static int tagColor(Frienemy one) {
		return one.friendly() ? Theme.RANK_FILL[0] : Theme.TEXT_ERROR;
	}
}
