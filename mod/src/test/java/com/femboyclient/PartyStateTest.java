package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PartyStateTest {

	private static PartyChat.Event present(String... names) {
		return new PartyChat.Event(PartyChat.Kind.PRESENT, List.of(names));
	}

	private static PartyChat.Event left(String name) {
		return new PartyChat.Event(PartyChat.Kind.LEFT, List.of(name));
	}

	private static final PartyChat.Event CLEAR = new PartyChat.Event(PartyChat.Kind.CLEAR, List.of());

	@Test
	void aPlayerIsNewTheFirstTimeTheyAreConfirmed() {
		PartyState party = new PartyState();

		assertEquals(List.of("Alice"), party.apply(present("Alice"), "Me"));
		assertEquals(List.of("Alice"), party.members());
	}

	@Test
	void beingConfirmedAgainWhileStillInThePartyIsNotNew() {
		PartyState party = new PartyState();
		party.apply(present("Alice"), "Me");

		assertEquals(List.of(), party.apply(present("Alice"), "Me"), "spoke in party chat");
		assertEquals(List.of(), party.apply(present("alice"), "Me"), "capitalisation does not matter");
		assertEquals(List.of("Alice"), party.members());
	}

	@Test
	void leavingAndComingBackIsANewTime() {
		PartyState party = new PartyState();
		party.apply(present("Alice"), "Me");
		party.apply(left("alice"), "Me");

		assertEquals(List.of(), party.members());
		assertEquals(List.of("Alice"), party.apply(present("Alice"), "Me"));
	}

	@Test
	void whenYouLeaveEveryoneIsForgottenAndTheNextPartyCountsAgain() {
		PartyState party = new PartyState();
		party.apply(present("Alice", "Bob"), "Me");
		party.apply(CLEAR, "Me");

		assertEquals(List.of(), party.members());
		assertEquals(List.of("Alice", "Bob"), party.apply(present("Alice", "Bob"), "Me"));
	}

	@Test
	void youAreNeverYourOwnPartyMember() {
		PartyState party = new PartyState();

		assertEquals(List.of("Alice"), party.apply(present("Me", "ME", "Alice", "InGameName"), "Me", "InGameName", null));
	}

	@Test
	void everyoneListedTogetherIsReportedInOrder() {
		PartyState party = new PartyState();
		party.apply(present("Bob"), "Me");

		assertEquals(List.of("Alice", "Carol"), party.apply(present("Alice", "Bob", "Carol"), "Me"));
	}

	@Test
	void someoneWhoLeftWithoutBeingKnownChangesNothing() {
		PartyState party = new PartyState();
		party.apply(present("Alice"), "Me");

		party.apply(left("Stranger"), "Me");
		assertEquals(List.of("Alice"), party.members());
	}

	@Test
	void anEventThatIsNullDoesNothing() {
		assertEquals(List.of(), new PartyState().apply(null, "Me"));
	}

	@Test
	void theStateIsCappedSoItCannotGrowWithoutLimit() {
		PartyState party = new PartyState();
		for (int i = 0; i < PartyState.MAX_MEMBERS + 50; i++) {
			party.apply(present("Player" + i), "Me");
		}
		assertEquals(PartyState.MAX_MEMBERS, party.members().size());
	}

	@Test
	void clearingForgetsEveryone() {
		PartyState party = new PartyState();
		party.apply(present("Alice"), "Me");
		party.clear();
		assertEquals(List.of(), party.members());
	}
}
