package com.femboyclient;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScannerTest {

	@Test
	void theScannerIsAlwaysOnInEveryWorldUnlessTheModIsOff() {
		FemboyClientConfig config = new FemboyClientConfig();
		assertEquals("ON (all worlds)", Scanner.describe(config));

		config.enabled = false;
		assertEquals("OFF", Scanner.describe(config));
	}
}
