package com.femboyclient;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.time.OffsetDateTime;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the small data-shaping classes: CoinRow, CsvLogger and the config layering. */
class DataClassesTest {

	private static final UUID PLAYER = UUID.fromString("1adf8788-e917-4675-b758-defaaee07477");

	// ----------------------------------------------------------------- CoinRow

	private static CoinRow row(String message) {
		return new CoinRow("s1", PLAYER, "Steve", OffsetDateTime.parse("2026-09-20T23:28:57.9105041-04:00"),
				5064840, 275116, "bazaar", message);
	}

	@Test
	void aRowBecomesTheJsonTheServerExpects() {
		JsonObject json = row("[Bazaar] Sold 192x Thing for 278,246 coins!").toJson();

		assertEquals("s1", json.get("session_id").getAsString());
		assertEquals(PLAYER.toString(), json.get("player_uuid").getAsString());
		assertEquals("Steve", json.get("player_name").getAsString());
		assertEquals("2026-09-20T23:28:57.9105041-04:00", json.get("timestamp").getAsString());
		assertEquals(5064840, json.get("coins").getAsLong());
		assertEquals(275116, json.get("change").getAsLong());
		assertEquals("bazaar", json.get("cause_label").getAsString());
	}

	@Test
	void quotesInsideMessagesSurviveJson() {
		String message = "Bought 64x \"Enchanted\" Bone for 56,026 coins!";
		assertEquals(message, row(message).toJson().get("cause_message").getAsString());
	}

	@Test
	void aNullMessageBecomesEmpty() {
		assertEquals("", row(null).toJson().get("cause_message").getAsString());
	}

	// --------------------------------------------------------------- CsvLogger

	@Test
	void csvQuotesCommasQuotesAndFlattensLineBreaks() {
		String q = "\"";
		assertEquals(q + "Sold 192x for 278,246 coins!" + q, CsvLogger.quote("Sold 192x for 278,246 coins!"));
		assertEquals(q + "say " + q + q + "hi" + q + q + q, CsvLogger.quote("say " + q + "hi" + q));
		assertEquals(q + q, CsvLogger.quote(null));
		assertEquals(q + "a b c" + q, CsvLogger.quote("a" + (char) 10 + "b" + (char) 13 + "c"));
	}

	@Test
	void aCsvRowHasEightFieldsEvenWithCommasInTheMessage() {
		String line = CsvLogger.toCsv(row("Sold 192x Enchanted Magma Cream for 278,246 coins!"));

		int fields = 1;
		boolean inQuotes = false;
		for (char c : line.toCharArray()) {
			if (c == '"') {
				inQuotes = !inQuotes;
			} else if (c == ',' && !inQuotes) {
				fields++;
			}
		}
		assertEquals(8, fields);
		assertTrue(line.startsWith("\"s1\",\"" + PLAYER + "\",\"Steve\",\"2026-09-20T23:28:57.9105041-04:00\",5064840,275116,"));
	}

	// ------------------------------------------------------- config layering

	private static Properties props(String text) throws IOException {
		Properties p = new Properties();
		p.load(new StringReader(text));
		return p;
	}

	@Test
	void defaultsApplyWhenNothingElseIsSet() throws IOException {
		FemboyClientConfig config = FemboyClientConfig.fromProperties(new Properties(), new Properties());

		assertTrue(config.enabled);
		assertTrue(config.chatLogging);
		assertFalse(config.includePlayerChat, "player chat must be off by default");
		assertFalse(config.scanner, "the scanner is OFF until you switch it on");
		assertEquals("", config.scannerWorld, "and covers every world unless one is chosen");
		assertEquals("http://127.0.0.1:8000", config.serverUrl);
		assertEquals("", config.enrollSecret);
		assertEquals(5, config.uploadIntervalSeconds);
	}

	@Test
	void theBundledDefaultsLetACopiedJarLinkItselfButThePlayersFileWins() throws IOException {
		Properties bundled = props("serverUrl=http://192.168.1.228:8000\nenrollSecret=abc");

		FemboyClientConfig fresh = FemboyClientConfig.fromProperties(bundled, new Properties());
		assertEquals("http://192.168.1.228:8000", fresh.serverUrl);
		assertEquals("abc", fresh.enrollSecret);

		FemboyClientConfig edited = FemboyClientConfig.fromProperties(bundled, props("serverUrl=http://10.0.0.5:9000"));
		assertEquals("http://10.0.0.5:9000", edited.serverUrl);
		assertEquals("abc", edited.enrollSecret, "a setting missing from the file keeps the bundled value");
	}

	@Test
	void badValuesFallBackInsteadOfBreakingTheMod() throws IOException {
		FemboyClientConfig config = FemboyClientConfig.fromProperties(new Properties(),
				props("serverUrl=   \nuploadIntervalSeconds=soon\nenabled=maybe"));

		assertEquals("http://127.0.0.1:8000", config.serverUrl, "a blank address keeps the default");
		assertEquals(5, config.uploadIntervalSeconds, "a non-number keeps the default");
		assertFalse(config.enabled, "anything that is not 'true' means false");
	}

	@Test
	void theScannerSettingsAreReadFromTheConfigFile() throws IOException {
		FemboyClientConfig config = FemboyClientConfig.fromProperties(new Properties(),
				props("scanner=true\nscannerWorld=Crystal Hollows"));

		assertTrue(config.scanner);
		assertEquals("Crystal Hollows", config.scannerWorld);
		assertTrue(config.enabled, "the scanner setting must not affect coin tracking");
	}

	@Test
	void anOldPlayerTrackerLineIsIgnoredSoTheScannerStaysOff() throws IOException {
		FemboyClientConfig config = FemboyClientConfig.fromProperties(new Properties(), props("playerTracker=true"));
		assertFalse(config.scanner);
	}

	@Test
	void scannerAllowsFollowsTheSetting() {
		FemboyClientConfig config = new FemboyClientConfig();
		assertFalse(config.scannerAllows("Hub"), "off scans nothing");

		config.scanner = true;
		assertTrue(config.scannerAllows("Hub"));
		assertTrue(config.scannerAllows("Unknown"), "every world includes an unknown one");

		config.scannerWorld = "Crystal Hollows";
		assertTrue(config.scannerAllows("Crystal Hollows"));
		assertTrue(config.scannerAllows("crystal hollows"), "ignoring case");
		assertFalse(config.scannerAllows("Hub"));
		assertFalse(config.scannerAllows("Unknown"), "an unknown world never matches a chosen one");
	}

	@Test
	void valuesAreTrimmed() throws IOException {
		FemboyClientConfig config = FemboyClientConfig.fromProperties(new Properties(),
				props("enrollSecret=  padded  \napiKey=\t key \n"));

		assertEquals("padded", config.enrollSecret);
		assertEquals("key", config.apiKey);
	}
}
