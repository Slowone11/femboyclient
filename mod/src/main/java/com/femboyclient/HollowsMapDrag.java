package com.femboyclient;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;

/**
 * Moving and resizing the MansionFinder map with the mouse. It works in the MansionFinder screen (from the G menu) and,
 * while you are in the Hollows, in the chat: open the chat, then
 * <ul>
 * <li>drag the map with the left button to move it,</li>
 * <li>drag its bottom right corner, or scroll over it, to make it bigger or smaller.</li>
 * </ul>
 * The map always has north up, so there is nothing to turn. The place and size are kept in the config and saved when you let go.
 */
final class HollowsMapDrag {

	static final String HINT = "MansionFinder: drag to move · corner or scroll to resize";

	/** How close to the bottom right corner a click has to be to take hold of it, in GUI pixels. */
	static final int CORNER = 9;

	private enum Mode { NONE, MOVE, RESIZE }

	private static Mode mode = Mode.NONE;
	private static int grabX, grabY;

	private HollowsMapDrag() {
	}

	/** Puts the map on the chat screen: the mouse events of a chat screen go here first. */
	static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof ChatScreen)) {
				return;
			}
			ScreenMouseEvents.allowMouseClick(screen).register((s, event) ->
					!(usable() && press(event.x(), event.y(), event.button(), HollowsHud.model(), s.width, s.height)));
			ScreenMouseEvents.allowMouseDrag(screen).register((s, event, dx, dy) ->
					!(usable() && drag(event.x(), event.y(), dx, dy, HollowsHud.model(), s.width, s.height)));
			ScreenMouseEvents.allowMouseRelease(screen).register((s, event) -> !release());
			ScreenMouseEvents.allowMouseScroll(screen).register((s, x, y, sx, sy) ->
					!(usable() && scroll(x, y, sy, HollowsHud.model(), s.width, s.height)));
		});
	}

	/** True while the map is drawn in the corner, so the chat may take hold of it. */
	static boolean usable() {
		Minecraft mc = Minecraft.getInstance();
		FemboyClientConfig config = FemboyClientConfig.get();
		return config.enabled && config.hud && mc.player != null && !HollowsMapScreen.open
				&& FemboyClientMod.HOLLOWS.inHollowsWorld() && FemboyClientMod.HOLLOWS.level() == mc.level;
	}

	static boolean over(double x, double y, HollowsMapModel.Model m) {
		FemboyClientConfig c = FemboyClientConfig.get();
		return x >= c.hudX && x <= c.hudX + HollowsHud.panelWidth(c.hudSize)
				&& y >= c.hudY && y <= c.hudY + HollowsHud.panelHeight(m, c.hudSize);
	}

	static boolean overCorner(double x, double y, HollowsMapModel.Model m) {
		FemboyClientConfig c = FemboyClientConfig.get();
		double right = c.hudX + HollowsHud.panelWidth(c.hudSize), bottom = c.hudY + HollowsHud.panelHeight(m, c.hudSize);
		return x >= right - CORNER && x <= right + 3 && y >= bottom - CORNER && y <= bottom + 3;
	}

	static boolean resizing() {
		return mode == Mode.RESIZE;
	}

	/** A mouse button went down. True when the map took it. */
	static boolean press(double x, double y, int button, HollowsMapModel.Model m, int screenW, int screenH) {
		FemboyClientConfig c = FemboyClientConfig.get();
		if (!over(x, y, m) && !overCorner(x, y, m)) {
			return false;
		}
		if (button != 0) {
			return false;
		}
		if (overCorner(x, y, m)) {
			mode = Mode.RESIZE;
			grabX = c.hudX + HollowsHud.panelWidth(c.hudSize) - (int) x;     // how far the mouse is from the corner it holds
			return true;
		}
		mode = Mode.MOVE;
		grabX = (int) x - c.hudX;
		grabY = (int) y - c.hudY;
		return true;
	}

	/** The mouse moved with a button down. True when the map is being dragged. */
	static boolean drag(double x, double y, double dx, double dy, HollowsMapModel.Model m, int screenW, int screenH) {
		FemboyClientConfig c = FemboyClientConfig.get();
		switch (mode) {
			case RESIZE -> {
				int size = (int) x + grabX - c.hudX - 2 * HollowsHud.PAD;
				c.hudSize = Math.max(HollowsHud.MIN_SIZE, Math.min(HollowsHud.MAX_SIZE, size));
				while (c.hudSize > HollowsHud.MIN_SIZE && (c.hudX + HollowsHud.panelWidth(c.hudSize) > screenW
						|| c.hudY + HollowsHud.panelHeight(m, c.hudSize) > screenH)) {
					c.hudSize--;
				}
				return true;
			}
			case MOVE -> {
				c.hudX = (int) x - grabX;
				c.hudY = (int) y - grabY;
				clamp(m, screenW, screenH);
				return true;
			}
			default -> {
				return false;
			}
		}
	}

	/** The button came up. True when the map had hold of it. The place is saved. */
	static boolean release() {
		if (mode == Mode.NONE) {
			return false;
		}
		mode = Mode.NONE;
		FemboyClientConfig.get().save();
		return true;
	}

	/** The wheel turned over the map: bigger or smaller. True when the mouse is over the map. */
	static boolean scroll(double x, double y, double amount, HollowsMapModel.Model m, int screenW, int screenH) {
		if (!over(x, y, m)) {
			return false;
		}
		FemboyClientConfig c = FemboyClientConfig.get();
		c.hudSize = Math.max(HollowsHud.MIN_SIZE, Math.min(HollowsHud.MAX_SIZE, c.hudSize + (amount > 0 ? 10 : -10)));
		clamp(m, screenW, screenH);
		c.save();
		return true;
	}

	/** Keeps the whole map on the screen. */
	static void clamp(HollowsMapModel.Model m, int screenW, int screenH) {
		FemboyClientConfig c = FemboyClientConfig.get();
		c.hudX = Math.max(0, Math.min(c.hudX, screenW - HollowsHud.panelWidth(c.hudSize)));
		c.hudY = Math.max(0, Math.min(c.hudY, screenH - HollowsHud.panelHeight(m, c.hudSize)));
	}
}
