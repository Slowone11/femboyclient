package com.femboyclient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;

/**
 * The Wiki Bar's calculator: a small recursive descent parser for what SkyBlock players type, never eval or a script engine.
 *
 * <pre>
 * expression := term (('+' | '-') term)*
 * term       := unary (('*' | 'x' | '/' | '%') unary)*
 * unary      := ('-' | '+') unary | power
 * power      := primary ('^' unary)?            right-associative, so 2^3^2 = 2^9 and -2^2 = -4
 * primary    := number suffix? | 'ans' | '(' expression ')'
 * </pre>
 *
 * Numbers may have a decimal point and thousands commas (1,250,000). A suffix multiplies the number before it: k, m, b, t
 * (thousand to trillion), s (a stack, 64) and e (an enchanted item, 160), any case. "x" between two numbers multiplies (10x5).
 * {@code %} is the remainder. Kept free of Minecraft classes so it is tested on its own.
 */
public final class Calculator {

	/** The suffixes and what they multiply by. */
	public static final Map<Character, Double> DEFAULT_SUFFIXES = Map.of(
			'k', 1e3, 'm', 1e6, 'b', 1e9, 't', 1e12,
			's', 64.0, 'e', 160.0);

	/** The answer, or why there is none. {@code error} is null when {@code value} is a number. */
	public record Result(double value, String error) {
		public boolean ok() {
			return error == null;
		}
	}

	private final Map<Character, Double> suffixes;

	public Calculator() {
		this(DEFAULT_SUFFIXES);
	}

	public Calculator(Map<Character, Double> suffixes) {
		this.suffixes = suffixes;
	}

	/** Works out {@code input}. {@code ans} is what "ans" stands for (the last answer). A leading "=" is ignored. */
	public Result evaluate(String input, double ans) {
		String text = input.strip();
		if (text.startsWith("=")) {
			text = text.substring(1);
		}
		try {
			Parser parser = new Parser(text, ans);
			double value = parser.expression();
			parser.skipSpaces();
			if (!parser.atEnd()) {
				throw new CalcException("unexpected \"" + parser.rest() + "\"");
			}
			if (Double.isNaN(value) || Double.isInfinite(value)) {
				throw new CalcException(Double.isNaN(value) ? "not a number" : "too big");
			}
			return new Result(value, null);
		} catch (CalcException e) {
			return new Result(Double.NaN, e.getMessage());
		}
	}

	/**
	 * Whether the bar treats {@code input} as a calculation rather than a wiki search: it starts with "=", or it works out to a
	 * number (a plain number counts).
	 */
	public boolean isCalculation(String input, double ans) {
		String text = input.strip();
		return text.startsWith("=") || (!text.isEmpty() && evaluate(text, ans).ok());
	}

