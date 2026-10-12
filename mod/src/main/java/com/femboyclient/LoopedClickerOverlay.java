package com.femboyclient;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

/**
 * The Looped Clicker module's controls: a small panel, shown over any open container (your inventory, a chest,
 * anything with slots) while the module is on in the G menu. "Set Location" arms picking a spot — the very next
 * click, wherever it lands, becomes the target, shown after as a small dot; pressing the button again re-arms it
 * so a new click replaces the old spot, and Escape abandons an armed pick without setting one or closing the
 * container. The panel itself can be dragged anywhere by its title, and a key (set from its own "Keybind" row, no
 * separate Controls screen needed) toggles the loop's Start/Stop from anywhere while the container is open.
 * Everything else about the container is untouched — a click or key outside what this uses passes straight
 * through as normal. Hooked onto vanilla and modded screens alike with {@link ScreenEvents}, the same way
 * {@link HollowsMapDrag} puts the Hollows map on the chat screen, rather than a mixin.
 */
final class LoopedClickerOverlay {

	private static final int PANEL_W = 160;
	private static final int ROW_H = 22;
	private static final int TITLE_H = 20;
	private static final int ROW_GAP = 6;
	private static final int MARKER_R = 6;
	private static final int ESCAPE = 256;   // GLFW key code: cancels a pending keybind instead of binding it
	// A flat, mostly-opaque panel rather than Theme's blurred glass: this sits over a screen this mod does not
	// own, so it must never blur the container behind it, only its own small box.
	private static final int PANEL = 0xF01A121C;
	private static final int LINE = 0xFF2A1E30;

	private static int panelX0, panelX1, panelY0, panelY1, panelH;
	private static int titleY0, titleY1;
	private static int leftX0, leftX1, rightX0, rightX1, buttonY0, buttonY1;
	private static int minusX0, minusX1, plusX0, plusX1, speedY0, speedY1;
	private static int startX0, startX1, startY0, startY1;
	private static int clicksY;
	private static int keyY0, keyY1;
	private static int setLocY0, setLocY1;

	private static boolean draggingPanel, bindingKey, pickingLocation;
	private static int dragOffsetX, dragOffsetY;
	private static int heldKey = -1;   // the bound key while it is down, so an OS key-repeat cannot toggle again

	private LoopedClickerOverlay() {
	}

