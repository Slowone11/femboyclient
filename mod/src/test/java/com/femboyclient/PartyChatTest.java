package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What Hypixel's party messages say about who is in the party. The important half is
 * what must NOT count: invites, requests, other lobby messages, and players typing a
 * fake party line into public chat.
 */
class PartyChatTest {

	private static PartyChat.Event present(String... names) {
		return new PartyChat.Event(PartyChat.Kind.PRESENT, List.of(names));
	}

	private static PartyChat.Event left(String name) {
		return new PartyChat.Event(PartyChat.Kind.LEFT, List.of(name));
	}

	private static final PartyChat.Event CLEAR = new PartyChat.Event(PartyChat.Kind.CLEAR, List.of());

	// ------------------------------------------------------------ someone is in the party

	@Test
	void aPlayerWhoJoinedIsInTheParty() {
		assertEquals(present("Alice"), PartyChat.parse("[MVP+] Alice joined the party."));
		assertEquals(present("Alice"), PartyChat.parse("Alice joined the party."));
		assertEquals(present("Alice_2"), PartyChat.parse("[VIP+] Alice_2 joined the party."));
		assertEquals(present("Alice"), PartyChat.parse("  [MVP+] Alice joined the party.  "));
	}

	@Test
	void joiningSomeonesPartyPutsTheirLeaderInIt() {
		assertEquals(present("Bob"), PartyChat.parse("You have joined [MVP+] Bob's party!"));
		assertEquals(present("Bob"), PartyChat.parse("You have joined Bob's party!"));
	}

	@Test
	void thePlayersAlreadyThereWhenYouJoinAreListed() {
		assertEquals(present("A1a", "Bob", "Carol"),
				PartyChat.parse("You'll be partying with: [MVP+] A1a, Bob, [VIP+] Carol"));
		assertEquals(present("Bob"), PartyChat.parse("You'll be partying with: Bob"));
	}

	@Test
	void aPartyChatLineProvesTheSpeakerIsInTheParty() {
		assertEquals(present("Alice"), PartyChat.parse("Party > [MVP+] Alice: hello there"));
		assertEquals(present("Alice"), PartyChat.parse("Party > Alice: hello there"));
	}

	@Test
	void thePartyListNamesEveryMember() {
		assertEquals(present("Zed"), PartyChat.parse("Party Leader: [VIP] Zed ●"));
		assertEquals(present("Alice", "Bob"), PartyChat.parse("Party Members: [MVP+] Alice ● Bob ● "));
		assertEquals(present("Mod1"), PartyChat.parse("Party Moderators: [MVP++] Mod1 ●"));
	}

	@Test
	void promotionsAndTransfersNameMembers() {
		assertEquals(present("Alice", "Bob"), PartyChat.parse("[MVP+] Alice has promoted [VIP] Bob to Party Leader"));
		assertEquals(present("Alice", "Bob"), PartyChat.parse("Alice has promoted Bob to Party Moderator"));
		assertEquals(present("Bob", "Alice"), PartyChat.parse("The party was transferred to [VIP] Bob by [MVP+] Alice"));
	}

	// ---------------------------------------------------------------- someone is gone

	@Test
	void aPlayerWhoLeftOrWasRemovedIsGone() {
		assertEquals(left("Alice"), PartyChat.parse("[MVP+] Alice has left the party."));
		assertEquals(left("Alice"), PartyChat.parse("Alice has been removed from the party."));
		assertEquals(left("Alice"), PartyChat.parse("[MVP+] Alice was removed from your party because they disconnected."));
	}

	@Test
	void leavingOrLosingThePartyClearsEveryone() {
		assertEquals(CLEAR, PartyChat.parse("You left the party."));
		assertEquals(CLEAR, PartyChat.parse("You have been kicked from the party by [MVP+] Alice"));
		assertEquals(CLEAR, PartyChat.parse("You have been removed from the party."));
		assertEquals(CLEAR, PartyChat.parse("The party was disbanded because all invites expired and the party was empty."));
		assertEquals(CLEAR, PartyChat.parse("[MVP+] Alice has disbanded the party!"));
		assertEquals(CLEAR, PartyChat.parse("You are not currently in a party."));
	}

	// ---------------------------------------------------- invites and requests never count

	@Test
	void invitesAndRequestsSayNothingAboutWhoIsInTheParty() {
		String[] notMembership = {
				"[MVP+] Alice has invited you to join their party!",
				"Alice has invited you to join their party!",
				"You have 60 seconds to accept. Click here to join!",
				"You invited Bob to your party! They have 60 seconds to accept.",
				"[MVP+] Alice invited [VIP] Bob to the party! They have 60 seconds to accept.",
				"The party invite to Bob has expired.",
				"The party invite from Alice has expired.",
				"Alice has requested to join your party.",
				"Bob is already in a party.",
				"You cannot invite that player since they're not online.",
				"Alice is not in your party.",
				"Party Finder > Alice joined the dungeon group! (Archer Level 40)",
				"Party Members (3)",
				"Party Leader:",
				"-----------------------------------------------------",
				"",
				"   ",
		};
		for (String line : notMembership) {
			assertNull(PartyChat.parse(line), "must not count: \"" + line + "\"");
		}
	}

	@Test
	void nullAndHugeLinesAreIgnored() {
		assertNull(PartyChat.parse(null));
		assertNull(PartyChat.parse("Alice joined the party." + " x".repeat(300)));
	}

	// --------------------------------------------------------- fake lines from other players

	@Test
	void aPlayerTypingAPartyLineInPublicChatIsNotBelieved() {
		String[] faked = {
				"[248] ❤ [MVP+] Eve: Bob joined the party.",
				"[MVP+] Eve: Bob joined the party.",
				"Eve: Bob joined the party.",
				"Eve: You have joined Bob's party!",
				"Eve: You'll be partying with: Bob, Carol",
				"Eve: Party Members: Bob ● Carol ●",
				"Eve: Bob has left the party.",
				"[MVP+] Eve: You left the party.",
				"Eve: The party was disbanded because all invites expired and the party was empty.",
				"From [MVP+] Eve: Bob joined the party.",
				"To Eve: Bob joined the party.",
				"Guild > [MVP+] Eve: Bob joined the party.",
		};
		for (String line : faked) {
			assertNull(PartyChat.parse(line), "must not be believed: \"" + line + "\"");
		}
	}

	@Test
	void aFakeLineInsidePartyChatOnlyProvesTheSpeaker() {
		// Only Eve is known to be in the party. Bob is just text in her message.
		assertEquals(present("Eve"), PartyChat.parse("Party > [MVP+] Eve: Bob joined the party."));
		assertEquals(present("Eve"), PartyChat.parse("Party > Eve: You left the party."));
	}

	@Test
	void youIsNeverAPlayer() {
		assertNull(PartyChat.parse("You joined the party."));
		assertNull(PartyChat.parse("You has left the party."));
	}

	@Test
	void namesThatCannotBeUsernamesAreIgnored() {
		assertNull(PartyChat.parse("[MVP+] Al joined the party."));
		assertNull(PartyChat.parse("[MVP+] AVeryLongNameThatIsTooLong joined the party."));
		assertNull(PartyChat.parse("[MVP+] Bad Name joined the party."));
		assertEquals(present("Good_One"), PartyChat.parse("You'll be partying with: !!, Good_One, x"));
	}

	@Test
	void missingPunctuationOrExtraWordsMeanNoMatch() {
		assertNull(PartyChat.parse("Alice joined the party"));
		assertNull(PartyChat.parse("Alice joined the party. Welcome!"));
		assertNull(PartyChat.parse("Someone told me Alice joined the party."));
	}
}
