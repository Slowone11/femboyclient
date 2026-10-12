package com.femboyclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CauseClassifierTest {

	private static ChatMessage msg(String text) {
		return new ChatMessage(Instant.now(), text);
	}

	private static CauseClassifier.Result classify(String... texts) {
		List<ChatMessage> list = new ArrayList<>();
		for (String text : texts) {
			list.add(msg(text));
		}
		return CauseClassifier.classify(list);
	}

	@ParameterizedTest(name = "{1} <- {0}")
	@CsvSource(delimiter = '|', value = {
			"[Bazaar] Bought 64x Enchanted Blaze Powder for 158,342 coins!|bazaar",
			"[Bazaar] Sold 128x Enchanted Blaze Powder for 261,248 coins!|bazaar",
			"[Bazaar] Claimed 64x Gold Ingot from your sell offer!|bazaar",
			"You sold 5x Mithril Ore for 500 coins!|sell",
			"You bought 1x Diamond Pickaxe|buy",
			"Auction sold for 1,000,000 coins!|auction",
			"You collected 5,000 coins from the auction house|auction",
			"Welcome to Hypixel SkyBlock!|unknown",
			"You have 27 items stashed away!|unknown"
	})
	void labelsRealMessages(String message, String expected) {
		assertEquals(expected, classify(message).label());
	}

	@Test
	void theNewestMatchingMessageIsBlamed() {
		CauseClassifier.Result result = classify(
				"[Bazaar] Bought 64x Gold for 1,000 coins!",
				"You sold 5x Mithril Ore for 500 coins!");
		assertEquals("sell", result.label());
	}

	// The two misattributions found in the first real test run.

	@Test
	void theRecurringBestiaryNagIsNotBlamedForAChange() {
		CauseClassifier.Result result = classify("You have 12 pending Bestiary Milestones to be claimed!");
		assertEquals("unknown", result.label());
		assertEquals("", result.message());
	}

	@Test
	void bazaarStatusChatterIsSkippedInFavourOfTheRealResult() {
		CauseClassifier.Result result = classify(
				"[Bazaar] Bought 64x Enchanted Magma Cream for 126,765 coins!",
				"[Bazaar] Executing instant sell...");
		assertEquals("bazaar", result.label());
		assertEquals("[Bazaar] Bought 64x Enchanted Magma Cream for 126,765 coins!", result.message());
	}

	@Test
	void aWindowOfOnlyNoiseGivesAnEmptyMessage() {
		CauseClassifier.Result result = classify("Welcome to Hypixel SkyBlock!", "You have 27 items stashed away!");
		assertEquals("unknown", result.label());
		assertEquals("", result.message());
	}

	@Test
	void anEmptyWindowIsUnknown() {
		assertEquals("unknown", classify().label());
	}

	@Test
	void unmatchedButRealMessagesKeepTheirTextAsContext() {
		CauseClassifier.Result result = classify("Something new Hypixel added happened");
		assertEquals("unknown", result.label());
		assertEquals("Something new Hypixel added happened", result.message());
	}

	@Test
	void wordsContainingBinAreNotMistakenForAnAuction() {
		assertEquals("unknown", classify("You entered the Cabin of Wonders").label());
	}
}
