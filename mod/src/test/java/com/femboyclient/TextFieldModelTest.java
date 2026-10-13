package com.femboyclient;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextFieldModelTest {

	private final TextFieldModel field = new TextFieldModel(10);

	private void typeAll(String text) {
		text.codePoints().forEach(field::type);
	}

	@Test
	void typingInsertsAtTheCaret() {
		typeAll("helo");
		field.left(false, false);
		field.type('l');
		assertEquals("hello", field.text());
		assertEquals(4, field.caret());
	}

	@Test
	void colorCodesAndControlCharactersAreRefused() {
		assertFalse(field.type('§'));
		assertFalse(field.type('\n'));
		assertTrue(field.type('é'));
		assertEquals("é", field.text());
	}

	@Test
	void lengthLimitAlsoForPaste() {
		field.paste("12345678901234");
		assertEquals("1234567890", field.text());
		assertFalse(field.type('x'));
	}

	@Test
	void shiftSelectsAndTypingReplacesTheSelection() {
		typeAll("abcdef");
		field.left(false, true);
		field.left(false, true);
		assertEquals("ef", field.selected());
		field.type('X');
		assertEquals("abcdX", field.text());
	}

	@Test
	void ctrlBackspaceDeletesAWord() {
		typeAll("50m / ans");
		field.backspace(true);
		assertEquals("50m / ", field.text());
		field.backspace(true);
		assertEquals("50m ", field.text());
	}

	@Test
	void deleteAndWordJumps() {
		typeAll("ab cd ef");
		field.home(false);
		field.right(true, false);
		assertEquals(2, field.caret());
		field.delete(true);
		assertEquals("ab ef", field.text());
	}

	@Test
	void selectAllThenBackspaceClears() {
		typeAll("hyperion");
		field.selectAll();
		assertTrue(field.backspace(false));
		assertTrue(field.isEmpty());
	}

	@Test
	void arrowWithASelectionCollapsesIt() {
		typeAll("abc");
		field.selectAll();
		field.left(false, false);
		assertEquals(0, field.caret());
		assertFalse(field.hasSelection());
	}

	@Test
	void setPutsTheCaretAtTheEnd() {
		field.set("5k");
		assertEquals(2, field.caret());
		assertEquals("5k", field.text());
	}
}
