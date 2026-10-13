package com.femboyclient;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Server Display (a copy of OdinClient 1.2.5.beta7's "Server Hud"): your ping, the server's TPS and your FPS on the screen, in
 * a row ("TPS: 20  Ping: 60" in the accent colour) or stacked ("Ping: 60ms", "TPS: 20.0", "FPS: 240" one under another).
 * Where it sits (X and Y, in GUI pixels) and its size are set in the G menu.
 */
public final class ServerDisplay {

	public static final HandMan.Range SCALE = new HandMan.Range(50, 300, 10);     // percent
	public static final HandMan.Range POSITION = new HandMan.Range(0, 1000, 1);   // GUI pixels

	private ServerDisplay() {
	}

	static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		FemboyClientConfig config = FemboyClientConfig.get();
		if (!config.enabled || !config.serverDisplay || mc.player == null
				|| !(config.serverDisplayPing || config.serverDisplayTps || config.serverDisplayFps)) {
			return;
		}
		List<String> parts = new ArrayList<>();
		int ping = (int) ServerStats.ping(), fps = mc.getFps();
		double tps = ServerStats.tps();
		if (config.serverDisplayStacked == 0) {
			if (config.serverDisplayTps) {
				parts.add("TPS: §f" + (tps > 19.3 ? 20 : (int) tps));
			}
			if (config.serverDisplayFps) {
				parts.add("FPS: §f" + fps);
			}
			if (config.serverDisplayPing) {
				parts.add("Ping: §f" + ping);
			}
		} else {
			if (config.serverDisplayPing) {
				parts.add("§6Ping: §a" + ping + "ms");
			}
			if (config.serverDisplayTps) {
				parts.add("§3TPS: §a" + String.format(Locale.US, "%.1f", tps));
			}
			if (config.serverDisplayFps) {
				parts.add("§dFPS: §a" + fps);
			}
		}

		Font font = mc.font;
		float scale = config.serverDisplayScale / 100F;
		int width, height;
		if (config.serverDisplayStacked == 0) {
			width = font.width(String.join("  ", parts));
			height = font.lineHeight;
		} else {
			width = parts.stream().mapToInt(font::width).max().orElse(0);
			height = parts.size() * (font.lineHeight + 1);
		}
		int x = Math.max(0, Math.min(config.serverDisplayX, (int) (g.guiWidth() - width * scale)));
		int y = Math.max(0, Math.min(config.serverDisplayY, (int) (g.guiHeight() - height * scale)));

		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(scale, scale);
		if (config.serverDisplayStacked == 0) {
			int at = 0;
			for (String part : parts) {
				g.text(font, part, at, 0, Theme.ACCENT_LIGHT | 0xFF000000, true);
				at += font.width(part) + font.width("  ");
			}
		} else {
			for (int i = 0; i < parts.size(); i++) {
				g.text(font, parts.get(i), 0, i * (font.lineHeight + 1), 0xFFFFFFFF, true);
			}
		}
		g.pose().popMatrix();
	}
}
