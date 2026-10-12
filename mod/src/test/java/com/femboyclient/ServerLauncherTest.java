package com.femboyclient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drives ServerLauncher.check() by hand with a fake website and a fake clock, so no Python is ever started. */
class ServerLauncherTest {

	@TempDir
	Path dir;

	private FemboyClientConfig config;
	private boolean up;
	private long now;
	private final List<FakeProcess> started = new ArrayList<>();
	private final List<Integer> ports = new ArrayList<>();
	private boolean startFails;
	private ServerLauncher launcher;

	/** A started website that stays alive until told otherwise. */
	private static final class FakeProcess implements ServerLauncher.Handle {
		boolean alive = true;
		int code = 1;

		@Override
		public boolean isAlive() {
			return alive;
		}

		@Override
		public int exitCode() {
			return code;
		}
	}

	@BeforeEach
	void setUp() throws IOException {
		Files.writeString(dir.resolve("app.py"), "");
		Path python = dir.resolve(".venv").resolve("Scripts").resolve("python.exe");
		Files.createDirectories(python.getParent());
		Files.writeString(python, "");

		config = new FemboyClientConfig();
		config.serverUrl = "http://127.0.0.1:8123";
		config.serverDir = dir.toString();

		launcher = new ServerLauncher(() -> config, () -> up, (serverDir, port) -> {
			if (startFails) {
				throw new IOException("no python");
			}
			assertEquals(dir, serverDir);
			ports.add(port);
			FakeProcess process = new FakeProcess();
			started.add(process);
			return process;
		}, () -> now);
	}

	private void after(Duration duration) {
		now += duration.toNanos();
		launcher.check();
	}

	// ------------------------------------------------------------ starting at launch

	@Test
	void startsTheWebsiteAtOnceWhenNothingAnswersAtLaunch() {
		launcher.check();

		assertEquals(1, started.size());
		assertEquals(List.of(8123), ports);
	}

	@Test
	void leavesARunningWebsiteAlone() {
		up = true;
		launcher.check();
		after(ServerLauncher.CHECK_EVERY);

		assertTrue(started.isEmpty());
	}

	@Test
	void waitsForAWebsiteThatIsStillStartingInsteadOfStartingASecond() {
		launcher.check();
		after(ServerLauncher.CHECK_EVERY);
		after(ServerLauncher.CHECK_EVERY);
		after(ServerLauncher.STARTUP_GRACE);    // stuck, but alive: still only one copy

		assertEquals(1, started.size());
	}

	// ------------------------------------------------------------ keeping it running

	@Test
	void startsItAgainWhenTheOneItStartedStops() {
		launcher.check();
		up = true;
		after(ServerLauncher.CHECK_EVERY);
		after(Duration.ofMinutes(5));

		up = false;
		started.get(0).alive = false;
		after(ServerLauncher.CHECK_EVERY);

		assertEquals(2, started.size());
	}

	@Test
	void givesAWebsiteSomeoneElseStartedASecondChanceBeforeStartingOne() {
		up = true;
		launcher.check();

		up = false;                                  // server.bat forever restarting its own
		after(ServerLauncher.CHECK_EVERY);
		assertTrue(started.isEmpty());

		after(ServerLauncher.CHECK_EVERY);           // still down: now it is ours to start
		assertEquals(1, started.size());
	}

	@Test
	void aMissThatRecoversDoesNotCount() {
		up = true;
		launcher.check();
		up = false;
		after(ServerLauncher.CHECK_EVERY);
		up = true;
		after(ServerLauncher.CHECK_EVERY);
		up = false;
		after(ServerLauncher.CHECK_EVERY);

		assertTrue(started.isEmpty());
	}

	// ------------------------------------------------------------ when it keeps failing

	@Test
	void waitsLongerEachTimeTheWebsiteCrashesStraightAway() {
		launcher.check();
		started.get(0).alive = false;
		after(Duration.ofSeconds(1));                // crashed: wait FIRST_BACKOFF
		assertEquals(1, started.size());

		after(ServerLauncher.FIRST_BACKOFF);
		assertEquals(2, started.size());

		started.get(1).alive = false;
		after(Duration.ofSeconds(1));
		after(ServerLauncher.FIRST_BACKOFF);         // the wait has doubled
		assertEquals(2, started.size());
		after(ServerLauncher.FIRST_BACKOFF);
		assertEquals(3, started.size());
	}

