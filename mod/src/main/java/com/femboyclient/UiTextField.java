package com.femboyclient;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.util.FormattedCharSequence;

/**
 * A one-line text field in the menu's look: the same rounded card as the Lobby Networth search box (an accent border and a soft
 * glow while focused, a hint while empty, a blinking pink caret), drawn with {@link UiDraw} in the body face. The text scrolls
 * sideways to keep the caret in view. The editing rules live in {@link TextFieldModel}.
 *
 * Usage: {@link #setBounds} when the screen lays out, {@link #draw} every frame, and pass clicks and keys to {@link #click} and
 * {@link #key} / {@link #type} while it is focused.
 */
public final class UiTextField {

	private static final int TEXT_INSET = 10;

	public final TextFieldModel model;
	private final String placeholder;
	private FormattedCharSequence placeholderText;

	private int x0, y0, x1, y1;
	private boolean focused;
	private long caretSince = System.nanoTime();

	// The drawn text, rebuilt only when the text or the caret changes (styling a text allocates).
	private String shownFor;
	private int shownCaret = -1, shownAnchor = -1, shownRoom = -1;
	private FormattedCharSequence shownText;
	private int scroll, caretX, selectX0, selectX1;

	public UiTextField(int maxLength, String placeholder) {
		this.model = new TextFieldModel(maxLength);
		this.placeholder = placeholder;
	}

	public void setBounds(int x0, int y0, int x1, int y1) {
		this.x0 = x0;
		this.y0 = y0;
		this.x1 = x1;
		this.y1 = y1;
	}

	public int x0() {
		return x0;
	}

	public int y0() {
		return y0;
	}

	public int x1() {
		return x1;
	}

	public int y1() {
		return y1;
	}

	public boolean focused() {
		return focused;
	}

	public void setFocused(boolean focused) {
		if (focused && !this.focused) {
			caretSince = System.nanoTime();
		}
		this.focused = focused;
	}

	public boolean inside(double x, double y) {
		return UiDraw.inside(x, y, x0, y0, x1, y1);
	}

	/**
	 * Draws the field. {@code rightReserve} GUI pixels at the right end are left free for something drawn over it (the
	 * calculator's answer); {@code border} replaces the focused accent border when not 0 (the error color).
	 */
	public void draw(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY, int rightReserve, int border) {
		boolean hover = inside(mouseX, mouseY);
		if (focused) {
			UiDraw.glow(g, x0, y0, x1, y1, Theme.ROW_RADIUS, border != 0 ? border : Theme.ACCENT, 3);
		}
		int edge = border != 0 ? border : focused ? Theme.ACCENT : hover ? Theme.CARD_HOVER_BORDER : Theme.CARD_BORDER;
		// The menu's plum glass first, so the field reads over whatever is behind it (a busy inventory, the world).
		UiDraw.roundRect(g, x0, y0, x1, y1, Theme.ROW_RADIUS, Theme.PANEL);
		UiDraw.card(g, x0, y0, x1, y1, Theme.ROW_RADIUS, focused || hover ? Theme.CARD_HOVER_FILL : Theme.CARD_FILL, edge);

		int textX = x0 + TEXT_INSET;
		int room = Math.max(10, x1 - TEXT_INSET - rightReserve - textX);
		int textY = UiFont.centeredY(UiFont.Face.BODY, y0, y1 - y0);
		if (model.isEmpty()) {
			if (placeholderText == null) {
				placeholderText = UiFont.sequence(UiFont.fit(font, placeholder, UiFont.Face.BODY, room), UiFont.Face.BODY);
			}
			UiDraw.text(g, font, placeholderText, textX, textY, Theme.TEXT_LABEL);
		} else {
			layoutText(font, room);
			UiDraw.beginClip(g, textX - 2, y0, textX + room + 1, y1);
			if (focused && model.hasSelection()) {
				UiDraw.roundRect(g, textX + selectX0 - scroll - 1, y0 + 4, textX + selectX1 - scroll + 1, y1 - 4, 3, Theme.ACCENT_SELECTED);
			}
			UiDraw.text(g, font, shownText, textX - scroll, textY, Theme.TEXT);
			UiDraw.endClip(g);
		}
		boolean caretOn = ((System.nanoTime() - caretSince) / 1_000_000L) % Theme.CARET_BLINK_MS < Theme.CARET_BLINK_MS / 2;
		if (focused && caretOn) {
			int x = textX + (model.isEmpty() ? 0 : caretX - scroll);
			UiDraw.fill(g, x, y0 + 5, x + 1, y1 - 5, Theme.ACCENT);
		}
	}

