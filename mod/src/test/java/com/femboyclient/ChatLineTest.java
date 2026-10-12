package com.femboyclient;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** What a chat line says and who said it. Server lines must never be read as a person talking. */
class ChatLineTest {

	private static void says(ChatLine.Channel channel, String sender, String text, String line) {
		ChatLine.Parsed parsed = ChatLine.parse(line);
		assertEquals(new ChatLine.Parsed(channel, sender, text), parsed, line);
	}

	@Test
	void publicChatWithLevelEmblemAndRank() {
		says(ChatLine.Channel.PUBLIC, "TEST", "hello there", "[248] ❤ [MVP+] TEST: hello there");
		says(ChatLine.Channel.PUBLIC, "Alice", "gg", "[91] ✿ [VIP] Alice: gg");
		says(ChatLine.Channel.PUBLIC, "Bob_2", "wts sword 5m", "[120] ⚔ Bob_2: wts sword 5m");
	}

	@Test
	void publicChatWithNoRankOrLevel() {
		says(ChatLine.Channel.PUBLIC, "Alice", "hi", "Alice: hi");
		says(ChatLine.Channel.PUBLIC, "Alice", "hi", "[MVP++] Alice: hi");
	}

	@Test
	void partyGuildCoopAndPrivateChannelsAreTagged() {
		says(ChatLine.Channel.PARTY, "Alice", "ready?", "Party > [MVP+] Alice: ready?");
		says(ChatLine.Channel.GUILD, "Alice", "gg all", "Guild > [VIP] Alice [Member]: gg all");
		says(ChatLine.Channel.GUILD, "Alice", "meeting", "Officer > [VIP] Alice [Officer]: meeting");
		says(ChatLine.Channel.COOP, "Alice", "on my island", "Co-op > [MVP+] Alice: on my island");
		says(ChatLine.Channel.PRIVATE_FROM, "Alice", "psst", "From [MVP+] Alice: psst");
	}

	@Test
	void aPrivateMessageYouSentIsReadWithThePersonYouWroteTo() {
		says(ChatLine.Channel.PRIVATE_TO, "Alice", "hello", "To [MVP+] Alice: hello");
		says(ChatLine.Channel.PRIVATE_TO, "Alice", "hello", "To Alice: hello");
	}

	@Test
	void everyChannelHasTheTypeTheWebsiteStores() {
		assertEquals("PUBLIC", ChatLine.Channel.PUBLIC.type());
		assertEquals("PARTY", ChatLine.Channel.PARTY.type());
		assertEquals("GUILD", ChatLine.Channel.GUILD.type());
		assertEquals("COOP", ChatLine.Channel.COOP.type());
		assertEquals("PRIVATE_FROM", ChatLine.Channel.PRIVATE_FROM.type());
		assertEquals("PRIVATE_TO", ChatLine.Channel.PRIVATE_TO.type());
	}

	@Test
	void serverLinesThatStartWithToOrFromButHaveNoPlayerNameAreStillIgnored() {
		assertNull(ChatLine.parse("To get started, use /help"));
		assertNull(ChatLine.parse("From now on: nothing"));
	}

	@Test
	void theMessageIsEverythingAfterTheFirstColonAndSpace() {
		says(ChatLine.Channel.PUBLIC, "Alice", "Bob: hello there: friend", "[MVP+] Alice: Bob: hello there: friend");
		says(ChatLine.Channel.PUBLIC, "Alice", "Party > [MVP+] Bob: hi", "[MVP+] Alice: Party > [MVP+] Bob: hi");
	}

	@Test
	void textIsTidiedSoItIsEasyToRead() {
		says(ChatLine.Channel.PUBLIC, "Alice", "spaced out text", "[MVP+] Alice:    spaced   out\ttext   ");
		assertEquals("a b", ChatLine.tidy("a \n b"));
	}

	@Test
	void aVeryLongMessageIsCut() {
		ChatLine.Parsed parsed = ChatLine.parse("Alice: " + "x".repeat(400));
		assertEquals(ChatLine.MAX_TEXT, parsed.text().length());
		assertEquals('…', parsed.text().charAt(parsed.text().length() - 1));
	}

	@Test
	void serverLinesWithPlainWordsInFrontAreNotChat() {
		String[] notChat = {
				"Bazaar Sold: 5 items",
				"[Bazaar] Sold: 64x Enchanted Bone",
				"Your Bank Balance: 1,000",
				"Fairy Souls: 12/242",
				"Area: Village",
				"Server: mini28CW",
				"Profile: Mango",
				"You have 60 seconds to accept. Click here: join",
				"Loot Share: 5 coins",
				"Party Members: Alice ● Bob ●",
				"Party Leader: [VIP] Zed ●",
		};
		for (String line : notChat) {
			// Some of these look like "Word: text", so the caller also checks the sender is in the lobby.
			ChatLine.Parsed parsed = ChatLine.parse(line);
			if (parsed != null) {
				assertEquals(ChatLine.Channel.PUBLIC, parsed.channel(), line);
			}
		}
		assertNull(ChatLine.parse("Bazaar Sold: 5 items"), "two plain words before the colon");
		assertNull(ChatLine.parse("Your Bank Balance: 1,000"));
		assertNull(ChatLine.parse("Party Members: Alice ● Bob ●"));
		assertNull(ChatLine.parse("Party Leader: [VIP] Zed ●"));
		assertNull(ChatLine.parse("You have 60 seconds to accept. Click here: join"));
	}

	@Test
	void linesWithNoColonOrNoNameAreNotChat() {
		String[] notChat = {"", "   ", "hello", "[MVP+] Alice joined the party.", ": hello", "[MVP+]: hello", "Al: hi",
				"AVeryLongUsernameThatIsTooLong: hi", "Alice:", "Alice:hello", "Bad Name Here: hi"};
		for (String line : notChat) {
			assertNull(ChatLine.parse(line), "must not be chat: \"" + line + "\"");
		}
		assertNull(ChatLine.parse(null));
	}

	@Test
	void aHugeLineIsIgnored() {
		assertNull(ChatLine.parse("Alice: " + "x".repeat(2000)));
	}

	@Test
	void aMessageOnlyOfSpacesIsNotAMessage() {
		assertNull(ChatLine.parse("Alice:    "));
	}
}
