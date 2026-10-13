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
		assertFalse(config.noParticles, "No Particles is off until you switch it on");
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
	void theOldScannerLinesOfAnOlderFileAreIgnoredAndNotWrittenBack() throws IOException {
		FemboyClientConfig config = FemboyClientConfig.fromProperties(new Properties(),
				props("scanner=false\nscannerWorld=Crystal Hollows\nplayerChat=false\nplayerTracker=true\nfullbright=true"));

		assertTrue(config.enabled, "an old scanner line must not switch the mod off");
		assertTrue(config.fullbright, "the rest of the file still loads");
		Properties written = config.toProperties();
		assertFalse(written.containsKey("scanner"));
		assertFalse(written.containsKey("scannerWorld"));
		assertFalse(written.containsKey("playerChat"));
	}

	@Test
	void noParticlesIsReadAndWritten() throws IOException {
		FemboyClientConfig config = FemboyClientConfig.fromProperties(new Properties(), props("noParticles=true"));
		assertTrue(config.noParticles);
		assertEquals("true", config.toProperties().getProperty("noParticles"));
	}

	@Test
	void valuesAreTrimmed() throws IOException {
		FemboyClientConfig config = FemboyClientConfig.fromProperties(new Properties(),
				props("enrollSecret=  padded  \napiKey=\t key \n"));

		assertEquals("padded", config.enrollSecret);
		assertEquals("key", config.apiKey);
	}
}
