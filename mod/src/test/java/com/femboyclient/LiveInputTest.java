package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveInputTest {

	private static String events(String... items) {
		return "{\"events\":[" + String.join(",", items) + "]}";
	}

	@Test
	void everyKindOfInputIsRead() {
		List<LiveInput.Event> got = LiveInput.parse(events(
				"{\"type\":\"click\",\"x\":0.5,\"y\":0.25,\"button\":1,\"shift\":true}",
				"{\"type\":\"click\",\"x\":0.1,\"y\":0.9}",
				"{\"type\":\"slot\",\"n\":2}",
				"{\"type\":\"scroll\",\"x\":0.3,\"y\":0.4,\"dy\":-1}",
				"{\"type\":\"key\",\"key\":\"Escape\"}",
				"{\"type\":\"text\",\"text\":\"diamond sword\"}"));
		assertEquals(List.of(
				new LiveInput.Click(0.5, 0.25, 1, true),
				new LiveInput.Click(0.1, 0.9, 0, false),
				new LiveInput.Slot(2),
				new LiveInput.Scroll(0.3, 0.4, -1),
				new LiveInput.Key("escape"),
				new LiveInput.Text("diamond sword")), got);
	}

	@Test
	void badInputIsSkippedAndTheRestKept() {
		List<LiveInput.Event> got = LiveInput.parse(events(
				"{\"type\":\"click\",\"x\":2,\"y\":0.5}",                       // off the picture
				"{\"type\":\"click\",\"x\":0.5,\"y\":0.5,\"button\":9}",         // no such button
				"{\"type\":\"slot\",\"n\":10}",
				"{\"type\":\"slot\",\"n\":0}",
				"{\"type\":\"key\",\"key\":\"f4\"}",
				"{\"type\":\"text\",\"text\":\"line\\nbreak\"}",
				"{\"type\":\"text\",\"text\":\"\"}",
				"{\"type\":\"teleport\"}",
				"{\"type\":\"click\"}",                                         // missing fields
				"{\"type\":\"slot\",\"n\":3}"));
		assertEquals(List.of(new LiveInput.Slot(3)), got);
	}

	@Test
	void anythingThatIsNotAnEventListIsNoInput() {
		assertTrue(LiveInput.parse("").isEmpty());
		assertTrue(LiveInput.parse("not json").isEmpty());
		assertTrue(LiveInput.parse("{}").isEmpty());
		assertTrue(LiveInput.parse("{\"events\":\"x\"}").isEmpty());
		assertTrue(LiveInput.parse("[]").isEmpty());
	}

	@Test
	void aFloodIsCut() {
		List<String> many = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			many.add("{\"type\":\"slot\",\"n\":1}");
		}
		assertEquals(40, LiveInput.parse(events(many.toArray(String[]::new))).size());
	}

	@Test
	void aSpotOnThePictureIsAScaledScreenSpot() {
		assertArrayEquals(new double[] {200, 100}, LiveInput.guiPoint(0.5, 0.5, 400, 200));
		assertArrayEquals(new double[] {0, 0}, LiveInput.guiPoint(-1, -1, 400, 200));
		assertArrayEquals(new double[] {400, 200}, LiveInput.guiPoint(3, 3, 400, 200));
	}

	@Test
	void inputIsOnlyAskedForWhileWatchedAndSwitchedOn() {
		FemboyClientConfig config = new FemboyClientConfig();
		config.liveInput = true;
		int[] asked = {0};
		List<List<LiveInput.Event>> ran = new ArrayList<>();
		LiveView view = new LiveView(() -> config, () -> true, (jpeg, state) -> 1, () -> {
			asked[0]++;
			return events("{\"type\":\"slot\",\"n\":4}");
		});

		view.pollInput(ran::add);                                    // nobody is watching yet
		assertEquals(0, asked[0]);

		view.process(new int[16], 4, 4);                             // a picture goes out and the website says the page is watching
		assertTrue(view.watched());
		view.pollInput(ran::add);
		assertEquals(1, asked[0]);
		assertEquals(List.of(List.of(new LiveInput.Slot(4))), ran);

		config.liveInput = false;                                  // switched off in the game: not even asked
		view.pollInput(ran::add);
		assertEquals(1, asked[0]);
	}
}
