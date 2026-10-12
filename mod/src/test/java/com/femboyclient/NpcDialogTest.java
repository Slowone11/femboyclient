package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcDialogTest {

	private static NpcDialog.Offer offer(String label, boolean green) {
		return new NpcDialog.Offer(label, green);
	}

	@Test
	void aSelectAnOptionLineIsRecognised() {
		assertTrue(NpcDialog.isDialogLine("Select an option: [What's a berry?]"));
		assertTrue(NpcDialog.isDialogLine("Select an option: [Yes] [No]"));
		assertTrue(NpcDialog.isDialogLine("  Select an option: [Yes] [No] "));
		assertFalse(NpcDialog.isDialogLine(null));
		assertFalse(NpcDialog.isDialogLine(""));
		assertFalse(NpcDialog.isDialogLine("Just some ordinary chat message."));
	}

	@Test
	void anyLineAnNpcSpeaksIsAlsoCheckedForOptions() {
		// The "Select an option:" marker doesn't always lead the line; Hypixel may tag the whole thing as the NPC's own line.
		assertTrue(NpcDialog.isDialogLine("[NPC] Swoop: Select an option: [What's a berry?]"));
		assertTrue(NpcDialog.isDialogLine("[NPC] Bank Teller: Have you ever heard about Sweep? [Yes] [No]"));
		assertTrue(NpcDialog.isDialogLine("[NPC] Swoop: Just talking, no options here."));
	}

	@Test
	void oneOptionIsAlwaysPickedWhateverItsColor() {
		assertEquals("What's a berry?", NpcDialog.pick(List.of(offer("What's a berry?", false))));
		assertEquals("Continue", NpcDialog.pick(List.of(offer("Continue", true))));
	}

	@Test
	void withTwoOrMoreOptionsTheGreenOneIsPicked() {
		assertEquals("Yes", NpcDialog.pick(List.of(offer("Yes", true), offer("No", false))));
		assertEquals("No", NpcDialog.pick(List.of(offer("Yes", false), offer("No", true))));
	}

	@Test
	void withNoGreenOptionNothingIsPicked() {
		assertNull(NpcDialog.pick(List.of(offer("Yes", false), offer("No", false))));
	}

	@Test
	void theFirstGreenOptionWinsIfMoreThanOneSomehowIs() {
		assertEquals("A", NpcDialog.pick(List.of(offer("A", true), offer("B", true), offer("C", false))));
	}

	@Test
	void commandsAndPlainChatAreToldApart() {
		assertTrue(NpcDialog.isCommand("/warp foo"));
		assertFalse(NpcDialog.isCommand("Yes"));
		assertFalse(NpcDialog.isCommand("What's a berry?"));
		assertEquals("warp foo", NpcDialog.stripSlash("/warp foo"));
	}
}
