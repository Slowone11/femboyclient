package com.femboyclient;

/**
 * The text in a one-line text field, with a caret and a selection: the editing rules without any drawing ({@link UiTextField}
 * draws it). Unlike {@link SearchModel}, the caret can move (arrows, Home, End, Ctrl+arrows by word) and Shift selects. Any
 * printable character is accepted except the section sign, which is the game's color code. Kept free of Minecraft classes so it
 * can be tested on its own.
 */
public final class TextFieldModel {

	private final int maxLength;
	private final StringBuilder text = new StringBuilder();
	private int caret;
	private int anchor;                 // the other end of the selection; equal to the caret when nothing is selected

	public TextFieldModel(int maxLength) {
		this.maxLength = maxLength;
	}

	public String text() {
		return text.toString();
	}

	public boolean isEmpty() {
		return text.length() == 0;
	}

	public int caret() {
		return caret;
	}

	public int selectionStart() {
		return Math.min(caret, anchor);
	}

	public int selectionEnd() {
		return Math.max(caret, anchor);
	}

	public boolean hasSelection() {
		return caret != anchor;
	}

	/** Types one character over the selection. Returns true if the text changed. */
	public boolean type(int codepoint) {
		if (!allowed(codepoint)) {
			return false;
		}
		return insert(new String(Character.toChars(codepoint)));
	}

	/** Pastes text over the selection: the allowed characters, up to the length limit. Returns true if the text changed. */
	public boolean paste(String pasted) {
		if (pasted == null) {
			return false;
		}
		StringBuilder clean = new StringBuilder();
		pasted.codePoints().filter(TextFieldModel::allowed).forEach(clean::appendCodePoint);
		return insert(clean.toString());
	}

	private boolean insert(String piece) {
		boolean removed = deleteSelection();
		int room = maxLength - text.length();
		if (room <= 0 || piece.isEmpty()) {
			return removed;
		}
		String added = piece.length() > room ? piece.substring(0, room) : piece;
		text.insert(caret, added);
		caret += added.length();
		anchor = caret;
		return true;
	}

	/** Backspace: the selection, or the character (Ctrl: the word) before the caret. */
	public boolean backspace(boolean word) {
		if (deleteSelection()) {
			return true;
		}
		if (caret == 0) {
			return false;
		}
		int from = word ? wordLeft(caret) : caret - 1;
		text.delete(from, caret);
		caret = anchor = from;
		return true;
	}

	/** Delete: the selection, or the character (Ctrl: the word) after the caret. */
	public boolean delete(boolean word) {
		if (deleteSelection()) {
			return true;
		}
		if (caret >= text.length()) {
			return false;
		}
		int to = word ? wordRight(caret) : caret + 1;
		text.delete(caret, to);
		anchor = caret;
		return true;
	}

	private boolean deleteSelection() {
		if (!hasSelection()) {
			return false;
		}
		int start = selectionStart();
		text.delete(start, selectionEnd());
		caret = anchor = start;
		return true;
	}

	/** Left arrow. {@code word}: Ctrl, jump a word. {@code select}: Shift, extend the selection. */
	public void left(boolean word, boolean select) {
		if (!select && hasSelection()) {
			moveTo(selectionStart(), false);
			return;
		}
		moveTo(word ? wordLeft(caret) : Math.max(0, caret - 1), select);
	}

	public void right(boolean word, boolean select) {
		if (!select && hasSelection()) {
			moveTo(selectionEnd(), false);
			return;
		}
		moveTo(word ? wordRight(caret) : Math.min(text.length(), caret + 1), select);
	}

	public void home(boolean select) {
		moveTo(0, select);
	}

	public void end(boolean select) {
		moveTo(text.length(), select);
	}

	public void selectAll() {
		anchor = 0;
		caret = text.length();
	}

	/** Puts the caret at {@code index} (a click), extending the selection with Shift. */
	public void moveTo(int index, boolean select) {
		caret = Math.max(0, Math.min(text.length(), index));
		if (!select) {
			anchor = caret;
		}
	}

	/** The selected text, or "" when nothing is selected. */
	public String selected() {
		return text.substring(selectionStart(), selectionEnd());
	}

	/** Replaces everything, with the caret at the end. */
	public void set(String value) {
		text.setLength(0);
		caret = anchor = 0;
		paste(value);
	}

	private int wordLeft(int from) {
		int i = from;
		while (i > 0 && text.charAt(i - 1) == ' ') {
			i--;
		}
		while (i > 0 && text.charAt(i - 1) != ' ') {
			i--;
		}
		return i;
	}

	private int wordRight(int from) {
		int i = from;
		while (i < text.length() && text.charAt(i) == ' ') {
			i++;
		}
		while (i < text.length() && text.charAt(i) != ' ') {
			i++;
		}
		return i;
	}

	private static boolean allowed(int codepoint) {
		return codepoint >= ' ' && codepoint != 0x7F && codepoint != '§' && !Character.isISOControl(codepoint);
	}
}
