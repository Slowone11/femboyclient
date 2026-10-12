package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerAddressTest {

	private static Properties props(String... keysAndValues) {
		Properties out = new Properties();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			out.setProperty(keysAndValues[i], keysAndValues[i + 1]);
		}
		return out;
	}

	@Test
	void theConfiguredAddressComesFirstThenEveryAddressTheJarWasBuiltWith() {
		Properties built = props("serverUrl", "http://192.168.1.228:8000", "serverUrls", "http://100.111.207.10:8000, http://example.test:8000");
		FemboyClientConfig config = FemboyClientConfig.fromProperties(built, props("serverUrl", "http://10.0.0.5:8000"));

		assertEquals(List.of("http://10.0.0.5:8000", "http://192.168.1.228:8000", "http://100.111.207.10:8000", "http://example.test:8000"),
				config.serverUrlCandidates());
	}

	@Test
	void anOldConfigFileNamingTheHomeNetworkStillLeavesTheOtherAddressToTry() {
		Properties built = props("serverUrl", "http://192.168.1.228:8000", "serverUrls", "http://100.111.207.10:8000");
		FemboyClientConfig config = FemboyClientConfig.fromProperties(built, props("serverUrl", "http://192.168.1.228:8000/"));

		assertEquals(List.of("http://192.168.1.228:8000", "http://100.111.207.10:8000"), config.serverUrlCandidates(), "no duplicates, no trailing slash");
	}

	@Test
	void withoutABuiltInListTheConfiguredAddressIsTheOnlyOne() {
		FemboyClientConfig config = FemboyClientConfig.fromProperties(new Properties(), props("serverUrl", "http://a.test:8000"));

		assertEquals(List.of("http://a.test:8000"), config.serverUrlCandidates());
	}

	@Test
	void aBlankEnrollSecretInAnOldFileDoesNotWipeTheOneBuiltIn() {
		FemboyClientConfig config = FemboyClientConfig.fromProperties(props("enrollSecret", "built-in"), props("enrollSecret", "   "));

		assertEquals("built-in", config.enrollSecret);
	}

	@Test
	void theUpdateSourceIsAlwaysTheOneTheJarWasBuiltWith() {
		Properties built = props("updateRepo", "owner/mod", "updateToken", "new-token");
		FemboyClientConfig config = FemboyClientConfig.fromProperties(built, props("updateRepo", "someone/else", "updateToken", "old-token"));

		assertEquals("owner/mod", config.updateRepo);
		assertEquals("new-token", config.updateToken);
		assertTrue(config.updatesFromGitHub());
	}

	@Test
	void aPlayersFileCannotTurnOnGitHubUpdates() {
		FemboyClientConfig config = FemboyClientConfig.fromProperties(new Properties(), props("updateRepo", "owner/mod", "updateToken", "token"));

		assertEquals("", config.updateRepo);
		assertFalse(config.updatesFromGitHub());
	}

	@Test
	void theUpdateTokenIsNeverSavedToThePlayersFile() {
		FemboyClientConfig config = FemboyClientConfig.fromProperties(props("updateRepo", "owner/mod", "updateToken", "token"), new Properties());

		Properties saved = config.toProperties();
		assertFalse(saved.containsKey("updateRepo"));
		assertFalse(saved.containsKey("updateToken"));
	}
}