	static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?>)) {
				return;
			}
			// Not LoopedClicker.stop() here: plenty of server menus close their screen and open a new one to
			// refresh after almost every click, and the loop is meant to keep going through that anyway.
			ScreenEvents.remove(screen).register(s -> {
				bindingKey = false;
				pickingLocation = false;
				heldKey = -1;
			});
			ScreenEvents.afterExtract(screen).register(LoopedClickerOverlay::render);
			ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> !press(s, event));
			ScreenMouseEvents.allowMouseDrag(screen).register((s, event, dx, dy) -> !drag(s, event));
			ScreenMouseEvents.allowMouseRelease(screen).register((s, event) -> !release());
			ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> !keyPress(event));
			ScreenKeyboardEvents.allowKeyRelease(screen).register((s, event) -> !keyRelease(event));
		});
	}

	private static boolean usable() {
		FemboyClientConfig config = FemboyClientConfig.get();
		return config.enabled && config.clicker;
	}

	/**
	 * Where the panel and its rows sit. The panel's own place comes from config, pulled back inside the screen: a
	 * spot saved at a smaller GUI scale or in a bigger window would otherwise put the whole panel off-screen.
	 */
	private static void layout(int screenWidth, int screenHeight) {
		FemboyClientConfig config = FemboyClientConfig.get();
		int x = config.clickerPanelX < 0 ? screenWidth - Theme.PADDING - PANEL_W : config.clickerPanelX;
		place(Math.max(0, Math.min(screenWidth - PANEL_W, x)), config.clickerPanelY < 0 ? Theme.PADDING : config.clickerPanelY);
		int fitY = Math.max(0, Math.min(screenHeight - panelH, panelY0));
		if (fitY != panelY0) {
			place(panelX0, fitY);
		}
	}

	/** Lays the panel's rows out with its top-left corner at (x, y). */
	private static void place(int x, int y) {
		panelX0 = x;
		panelX1 = panelX0 + PANEL_W;
		panelY0 = y;

		titleY0 = panelY0;
		titleY1 = panelY0 + TITLE_H;

		int left = panelX0 + 8, right = panelX1 - 8;
		int chipGap = 6, chipW = (right - left - chipGap) / 2;
		buttonY0 = titleY1 + 4;
		buttonY1 = buttonY0 + ROW_H;
		leftX0 = left;
		leftX1 = left + chipW;
		rightX0 = leftX1 + chipGap;
		rightX1 = right;

		speedY0 = buttonY1 + ROW_GAP;
		speedY1 = speedY0 + ROW_H;
		minusX0 = left;
		minusX1 = minusX0 + 20;
		plusX1 = right;
		plusX0 = plusX1 - 20;

		startX0 = left;
		startX1 = right;
		startY0 = speedY1 + ROW_GAP;
		startY1 = startY0 + 24;

		clicksY = startY1 + 4;

		keyY0 = clicksY + 12 + ROW_GAP;
		keyY1 = keyY0 + ROW_H;

		setLocY0 = keyY1 + ROW_GAP;
		setLocY1 = setLocY0 + ROW_H;

		panelY1 = setLocY1 + 8;
		panelH = panelY1 - panelY0;
	}

	private static String keyName(int keyCode) {
		return keyCode < 0 ? "not set" : InputConstants.Type.KEYSYM.getOrCreate(keyCode).getDisplayName().getString();
	}

	private static void render(Screen screen, GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		if (!usable()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		FemboyClientConfig config = FemboyClientConfig.get();
		layout(screen.width, screen.height);

		UiDraw.setScale(mc.getWindow().getGuiScale());
		UiDraw.setFade(1f);

		UiDraw.roundRect(g, panelX0, panelY0, panelX1, panelY1, Theme.CARD_RADIUS, PANEL);
		UiDraw.border(g, panelX0, panelY0, panelX1, panelY1, Theme.CARD_RADIUS, LINE);
		UiDraw.divider(g, panelX0 + 1, titleY1, panelX1 - 1);
		g.text(mc.font, "Looped Clicker", panelX0 + 8, panelY0 + 7, Theme.TEXT, false);

		chip(g, mc, mouseX, mouseY, leftX0, leftX1, buttonY0, buttonY1, "Left", !config.clickerRightClick);
		chip(g, mc, mouseX, mouseY, rightX0, rightX1, buttonY0, buttonY1, "Right", config.clickerRightClick);

		chip(g, mc, mouseX, mouseY, minusX0, minusX1, speedY0, speedY1, "-", false);
		chip(g, mc, mouseX, mouseY, plusX0, plusX1, speedY0, speedY1, "+", false);
		String speedText = config.clickerMs + " ms";
		g.text(mc.font, speedText, minusX1 + (plusX0 - minusX1 - mc.font.width(speedText)) / 2, speedY0 + 7, Theme.TEXT_DIM, false);

		boolean running = LoopedClicker.running();
		boolean startHover = UiDraw.inside(mouseX, mouseY, startX0, startY0, startX1, startY1);
		UiDraw.accentCard(g, startX0, startY0, startX1, startY1, Theme.ROW_RADIUS, Theme.ACCENT, running, startHover);
		String startLabel = running ? "Stop" : "Start";
		g.text(mc.font, startLabel, startX0 + (startX1 - startX0 - mc.font.width(startLabel)) / 2, startY0 + 8,
				running ? Theme.TEXT : Theme.TEXT_DIM, false);

		// So it is obvious from the panel alone whether the loop is still ticking, not just whether it thinks it is.
		String clicksText = "Clicks: " + LoopedClicker.clicks();
		g.text(mc.font, clicksText, startX0 + (startX1 - startX0 - mc.font.width(clicksText)) / 2, clicksY, Theme.TEXT_DIM, false);

		String keyLabel = bindingKey ? "Press a key…" : "Keybind: " + keyName(config.clickerKey);
		boolean keyHover = UiDraw.inside(mouseX, mouseY, startX0, keyY0, startX1, keyY1);
		UiDraw.accentCard(g, startX0, keyY0, startX1, keyY1, Theme.ROW_RADIUS, Theme.ACCENT, bindingKey, keyHover);
		g.text(mc.font, keyLabel, startX0 + (startX1 - startX0 - mc.font.width(keyLabel)) / 2, keyY0 + 7,
				bindingKey ? Theme.TEXT : Theme.TEXT_DIM, false);

		String setLocLabel = pickingLocation ? "Click to set…" : "Set Location";
		boolean setLocHover = UiDraw.inside(mouseX, mouseY, startX0, setLocY0, startX1, setLocY1);
		UiDraw.accentCard(g, startX0, setLocY0, startX1, setLocY1, Theme.ROW_RADIUS, Theme.ACCENT, pickingLocation, setLocHover);
		g.text(mc.font, setLocLabel, startX0 + (startX1 - startX0 - mc.font.width(setLocLabel)) / 2, setLocY0 + 7,
				pickingLocation ? Theme.TEXT : Theme.TEXT_DIM, false);

		// Only shown once a spot has actually been set: no default centre dot, and Esc while picking cancels
		// without ever setting one, so there is nothing to draw until you deliberately place it.
		if (config.clickerX >= 0 && config.clickerY >= 0) {
			// Pulled inside the screen like the panel, and the same spot LoopedClicker clicks.
			UiDraw.glowDot(g, Math.min(config.clickerX, screen.width - 1), Math.min(config.clickerY, screen.height - 1),
					MARKER_R, running ? Theme.TEXT_ERROR : Theme.ACCENT);
		}
	}

	private static void chip(GuiGraphicsExtractor g, Minecraft mc, int mouseX, int mouseY, int x0, int x1, int y0, int y1,
			String label, boolean selected) {
		boolean hover = UiDraw.inside(mouseX, mouseY, x0, y0, x1, y1);
		UiDraw.accentCard(g, x0, y0, x1, y1, Theme.ROW_RADIUS, Theme.ACCENT, selected, hover);
		g.text(mc.font, label, x0 + (x1 - x0 - mc.font.width(label)) / 2, y0 + 7, selected ? Theme.TEXT : Theme.TEXT_DIM, false);
	}

	private static boolean press(Screen s, MouseButtonEvent event) {
		if (!usable() || LoopedClicker.firingSynthetic()) {
			return false;
		}
		double x = event.x(), y = event.y();
		FemboyClientConfig config = FemboyClientConfig.get();

		// Armed by the "Set Location" button: the very next click, wherever it lands, is the new spot. Swallowed
		// either way so it never also acts on the real container; Escape (in keyPress, not here) cancels instead
		// of setting anything, which is how a mis-arm gets abandoned without leaving a stray spot behind.
		if (pickingLocation) {
			if (event.button() == 0) {
				config.clickerX = (int) Math.max(0, Math.min(s.width, x));
				config.clickerY = (int) Math.max(0, Math.min(s.height, y));
				config.save();
			}
			pickingLocation = false;
			return true;
		}

		layout(s.width, s.height);

		if (bindingKey && !UiDraw.inside(x, y, startX0, keyY0, startX1, keyY1)) {
			bindingKey = false;      // clicking away cancels a pending keybind instead of leaving it stuck
		}

		if (event.button() == 1 && UiDraw.inside(x, y, startX0, keyY0, startX1, keyY1)) {
			config.clickerKey = -1;      // right click clears the keybind, same convention as vanilla's Controls screen
			config.save();
			return true;
		}
		if (event.button() != 0) {
			return false;
		}

		if (UiDraw.inside(x, y, panelX0, titleY0, panelX1, titleY1)) {
			draggingPanel = true;
			dragOffsetX = (int) x - panelX0;
			dragOffsetY = (int) y - panelY0;
			return true;
		}
		if (UiDraw.inside(x, y, leftX0, buttonY0, leftX1, buttonY1)) {
			config.clickerRightClick = false;
			config.save();
			return true;
		}
		if (UiDraw.inside(x, y, rightX0, buttonY0, rightX1, buttonY1)) {
			config.clickerRightClick = true;
			config.save();
			return true;
		}
		if (UiDraw.inside(x, y, minusX0, speedY0, minusX1, speedY1)) {
			config.clickerMs = Math.max(LoopedClicker.MIN_MS, config.clickerMs - 10);
			config.save();
			return true;
		}
		if (UiDraw.inside(x, y, plusX0, speedY0, plusX1, speedY1)) {
			config.clickerMs = Math.min(LoopedClicker.MAX_MS, config.clickerMs + 10);
			config.save();
			return true;
		}
		if (UiDraw.inside(x, y, startX0, startY0, startX1, startY1)) {
			LoopedClicker.toggle();
			return true;
		}
		if (UiDraw.inside(x, y, startX0, keyY0, startX1, keyY1)) {
			bindingKey = true;
			return true;
		}
		if (UiDraw.inside(x, y, startX0, setLocY0, startX1, setLocY1)) {
			pickingLocation = true;
			return true;
		}
		return false;
	}

	private static boolean drag(Screen s, MouseButtonEvent event) {
		if (!draggingPanel) {
			return false;
		}
		FemboyClientConfig config = FemboyClientConfig.get();
		config.clickerPanelX = (int) Math.max(0, Math.min(s.width - PANEL_W, event.x() - dragOffsetX));
		config.clickerPanelY = (int) Math.max(0, Math.min(s.height - panelH, event.y() - dragOffsetY));
		return true;
	}

	private static boolean release() {
		if (LoopedClicker.firingSynthetic() || !draggingPanel) {
			return false;
		}
		draggingPanel = false;
		FemboyClientConfig.get().save();
		return true;
	}

	/** True (swallow the key) while capturing a new binding; otherwise toggles Start/Stop when the bound key is hit,
	 * without swallowing it, so a key that also does something vanilla (a hotbar number, say) still does that too.
	 * Only the genuine first press toggles: the game resends this same event for every OS key-repeat while a key
	 * stays down (the same mechanism that repeats a held Backspace in a text box), and reacting to those too would
	 * flip the loop on and off many times a second for as long as the key was held. */
	private static boolean keyPress(KeyEvent event) {
		if (!usable()) {
			return false;
		}
		if (pickingLocation) {
			// Escape abandons the pick without setting anything or closing the container; any other key does not
			// interrupt picking (it is still waiting on a click, not a key), so it is left to act normally.
			if (event.key() == ESCAPE) {
				pickingLocation = false;
				return true;
			}
			return false;
		}
		if (bindingKey) {
			bindingKey = false;
			if (event.key() != ESCAPE) {
				FemboyClientConfig config = FemboyClientConfig.get();
				config.clickerKey = event.key();
				config.save();
			}
			return true;
		}
		FemboyClientConfig config = FemboyClientConfig.get();
		if (config.clickerKey >= 0 && event.key() == config.clickerKey && heldKey != event.key()) {
			heldKey = event.key();
			LoopedClicker.toggle();
		}
		return false;
	}

	/** Clears the held key so its next genuine press toggles again. Never swallows a release. */
	private static boolean keyRelease(KeyEvent event) {
		if (event.key() == heldKey) {
			heldKey = -1;
		}
		return false;
	}
}
