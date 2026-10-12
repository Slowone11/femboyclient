package com.femboyclient;

import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcAutoAnswerTest {

	/** A fake clock and a fake game: everything sent is recorded, nothing touches Minecraft. */
	private static final class FakeGame implements NpcAutoAnswer.Env {
		long now;
		final List<String> commands = new ArrayList<>();
		final List<String> chats = new ArrayList<>();
		final List<String> said = new ArrayList<>();
		final List<String> customClicks = new ArrayList<>();

		@Override
		public void sendCommand(String command) {
			commands.add(command);
		}

		@Override
		public void sendChat(String text) {
			chats.add(text);
		}

		@Override
		public void sendCustomClick(Identifier id, Optional<Tag> payload) {
			customClicks.add(id + " " + payload);
		}

		@Override
		public void say(String text) {
			said.add(text);
		}

		@Override
		public long nanos() {
			return now;
		}
	}

	private static NpcAutoAnswer.Option option(String label, boolean green, String value) {
		return new NpcAutoAnswer.Option(label, green, new NpcAutoAnswer.Action.Command(value));
	}

	private static NpcAutoAnswer.Option customOption(String label, boolean green, String id, String payload) {
		return new NpcAutoAnswer.Option(label, green,
				new NpcAutoAnswer.Action.Custom(Identifier.parse(id), Optional.of(StringTag.valueOf(payload))));
	}

	private static NpcAutoAnswer answer(FakeGame game, boolean enabled) {
		return new NpcAutoAnswer(game, () -> enabled, new java.util.Random(1));
	}

	@Test
	void aSingleOptionIsSentAfterTheShortDelayWhateverItsColor() {
		FakeGame game = new FakeGame();
		NpcAutoAnswer npc = answer(game, true);

		npc.onOptions(List.of(option("What's a berry?", false, "What's a berry?")), game.now);
		assertTrue(npc.hasPending());
		assertTrue(game.chats.isEmpty(), "not sent before its delay has passed");

		npc.tick(game.now + NpcAutoAnswer.DELAY_MIN_NANOS - 1);
		assertTrue(game.chats.isEmpty());

		npc.tick(game.now + NpcAutoAnswer.DELAY_MAX_NANOS + 1);
		assertEquals(List.of("What's a berry?"), game.chats);
		assertFalse(npc.hasPending());
	}

	@Test
	void aCommandValueIsSentAsACommandWithoutTheSlash() {
		FakeGame game = new FakeGame();
		NpcAutoAnswer npc = answer(game, true);
		npc.onOptions(List.of(option("Warp", false, "/warp foo")), game.now);
		npc.tick(game.now + NpcAutoAnswer.DELAY_MAX_NANOS + 1);
		assertEquals(List.of("warp foo"), game.commands);
		assertTrue(game.chats.isEmpty());
	}

	@Test
	void aCustomClickIsSentBackWithItsIdAndPayload() {
		// This is what Hypixel's own dialog options actually use: a structured click, not a plain command.
		FakeGame game = new FakeGame();
		NpcAutoAnswer npc = answer(game, true);
		npc.onOptions(List.of(customOption("Hunting?", false, "skyblock:dialogue_response", "x_2")), game.now);
		npc.tick(game.now + NpcAutoAnswer.DELAY_MAX_NANOS + 1);
		assertEquals(1, game.customClicks.size());
		assertTrue(game.customClicks.get(0).contains("skyblock:dialogue_response"), game.customClicks.get(0));
		assertTrue(game.customClicks.get(0).contains("x_2"), game.customClicks.get(0));
		assertTrue(game.chats.isEmpty());
		assertTrue(game.commands.isEmpty());
	}

	@Test
	void withTwoOptionsTheGreenOneIsClicked() {
		FakeGame game = new FakeGame();
		NpcAutoAnswer npc = answer(game, true);
		npc.onOptions(List.of(option("Yes", true, "Yes"), option("No", false, "No")), game.now);
		npc.tick(game.now + NpcAutoAnswer.DELAY_MAX_NANOS + 1);
		assertEquals(List.of("Yes"), game.chats);
	}

	@Test
	void theOtherGreenExampleAlsoWorks() {
		FakeGame game = new FakeGame();
		NpcAutoAnswer npc = answer(game, true);
		npc.onOptions(List.of(option("Yes", false, "Yes"), option("No", true, "No")), game.now);
		npc.tick(game.now + NpcAutoAnswer.DELAY_MAX_NANOS + 1);
		assertEquals(List.of("No"), game.chats);
	}

	@Test
	void withNoGreenOptionNothingIsSentAndAHintIsPrinted() {
		FakeGame game = new FakeGame();
		NpcAutoAnswer npc = answer(game, true);

		npc.onOptions(List.of(option("Yes", false, "Yes"), option("No", false, "No")), game.now);
		assertFalse(npc.hasPending());
		npc.tick(game.now + NpcAutoAnswer.DELAY_MAX_NANOS + 1);
		assertTrue(game.chats.isEmpty());
		assertTrue(game.commands.isEmpty());
		assertEquals(1, game.said.size());
		assertTrue(game.said.get(0).contains("Yes"), game.said.get(0));
		assertTrue(game.said.get(0).contains("No"), game.said.get(0));
	}

	@Test
	void whenSwitchedOffNothingIsSentAtAll() {
		FakeGame game = new FakeGame();
		NpcAutoAnswer npc = answer(game, false);
		npc.onOptions(List.of(option("What's a berry?", false, "What's a berry?")), game.now);
		assertFalse(npc.hasPending());
		npc.tick(game.now + NpcAutoAnswer.DELAY_MAX_NANOS + 1);
		assertTrue(game.chats.isEmpty());
		assertTrue(game.said.isEmpty());
	}

	@Test
	void turningItOffAfterADecisionButBeforeItFiresStillStopsIt() {
		FakeGame game = new FakeGame();
		boolean[] enabled = {true};
		NpcAutoAnswer npc = new NpcAutoAnswer(game, () -> enabled[0], new java.util.Random(1));
		npc.onOptions(List.of(option("What's a berry?", false, "What's a berry?")), game.now);
		enabled[0] = false;
		npc.tick(game.now + NpcAutoAnswer.DELAY_MAX_NANOS + 1);
		assertTrue(game.chats.isEmpty());
	}

	@Test
	void anEmptyOptionListDoesNothing() {
		FakeGame game = new FakeGame();
		NpcAutoAnswer npc = answer(game, true);
		npc.onOptions(List.of(), game.now);
		assertFalse(npc.hasPending());
		assertTrue(game.said.isEmpty());
	}
}
