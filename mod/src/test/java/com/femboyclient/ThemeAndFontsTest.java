package com.femboyclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The look the screen was asked for, and that the font files the game loads match the sizes in the theme. */
class ThemeAndFontsTest {

	private static JsonObject fontJson(String name) throws IOException {
		try (InputStream in = ThemeAndFontsTest.class.getResourceAsStream("/assets/femboyclient/font/" + name + ".json")) {
			assertNotNull(in, "font definition " + name + ".json is missing");
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	private static JsonObject ttfProvider(String name) throws IOException {
		JsonArray providers = fontJson(name).getAsJsonArray("providers");
		JsonObject first = providers.get(0).getAsJsonObject();
		assertEquals("ttf", first.get("type").getAsString(), "the first provider of " + name + " is the TrueType font");
		return first;
	}

	@Test
	void thePanelIsTheDarkPlumAtSeventyFivePercentWithATwelvePercentWhiteBorder() {
		assertEquals(0x170D16, Theme.PANEL & 0xFFFFFF);
		assertEquals(0.75, Colors.alpha(Theme.PANEL) / 255.0, 0.005);
		assertEquals(0xFFFFFF, Theme.BORDER & 0xFFFFFF);
		assertEquals(0.12, Colors.alpha(Theme.BORDER) / 255.0, 0.005);
		assertTrue(Theme.PANEL_RADIUS >= 10 && Theme.PANEL_RADIUS <= 12);
	}

	@Test
	void theAccentIsThePinkAndLabelsAreGrayPinkAtSixtyPercent() {
		assertEquals(0xFFFF4FA6, Theme.ACCENT);
		assertEquals(0xFFFFFFFF, Theme.TEXT);
		assertEquals(0.60, Colors.alpha(Theme.TEXT_LABEL) / 255.0, 0.005);
		int red = Theme.TEXT_LABEL >> 16 & 0xFF, green = Theme.TEXT_LABEL >> 8 & 0xFF, blue = Theme.TEXT_LABEL & 0xFF;
		assertTrue(red > green && blue > green, "gray-pink");
	}

	@Test
	void paddingIsBetweenTwelveAndSixteen() {
		assertTrue(Theme.PADDING >= 12 && Theme.PADDING <= 16);
	}

	@Test
	void everyFontHasADefinitionWhoseSizeMatchesTheTheme() throws IOException {
		assertEquals(Theme.FONT_LABEL_SIZE, ttfProvider(Theme.FONT_LABEL).get("size").getAsFloat());
		assertEquals(Theme.FONT_BODY_SIZE, ttfProvider(Theme.FONT_BODY).get("size").getAsFloat());
		assertEquals(Theme.FONT_STRONG_SIZE, ttfProvider(Theme.FONT_STRONG).get("size").getAsFloat());
		assertEquals(Theme.FONT_VALUE_SIZE, ttfProvider(Theme.FONT_VALUE).get("size").getAsFloat());
	}

	@Test
	void theSizesAreRightForTheDashboardLook() {
		assertTrue(Theme.FONT_LABEL_SIZE >= 8 && Theme.FONT_LABEL_SIZE <= 9, "labels are 8-9 px");
		assertTrue(Theme.FONT_VALUE_SIZE >= 14 && Theme.FONT_VALUE_SIZE <= 18, "values are 14-18 px");
	}

	@Test
	void everyFontIsDrawnAtHighResolution() throws IOException {
		for (String name : new String[] {Theme.FONT_LABEL, Theme.FONT_BODY, Theme.FONT_STRONG, Theme.FONT_VALUE}) {
			assertTrue(ttfProvider(name).get("oversample").getAsFloat() >= 4f, name + " is oversampled for sharpness at large GUI scales");
		}
	}

	@Test
	void everyFontFileExistsIsARealFontAndFallsBackToTheGamesGlyphs() throws IOException {
		for (String name : new String[] {Theme.FONT_LABEL, Theme.FONT_BODY, Theme.FONT_STRONG, Theme.FONT_VALUE}) {
			String file = ttfProvider(name).get("file").getAsString();
			assertTrue(file.startsWith(Theme.FONT_NAMESPACE + ":"), file);
			try (InputStream in = ThemeAndFontsTest.class.getResourceAsStream("/assets/femboyclient/font/" + file.substring(file.indexOf(':') + 1))) {
				assertNotNull(in, file + " is missing");
				byte[] head = in.readNBytes(4);
				boolean trueType = head[0] == 0 && head[1] == 1 && head[2] == 0 && head[3] == 0;
				boolean openType = new String(head, StandardCharsets.US_ASCII).equals("OTTO");
				assertTrue(trueType || openType, file + " is not a font file");
			}
			JsonArray providers = fontJson(name).getAsJsonArray("providers");
			assertTrue(providers.size() > 1 && providers.get(1).getAsJsonObject().get("type").getAsString().equals("reference"),
					name + " falls back to the game's own glyphs for symbols the font lacks");
		}
	}

	@Test
	void theFontLicenseTravelsWithTheFont() {
		assertNotNull(ThemeAndFontsTest.class.getResourceAsStream("/assets/femboyclient/font/OFL.txt"));
	}

	@Test
	void everyTextMetricIsPositiveAndTheBaselineIsTheGamesSeven() {
		assertEquals(7, Theme.GLYPH_BASELINE);
		for (float size : new float[] {Theme.FONT_LABEL_SIZE, Theme.FONT_BODY_SIZE, Theme.FONT_STRONG_SIZE, Theme.FONT_VALUE_SIZE}) {
			assertTrue(Math.round(size * Theme.CAP_HEIGHT_RATIO) > 0);
		}
		assertTrue(Theme.BODY_LINE >= Theme.FONT_BODY_SIZE && Theme.LABEL_LINE >= Theme.FONT_LABEL_SIZE);
	}

	@Test
	void theRankBadgeColorsCoverEveryStyle() {
		assertArrayEquals(new int[Ranks.Style.values().length], new int[Theme.RANK_FILL.length]);
	}
}
