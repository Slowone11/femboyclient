package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CalculatorTest {

	private final Calculator calc = new Calculator();

	private double eval(String input) {
		Calculator.Result result = calc.evaluate(input, 0);
		assertTrue(result.ok(), () -> input + " failed: " + result.error());
		return result.value();
	}

	private void fails(String input) {
		Calculator.Result result = calc.evaluate(input, 0);
		assertFalse(result.ok(), () -> input + " should fail but gave " + result.value());
		assertNotNull(result.error());
	}

	@Test
	void basicOperatorsAndPrecedence() {
		assertEquals(7, eval("1 + 2 * 3"));
		assertEquals(9, eval("(1 + 2) * 3"));
		assertEquals(2.5, eval("5/2"));
		assertEquals(1, eval("10 % 3"));
		assertEquals(1, eval("7 - 3 - 3"));
		assertEquals(2, eval("16 / 4 / 2"));
	}

	@Test
	void powerIsRightAssociativeAndBindsTighterThanUnaryMinus() {
		assertEquals(512, eval("2^3^2"));
		assertEquals(-4, eval("-2^2"));
		assertEquals(4, eval("(-2)^2"));
		assertEquals(0.5, eval("2^-1"));
	}

	@Test
	void unaryMinusAndDecimals() {
		assertEquals(-5, eval("-5"));
		assertEquals(5, eval("--5"));
		assertEquals(-1.5, eval("-.5 - 1"));
		assertEquals(3, eval("+3"));
		assertEquals(0.25, eval("0.25"));
	}

	@Test
	void xMultipliesBetweenNumbers() {
		assertEquals(50, eval("10x5"));
		assertEquals(12, eval("3 x 4"));
		assertEquals(4, eval("2x(1+1)"));
		assertEquals(50, eval("10X5"));
	}

	@Test
	void skyBlockSuffixesAnyCase() {
		assertEquals(5_000, eval("5k"));
		assertEquals(50_000_000, eval("50m"));
		assertEquals(1_500_000_000, eval("1.5B"));
		assertEquals(2e12, eval("2t"));
		assertEquals(192, eval("3s"));
		assertEquals(320, eval("2e"));
		assertEquals(12_500_000, eval("50m/4"));
		assertEquals(64 * 160, eval("1s * 1e"));
	}

	@Test
	void suffixesCanBeChanged() {
		Calculator custom = new Calculator(Map.of('s', 16.0));
		assertEquals(32, custom.evaluate("2s", 0).value());
		assertFalse(custom.evaluate("2k", 0).ok());
	}

	@Test
	void thousandsCommas() {
		assertEquals(1_250_000, eval("1,250,000"));
		assertEquals(2_000_000, eval("1,000,000 * 2"));
	}

	@Test
	void ansIsThePreviousAnswer() {
		assertEquals(20, calc.evaluate("ans * 2", 10).value());
		assertEquals(10, calc.evaluate("ANS", 10).value());
		assertEquals(30, calc.evaluate("10xans", 3).value());
	}

	@Test
	void leadingEqualsIsIgnored() {
		assertEquals(4, eval("=2+2"));
		assertEquals(4, eval(" = 4"));
	}

	@Test
	void badInputFails() {
		fails("5+");
		fails("3*(2");
		fails("(");
		fails("");
		fails("hyperion");
		fails("2x");
		fails("1/0");
		fails("5 % 0");
		fails("5 sets");
		fails("answer");
		fails("1..2");
		fails("9^9^9");
	}

	@Test
	void whatCountsAsACalculation() {
		assertTrue(calc.isCalculation("42", 0));
		assertTrue(calc.isCalculation("50m/4", 0));
		assertTrue(calc.isCalculation("=", 0));
		assertTrue(calc.isCalculation("=hello", 0));
		assertFalse(calc.isCalculation("hyperion", 0));
		assertFalse(calc.isCalculation("e", 0));
		assertFalse(calc.isCalculation("   ", 0));
		assertFalse(calc.isCalculation("2 sets", 0));
	}

	@Test
	void looksLikeMathOnlyForBrokenMath() {
		assertTrue(Calculator.looksLikeMath("5+"));
		assertTrue(Calculator.looksLikeMath("(3"));
		assertTrue(Calculator.looksLikeMath("=x"));
		assertFalse(Calculator.looksLikeMath("hyperion"));
		assertFalse(Calculator.looksLikeMath("2 sets"));
		assertFalse(Calculator.looksLikeMath("+"));
	}

	@Test
	void formatting() {
		assertEquals("50,000,000", Calculator.format(5e7));
		assertEquals("12.5", Calculator.format(12.5));
		assertEquals("0.3333", Calculator.format(1 / 3.0));
		assertEquals("-1,000", Calculator.format(-1000));
		assertEquals("0", Calculator.format(-0.0));
	}

	@Test
	void shorthand() {
		assertEquals("50m", Calculator.shorthand(5e7));
		assertEquals("1.25b", Calculator.shorthand(1.25e9));
		assertEquals("3.2k", Calculator.shorthand(3200));
		assertEquals("2t", Calculator.shorthand(2e12));
		assertEquals("999", Calculator.shorthand(999));
		assertEquals("-1.5m", Calculator.shorthand(-1.5e6));
		assertEquals("0.33", Calculator.shorthand(1 / 3.0));
	}

	@Test
	void plainCopyHasNoGrouping() {
		assertEquals("50000000", Calculator.plain(5e7));
		assertEquals("12.5", Calculator.plain(12.5));
		assertEquals("0.3333", Calculator.plain(1 / 3.0));
	}
}
