package com.femboyclient;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.util.FormattedCharSequence;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws a crafting recipe small and plain, in the menu's style: a 3×3 grid of faint rounded slots with the item pictures in
 * them (a stack count in the corner), an arrow, and the output slot. Slots light up under the mouse, and {@link #drawTooltip}
 * names the item under the mouse in a small card. {@code slot} is the size of one grid slot in GUI pixels.
 */
final class RecipeView {

	static final int GAP = 2, ARROW = 14;

	private static final Map<Integer, FormattedCharSequence> COUNTS = new HashMap<>();
	private static final Map<String, FormattedCharSequence> NAMES = new HashMap<>();
	private static FormattedCharSequence arrow;

	// The slot under the mouse this frame, for the tooltip drawn last.
	private static WikiDoc.Slot hovered;
	private static int hoverX, hoverY;

	private RecipeView() {
	}

	static int grid(int slot) {
		return 3 * slot + 2 * GAP;
	}

	static int output(int slot) {
		return slot + 6;
	}

	static int width(int slot) {
		return grid(slot) + ARROW + output(slot);
	}

	/** Forgets last frame's hovered slot. Call at the start of a frame. */
	static void beginFrame() {
		hovered = null;
	}

	/** Draws the recipe with its top-left at (x, y). {@code hover} false: the mouse is over something on top. */
	static void draw(GuiGraphicsExtractor g, Font font, WikiDoc.Recipe recipe, int x, int y, int slot, int mouseX, int mouseY, boolean hover) {
		for (int i = 0; i < 9; i++) {
			int sx = x + i % 3 * (slot + GAP), sy = y + i / 3 * (slot + GAP);
			WikiDoc.Slot item = recipe.grid().get(i);
			slot(g, font, item, sx, sy, slot, hover && item != null && UiDraw.inside(mouseX, mouseY, sx, sy, sx + slot, sy + slot), mouseX, mouseY);
		}
		if (arrow == null) {
			arrow = UiFont.sequence("→", UiFont.Face.BODY);
		}
		int mid = y + grid(slot) / 2;
		UiDraw.text(g, font, arrow, x + grid(slot) + (ARROW - font.width(arrow)) / 2, UiFont.centeredY(UiFont.Face.BODY, mid - 6, 12),
				Theme.TEXT_LABEL);
		int out = output(slot), ox = x + grid(slot) + ARROW, oy = mid - out / 2;
		slot(g, font, recipe.output(), ox, oy, out, hover && UiDraw.inside(mouseX, mouseY, ox, oy, ox + out, oy + out), mouseX, mouseY);
	}

	/** The slot of {@code recipe} drawn at (x, y) under (mx, my), or null. */
	static WikiDoc.Slot slotAt(WikiDoc.Recipe recipe, int x, int y, int slot, double mx, double my) {
		for (int i = 0; i < 9; i++) {
			int sx = x + i % 3 * (slot + GAP), sy = y + i / 3 * (slot + GAP);
			if (recipe.grid().get(i) != null && UiDraw.inside(mx, my, sx, sy, sx + slot, sy + slot)) {
				return recipe.grid().get(i);
			}
		}
		int out = output(slot), ox = x + grid(slot) + ARROW, oy = y + grid(slot) / 2 - out / 2;
		return UiDraw.inside(mx, my, ox, oy, ox + out, oy + out) ? recipe.output() : null;
	}

	/** One slot: a faint rounded square, its border pink under the mouse, the picture inside, the count in the corner. */
	private static void slot(GuiGraphicsExtractor g, Font font, WikiDoc.Slot item, int x, int y, int size, boolean hover, int mouseX, int mouseY) {
		UiDraw.card(g, x, y, x + size, y + size, 3, hover ? Theme.CARD_HOVER_FILL : Theme.CARD_FILL, hover ? Theme.ACCENT : Theme.CARD_BORDER);
		if (item == null) {
			return;
		}
		if (!WikiImages.draw(g, item.image(), x + 1, y + 1, size - 2) && !ItemIcons.draw(g, item.name(), x + 1, y + 1, size - 2)) {
			UiDraw.roundRect(g, x + 3, y + 3, x + size - 3, y + size - 3, 2, Theme.ACCENT_TINT);      // still loading
		}
		if (item.count() > 1) {
			FormattedCharSequence count = COUNTS.computeIfAbsent(item.count(), c -> UiFont.sequence(Integer.toString(c), UiFont.Face.LABEL));
			UiDraw.text(g, font, count, x + size - font.width(count), UiFont.topY(UiFont.Face.LABEL, y + size - UiFont.capHeight(UiFont.Face.LABEL) - 1),
					Theme.TEXT);
		}
		if (hover) {
			hovered = item;
			hoverX = mouseX;
			hoverY = mouseY;
		}
	}

	/** The name of the item under the mouse, in a small card by the mouse, kept inside the screen. Draw it last. */
	static void drawTooltip(GuiGraphicsExtractor g, Font font, int screenWidth, int screenHeight) {
		if (hovered == null) {
			return;
		}
		String text = hovered.count() > 1 ? hovered.name() + "  ×" + hovered.count() : hovered.name();
		FormattedCharSequence name = NAMES.computeIfAbsent(text, t -> UiFont.sequence(t, UiFont.Face.STRONG));
		int w = font.width(name) + 16, h = 20;
		int x = Math.min(hoverX + 10, screenWidth - w - 2), y = Math.max(2, hoverY - h - 4);
		UiDraw.card(g, x, y, x + w, y + h, Theme.ROW_RADIUS, Theme.TOAST_FILL, Theme.TOAST_BORDER);
		UiDraw.text(g, font, name, x + 8, UiFont.centeredY(UiFont.Face.STRONG, y, h), Theme.TEXT);
	}
}
