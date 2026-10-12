package com.femboyclient;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScannerTest {

	@Test
	void statusTextMatchesTheSpecification() {
		FemboyClientConfig config = new FemboyClientConfig();
		assertEquals("OFF", Scanner.describe(config));

		config.scanner = true;
		assertEquals("ON (all worlds)", Scanner.describe(config));

		config.scannerWorld = "Crystal Hollows";
		assertEquals("ON (world: Crystal Hollows)", Scanner.describe(config));

		config.scanner = false;
		assertEquals("OFF", Scanner.describe(config));
	}
}