	@Test
	void theWaitNeverGrowsPastTheLimit() {
		launcher.check();
		for (int i = 0; i < 12; i++) {
			started.get(started.size() - 1).alive = false;
			after(Duration.ofSeconds(1));
			after(ServerLauncher.MAX_BACKOFF);
		}
		assertEquals(13, started.size());
	}

	@Test
	void aProcessThatCannotBeCreatedIsTriedAgainLater() {
		startFails = true;
		launcher.check();
		after(ServerLauncher.CHECK_EVERY);
		assertTrue(started.isEmpty());

		startFails = false;
		after(ServerLauncher.FIRST_BACKOFF);
		assertEquals(1, started.size());
	}

	@Test
	void neverThrows() {
		ServerLauncher broken = new ServerLauncher(() -> config, () -> {
			throw new IllegalStateException("boom");
		}, (d, p) -> null, () -> 0);
		broken.check();
	}

	// ------------------------------------------------------------ when not to start it

	@Test
	void doesNothingWithoutServerDir() {
		config.serverDir = "";
		launcher.check();

		assertTrue(started.isEmpty());
		assertEquals("serverDir is not set", ServerLauncher.whyNot(config));
	}

	@Test
	void doesNothingWhenTheFolderIsNotTheWebsite() throws IOException {
		Files.delete(dir.resolve("app.py"));
		launcher.check();

		assertTrue(started.isEmpty());
		assertNotNull(ServerLauncher.whyNot(config));
	}

	@Test
	void doesNothingWithoutTheVenv() throws IOException {
		Files.delete(dir.resolve(".venv").resolve("Scripts").resolve("python.exe"));

		assertTrue(ServerLauncher.whyNot(config).contains("python.exe"));
	}

	@Test
	void doesNothingWhenServerUrlIsAnotherPc() {
		config.serverUrl = "http://192.0.2.10:8000";   // TEST-NET-1, never a local address
		launcher.check();

		assertTrue(started.isEmpty());
		assertTrue(ServerLauncher.whyNot(config).contains("another PC"));
	}

	@Test
	void doesNothingWhenTheModIsOff() {
		config.enabled = false;
		launcher.check();

		assertTrue(started.isEmpty());
	}

	@Test
	void startsOnceTheConfigAllowsIt() {
		config.serverDir = "";
		launcher.check();
		config.serverDir = dir.toString();
		after(ServerLauncher.CHECK_EVERY);

		assertEquals(1, started.size());
	}

	// ------------------------------------------------------------ addresses

	@Test
	void recognisesThisPc() {
		assertTrue(ServerLauncher.isThisPc("127.0.0.1"));
		assertTrue(ServerLauncher.isThisPc("localhost"));
		assertTrue(ServerLauncher.isThisPc("::1"));
		assertFalse(ServerLauncher.isThisPc("192.0.2.10"));
	}

	@Test
	void readsHostAndPortFromTheAddress() {
		assertEquals("192.168.1.228", ServerLauncher.host("http://192.168.1.228:8000"));
		assertEquals(8000, ServerLauncher.port("http://192.168.1.228:8000/"));
		assertEquals(9000, ServerLauncher.port(" http://localhost:9000 "));
		assertEquals(8000, ServerLauncher.port("http://localhost"));
		assertEquals(8000, ServerLauncher.port("not a url at all"));
		assertNull(ServerLauncher.host("not a url at all"));
	}

	@Test
	void serverDirIsReadAndWrittenWithTheOtherSettings() {
		java.util.Properties bundled = new java.util.Properties();
		bundled.setProperty("serverDir", "D:/COinView/server");
		java.util.Properties user = new java.util.Properties();

		assertEquals("D:/COinView/server", FemboyClientConfig.fromProperties(bundled, user).serverDir);

		user.setProperty("serverDir", "");
		assertEquals("", FemboyClientConfig.fromProperties(bundled, user).serverDir);
	}
}
