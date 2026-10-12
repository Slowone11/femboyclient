package com.femboyclient.gametest;

import com.femboyclient.FemboyClientConfig;
import com.femboyclient.LiveView;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Takes the live view's picture of a real (test) game with the real game call, and saves the JPEG so its colours and size can be
 * looked at. Development only: {@code gradlew runClientGametest}. Set -Dlive.out=path to choose where the picture goes.
 */
public final class LiveViewGameTest implements FabricClientGameTest {

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext world = context.worldBuilder().create()) {
			context.waitTicks(60);
			FemboyClientConfig.get().enabled = true;

			AtomicReference<byte[]> got = new AtomicReference<>();
			LiveView view = new LiveView(FemboyClientConfig::get, () -> true, (jpeg, state) -> {
				got.set(jpeg); System.out.println("GAMETEST_LIVE state " + state);
				return 0;
			});

			for (int i = 0; i < 100 && got.get() == null; i++) {
				context.runOnClient(view::tick);
				context.waitTicks(2);
			}
			byte[] jpeg = got.get();
			if (jpeg == null) {
				throw new AssertionError("the live view never produced a picture");
			}
			Path out = Path.of(System.getProperty("live.out", "live-test.jpg"));
			try {
				Files.write(out, jpeg);
			} catch (java.io.IOException e) {
				throw new AssertionError(e);
			}
			System.out.println("GAMETEST_LIVE wrote " + jpeg.length + " bytes to " + out.toAbsolutePath());
			view.stop();
		}
	}
}