	/** Measures the text and the caret, and scrolls so the caret stays in view. Only when something changed. */
	private void layoutText(Font font, int room) {
		String text = model.text();
		if (text.equals(shownFor) && model.caret() == shownCaret && model.selectionStart() + model.selectionEnd() * 1000 == shownAnchor
				&& room == shownRoom) {
			return;
		}
		shownFor = text;
		shownCaret = model.caret();
		shownAnchor = model.selectionStart() + model.selectionEnd() * 1000;
		shownRoom = room;
		shownText = UiFont.sequence(text, UiFont.Face.BODY);
		caretX = prefixWidth(font, text, model.caret());
		selectX0 = prefixWidth(font, text, model.selectionStart());
		selectX1 = prefixWidth(font, text, model.selectionEnd());
		int full = font.width(shownText);
		if (caretX - scroll > room) {
			scroll = caretX - room;
		} else if (caretX - scroll < 0) {
			scroll = caretX;
		}
		scroll = Math.max(0, Math.min(scroll, Math.max(0, full - room + 1)));
	}

	private static int prefixWidth(Font font, String text, int end) {
		return end <= 0 ? 0 : UiFont.width(font, text.substring(0, Math.min(end, text.length())), UiFont.Face.BODY);
	}

	/** A left click: focuses the field and puts the caret under the mouse. Returns true if the click was on the field. */
	public boolean click(Font font, double x, double y, boolean shift) {
		if (!inside(x, y)) {
			return false;
		}
		setFocused(true);
		String text = model.text();
		double local = x - (x0 + TEXT_INSET) + scroll;
		int best = text.length();
		for (int i = 0; i <= text.length(); i++) {
			int w = prefixWidth(font, text, i);
			int next = i < text.length() ? prefixWidth(font, text, i + 1) : Integer.MAX_VALUE;
			if (local < (w + Math.min(next, w + 20)) / 2.0) {
				best = i;
				break;
			}
		}
		model.moveTo(best, shift);
		caretSince = System.nanoTime();
		return true;
	}

	/** A typed character. Returns true if the text changed. */
	public boolean type(int codepoint) {
		caretSince = System.nanoTime();
		return model.type(codepoint);
	}

	/**
	 * An editing key (arrows, Home, End, Backspace, Delete, Ctrl+A/C/X/V). Returns 1 if the text changed, 0 if the key was an
	 * editing key that did not change the text (a caret move), -1 if it is not an editing key.
	 */
	public int key(KeyEvent event) {
		caretSince = System.nanoTime();
		boolean word = event.hasControlDown(), shift = event.hasShiftDown();
		var keyboard = Minecraft.getInstance().keyboardHandler;
		if (event.isSelectAll()) {
			model.selectAll();
			return 0;
		}
		if (event.isCopy()) {
			if (model.hasSelection()) {
				keyboard.setClipboard(model.selected());
			}
			return 0;
		}
		if (event.isCut()) {
			if (!model.hasSelection()) {
				return 0;
			}
			keyboard.setClipboard(model.selected());
			return model.backspace(false) ? 1 : 0;
		}
		if (event.isPaste()) {
			return model.paste(keyboard.getClipboard().replace('\n', ' ').replace('\r', ' ')) ? 1 : 0;
		}
		switch (event.key()) {
			case InputConstants.KEY_BACKSPACE -> {
				return model.backspace(word) ? 1 : 0;
			}
			case InputConstants.KEY_DELETE -> {
				return model.delete(word) ? 1 : 0;
			}
			case InputConstants.KEY_LEFT -> model.left(word, shift);
			case InputConstants.KEY_RIGHT -> model.right(word, shift);
			case InputConstants.KEY_HOME -> model.home(shift);
			case InputConstants.KEY_END -> model.end(shift);
			default -> {
				return -1;
			}
		}
		return 0;
	}
}
