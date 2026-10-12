package com.femboyclient;

/**
 * The text typed into the Frienemys screen's note field: the editing rules without any drawing, so the screen
 * can use its own font for it. Unlike {@link SearchModel} (locked to what a username can hold), this accepts any
 * printable character and a longer length, since a note is a free sentence, not a username.
 * Kept free of Minecraft classes so it can be tested on its own.
 *
 * The limit matches the website's and the server's (500 characters): a note this long, made on the website, must
 * never be silently cut short just because it is then opened in this screen.
 */
public final class NotesModel {

	public static final int MAX_LENGTH = 500;

	private final StringBuilder text = new StringBuilder();
	private boolean allSelected;

	/** What is typed. */
	public String text() {
		return text.toString();
	}

	public boolean isEmpty() {
		return text.length() == 0;
	}

	/** True after Ctrl+A: the next character or backspace replaces everything. */
	public boolean allSelected() {
		return allSelected && !isEmpty();
	}

	/** Adds one typed character. Returns true if the text changed. */
	public boolean type(int codepoint) {
		if (!allowed(codepoint)) {
			return false;
		}
		if (allSelected) {
			text.setLength(0);
			allSelected = false;
		}
		if (text.length() >= MAX_LENGTH) {
			return false;
		}
		text.append((char) codepoint);
		return true;
	}

	/** Pastes text: every character that is allowed, up to the length limit. Returns true if the text changed. */
	public boolean paste(String pasted) {
		if (pasted == null) {
			return false;
		}
		boolean changed = false;
		for (int i = 0; i < pasted.length(); i++) {
			changed |= type(pasted.charAt(i));
		}
		return changed;
	}

	/** Removes the last character, or everything after Ctrl+A. Returns true if the text changed. */
	public boolean backspace() {
		if (text.length() == 0) {
			return false;
		}
		if (allSelected) {
			text.setLength(0);
			allSelected = false;
		} else {
			text.setLength(text.length() - 1);
		}
		return true;
	}

	/** Ctrl+A. */
	public void selectAll() {
		allSelected = true;
	}

	/** Clears the selection without changing the text (for when the box loses focus). */
	public void clearSelection() {
		allSelected = false;
	}

	/** Empties the box. Returns true if there was anything in it. */
	public boolean clear() {
		boolean had = text.length() > 0;
		text.setLength(0);
		allSelected = false;
		return had;
	}

	/** Sets the whole text (for example, loading a player's existing note), keeping only what is allowed. */
	public void set(String value) {
		clear();
		paste(value);
	}

	/** Any printable, non-control character. A note is free text, so nothing is filtered beyond that. */
	private static boolean allowed(int codepoint) {
		return codepoint >= 0x20 && codepoint != 0x7F;
	}
}
