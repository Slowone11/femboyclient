package com.femboyclient;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.util.Locale;

/**
 * The player screen's fonts. They are Inter, loaded by the game from {@code assets/femboyclient/font/} as TrueType fonts and
 * drawn anti-aliased at 8 times the size, so they stay sharp at every GUI scale. A text is given one of these fonts by
 * styling it; the game does the rest.
 *
 * Making a styled text allocates, so build each one once (when an answer arrives or the screen opens) and keep it. Drawing a
 * finished text does not allocate.
 */
public final class UiFont {

	/** The four faces, with the name of their font file and their size (from {@link Theme}). */
	public enum Face {
		LABEL(Theme.FONT_LABEL, Theme.FONT_LABEL_SIZE),
		BODY(Theme.FONT_BODY, Theme.FONT_BODY_SIZE),
		STRONG(Theme.FONT_STRONG, Theme.FONT_STRONG_SIZE),
		VALUE(Theme.FONT_VALUE, Theme.FONT_VALUE_SIZE);

		final String fontName;
		final float size;

		Face(String fontName, float size) {
			this.fontName = fontName;
			this.size = size;
		}
	}

	/** One style per face, made once. */
	private static final Style[] STYLES = new Style[Face.values().length];

	static {
		for (Face face : Face.values()) {
			STYLES[face.ordinal()] = Style.EMPTY.withFont(new FontDescription.Resource(Identifier.fromNamespaceAndPath(Theme.FONT_NAMESPACE, face.fontName)));
		}
	}

	/** Put between the capitals of a label: a thin space, about a pixel and a third at the label size. */
	private static final char TRACKING_SPACE = ' ';

	private UiFont() {
	}

	/** The style that puts a text in a face, to build mixed texts (wiki paragraphs) from. */
	public static Style style(Face face) {
		return STYLES[face.ordinal()];
	}

	/** A text in a face, for wrapping it into lines. Anything the font lacks (symbols, other alphabets) falls back to the game's own glyphs. */
	public static Component component(String text, Face face) {
		return Component.literal(text).withStyle(STYLES[face.ordinal()]);
	}

	/** A text in a face, ready to draw. */
	public static FormattedCharSequence sequence(String text, Face face) {
		return component(text, face).getVisualOrderText();
	}

	/** How wide a text is in a face, in GUI pixels. */
	public static int width(Font font, String text, Face face) {
		return font.width(sequence(text, face));
	}

	/** The cut-off text (with "..." added) that fits in {@code maxWidth}, or the whole text if it already fits. */
	public static String fit(Font font, String text, Face face, int maxWidth) {
		if (width(font, text, face) <= maxWidth) {
			return text;
		}
		int end = text.length();
		while (end > 1 && width(font, text.substring(0, end) + "...", face) > maxWidth) {
			end--;
		}
		return text.substring(0, end) + "...";
	}

	/** How tall a capital letter is in a face, in pixels. */
	public static int capHeight(Face face) {
		return Math.round(face.size * Theme.CAP_HEIGHT_RATIO);
	}

	/**
	 * The y to draw a single line of text at so that its capital letters sit in the vertical middle of a box. (The game puts every
	 * font's baseline {@link Theme#GLYPH_BASELINE} pixels below the y it is given.)
	 */
	public static int centeredY(Face face, int boxTop, int boxHeight) {
		return boxTop + (boxHeight + capHeight(face)) / 2 - Theme.GLYPH_BASELINE;
	}

	/** The y to draw a line at so that its capital letters' tops are {@code top}. */
	public static int topY(Face face, int top) {
		return top + capHeight(face) - Theme.GLYPH_BASELINE;
	}

	/**
	 * A small ALL-CAPS label with a little space between the letters, laid out once. The game's own text has no letter spacing, so
	 * a thin space (U+2009, which Inter has) is put between the capitals. It is a single text, so drawing one is one call.
	 */
	public static final class Label {
		private final FormattedCharSequence text;
		private final int width;

		private Label(FormattedCharSequence text, int width) {
			this.text = text;
			this.width = width;
		}

		/** Lays a label out in the LABEL face. Call once per label, not every frame. */
		public static Label of(Font font, String text) {
			String upper = text.toUpperCase(Locale.ROOT);
			StringBuilder spaced = new StringBuilder(upper.length() * 2);
			for (int i = 0; i < upper.length(); i++) {
				if (i > 0) {
					spaced.append(TRACKING_SPACE);
				}
				spaced.append(upper.charAt(i));
			}
			FormattedCharSequence sequence = sequence(spaced.toString(), Face.LABEL);
			return new Label(sequence, font.width(sequence));
		}

		public int width() {
			return width;
		}

		FormattedCharSequence text() {
			return text;
		}
	}
}
