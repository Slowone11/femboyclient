package com.femboyclient;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchModelTest {

	private final SearchModel search = new SearchModel();

	private void typeAll(String text) {
		text.codePoints().forEach(search::type);
	}

	@Test
	void typingAddsUsernameCharactersOnly() {
		typeAll("Ab_9");
		assertEquals("Ab_9", search.text());
		assertFalse(search.type(' '));
		assertFalse(search.type('!'));
		assertFalse(search.type('\u00e9'));
		assertFalse(search.type(0x1F600));
		assertEquals("Ab_9", search.text());
	}

	@Test
	void theTextStopsAtSixteenCharacters() {
		typeAll("abcdefghijklmnopqrstuvwxyz");
		assertEquals("abcdefghijklmnop", search.text());
		assertFalse(search.type('z'));
	}

	@Test
	void backspaceRemovesTheLastCharacter() {
		typeAll("abc");
		assertTrue(search.backspace());
		assertEquals("ab", search.text());
		search.backspace();
		search.backspace();
		assertFalse(search.backspace());
		assertTrue(search.isEmpty());
	}

	@Test
	void selectAllThenTypingReplacesEverything() {
		typeAll("abc");
		search.selectAll();
		assertTrue(search.allSelected());
		assertTrue(search.type('x'));
		assertEquals("x", search.text());
		assertFalse(search.allSelected());
	}

	@Test
	void selectAllThenBackspaceClearsEverything() {
		typeAll("abc");
		search.selectAll();
		assertTrue(search.backspace());
		assertTrue(search.isEmpty());
	}

	@Test
	void anEmptyBoxIsNeverSelected() {
		search.selectAll();
		assertFalse(search.allSelected());
	}

	@Test
	void aRejectedCharacterDoesNotReplaceTheSelection() {
		typeAll("abc");
		search.selectAll();
		assertFalse(search.type('!'));
		assertEquals("abc", search.text());
		assertTrue(search.allSelected());
	}

	@Test
	void pasteKeepsOnlyWhatIsAllowedUpToTheLimit() {
		assertTrue(search.paste("  Hello World! 12345678901234567890  "));
		assertEquals("HelloWorld123456", search.text());
		assertFalse(search.paste(null));
		assertFalse(search.paste("!!!"));
	}

	@Test
	void pasteOverASelectionReplacesIt() {
		typeAll("old");
		search.selectAll();
		search.paste("new");
		assertEquals("new", search.text());
	}

	@Test
	void clearAndSetWork() {
		typeAll("abc");
		assertTrue(search.clear());
		assertFalse(search.clear());
		search.set("Steve!");
		assertEquals("Steve", search.text());
	}

	@Test
	void losingFocusDropsTheSelectionButKeepsTheText() {
		typeAll("abc");
		search.selectAll();
		search.clearSelection();
		assertEquals("abc", search.text());
		assertFalse(search.allSelected());
	}
}
