package com.femboyclient;

/**
 * The text typed into the screen's search box: the editing rules without any drawing, so the screen can use its own font for it.
 * Only what a Minecraft username can hold is accepted (letters, digits, underscore, at most 16), which also keeps pasted text safe.
 * Kept free of Minecraft classes so it can be tested on its own.
 */
public final class SearchModel extends SimpleTextModel {

	public static final int MAX_LENGTH = 16;

	public SearchModel() {
		super(MAX_LENGTH);
	}

	@Override
	protected boolean allowed(int codepoint) {
		return codepoint == '_' || (codepoint >= '0' && codepoint <= '9') || (codepoint >= 'a' && codepoint <= 'z')
				|| (codepoint >= 'A' && codepoint <= 'Z');
	}
}