	/**
	 * Whether input that is not a calculation still looks like one gone wrong ("5+", "3*(2"), so the bar shows an error instead of
	 * searching the wiki for it: it starts with "=", or has a digit and an operator.
	 */
	public static boolean looksLikeMath(String input) {
		String text = input.strip();
		if (text.startsWith("=")) {
			return true;
		}
		boolean digit = false, operator = false;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			digit |= c >= '0' && c <= '9';
			operator |= "+-*/^%()".indexOf(c) >= 0;
		}
		return digit && operator;
	}

	// ------------------------------------------------------------ formatting

	/** "50,000,000" or "12.5": grouped, at most 4 decimals, no trailing zeros. */
	public static String format(double value) {
		DecimalFormat format = new DecimalFormat("#,##0.####", DecimalFormatSymbols.getInstance(Locale.ROOT));
		format.setRoundingMode(RoundingMode.HALF_UP);
		String text = format.format(value);
		return text.equals("-0") ? "0" : text;
	}

	/** "50000000", "12.5": no grouping, at most 4 decimals. What Enter copies, so it pastes into a Bazaar or Auction sign. */
	public static String plain(double value) {
		String text = BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
		return text.equals("-0") ? "0" : text;
	}

	/** "50m", "1.25b", "3.2k"; below a thousand the plain number. At most 2 decimals. */
	public static String shorthand(double value) {
		double abs = Math.abs(value);
		String[] units = {"t", "b", "m", "k"};
		double[] sizes = {1e12, 1e9, 1e6, 1e3};
		for (int i = 0; i < units.length; i++) {
			if (abs >= sizes[i]) {
				return trim(value / sizes[i]) + units[i];
			}
		}
		return trim(value);
	}

	private static String trim(double value) {
		String text = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
		return text.equals("-0") ? "0" : text;
	}

	// ------------------------------------------------------------ the parser

	private static final class CalcException extends Exception {
		CalcException(String message) {
			super(message, null, false, false);
		}
	}

	private final class Parser {
		private final String text;
		private final double ans;
		private int at;

		Parser(String text, double ans) {
			this.text = text;
			this.ans = ans;
		}

		double expression() throws CalcException {
			double value = term();
			while (true) {
				if (eat('+')) {
					value += term();
				} else if (eat('-')) {
					value -= term();
				} else {
					return value;
				}
			}
		}

		double term() throws CalcException {
			double value = unary();
			while (true) {
				if (eat('*') || eatTimesX()) {
					value *= unary();
				} else if (eat('/')) {
					double divisor = unary();
					if (divisor == 0) {
						throw new CalcException("division by zero");
					}
					value /= divisor;
				} else if (eat('%')) {
					double divisor = unary();
					if (divisor == 0) {
						throw new CalcException("division by zero");
					}
					value %= divisor;
				} else {
					return value;
				}
			}
		}

		double unary() throws CalcException {
			if (eat('-')) {
				return -unary();
			}
			if (eat('+')) {
				return unary();
			}
			return power();
		}

		double power() throws CalcException {
			double base = primary();
			if (eat('^')) {
				return Math.pow(base, unary());
			}
			return base;
		}

		double primary() throws CalcException {
			skipSpaces();
			if (eat('(')) {
				double value = expression();
				if (!eat(')')) {
					throw new CalcException("missing \")\"");
				}
				return value;
			}
			if (text.regionMatches(true, at, "ans", 0, 3)) {
				at += 3;
				return ans;
			}
			if (at < text.length() && (Character.isDigit(text.charAt(at)) || text.charAt(at) == '.')) {
				return number();
			}
			throw new CalcException(atEnd() ? "unfinished" : "unexpected \"" + rest() + "\"");
		}

		/** Digits, thousands commas, a decimal point, then an optional suffix. */
		double number() throws CalcException {
			StringBuilder digits = new StringBuilder();
			boolean point = false;
			while (at < text.length()) {
				char c = text.charAt(at);
				if (Character.isDigit(c)) {
					digits.append(c);
				} else if (c == '.' && !point) {
					point = true;
					digits.append(c);
				} else if (c == ',' && !point && at + 1 < text.length() && Character.isDigit(text.charAt(at + 1)) && digits.length() > 0) {
					// a thousands comma: skipped
				} else {
					break;
				}
				at++;
			}
			if (digits.isEmpty() || digits.toString().equals(".")) {
				throw new CalcException("bad number");
			}
			double value = Double.parseDouble(digits.toString());
			if (at < text.length()) {
				Double multiplier = suffixes.get(Character.toLowerCase(text.charAt(at)));
				if (multiplier != null && !startsWord(at)) {
					at++;
					value *= multiplier;
				}
			}
			return value;
		}

		/** True if a letter run starts here that is not a lone suffix (so "5 sets" is not "5s" then "ets"). */
		private boolean startsWord(int index) {
			return index + 1 < text.length() && Character.isLetter(text.charAt(index + 1));
		}

		/** "x" as multiply, only between two numbers: 10x5, 3 x 4, 2x(1+1). */
		private boolean eatTimesX() {
			skipSpaces();
			if (at < text.length() && (text.charAt(at) == 'x' || text.charAt(at) == 'X')) {
				int next = at + 1;
				while (next < text.length() && text.charAt(next) == ' ') {
					next++;
				}
				if (next < text.length() && (Character.isDigit(text.charAt(next)) || "(.-".indexOf(text.charAt(next)) >= 0
						|| text.regionMatches(true, next, "ans", 0, 3))) {
					at++;
					return true;
				}
			}
			return false;
		}

		private boolean eat(char c) {
			skipSpaces();
			if (at < text.length() && text.charAt(at) == c) {
				at++;
				return true;
			}
			return false;
		}

		void skipSpaces() {
			while (at < text.length() && Character.isWhitespace(text.charAt(at))) {
				at++;
			}
		}

		boolean atEnd() {
			return at >= text.length();
		}

		String rest() {
			String rest = text.substring(at);
			return rest.length() > 8 ? rest.substring(0, 8) + "…" : rest;
		}
	}
}
