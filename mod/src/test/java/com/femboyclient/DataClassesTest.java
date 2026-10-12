package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests for the config layering. */
class DataClassesTest {

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
