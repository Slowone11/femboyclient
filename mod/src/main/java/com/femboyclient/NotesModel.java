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
public final class NotesModel extends SimpleTextModel {

	public static final int MAX_LENGTH = 500;

	public NotesModel() {
		super(MAX_LENGTH);
	}

	/** Any printable, non-control character. A note is free text, so nothing is filtered beyond that. */
	@Override
	protected boolean allowed(int codepoint) {
		return codepoint >= 0x20 && codepoint != 0x7F;
	}
}
