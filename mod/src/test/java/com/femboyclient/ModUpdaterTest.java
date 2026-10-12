package com.femboyclient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModUpdaterTest {

	@TempDir
	Path folder;

	/** A jar (a zip) whose fabric.mod.json has this id. GitHubReleasesTest uses it too. */
	static byte[] jar(String id, String extra) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			zip.putNextEntry(new ZipEntry("fabric.mod.json"));
			zip.write(("{\"id\":\"" + id + "\"}").getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
			zip.putNextEntry(new ZipEntry("extra.txt"));
			zip.write(extra.getBytes(StandardCharsets.UTF_8));
			zip.closeEntry();
		}
		return bytes.toByteArray();
	}

	/** A website that offers this jar and says this checksum (which a test may get wrong). */
	private static ModUpdater.Fetcher website(byte[] offered, String claimedSha) {
		return path -> {
			if (path.equals("/mod/latest.json")) {
				return new WebClient.Response(200, "{\"name\":\"femboyclient-0.1.0.jar\",\"sha256\":\"" + claimedSha + "\",\"size\":" + offered.length + "}", null);
			}
			return new WebClient.Response(200, Base64.getEncoder().encodeToString(offered), null);
		};
	}

	private final List<String> notices = new ArrayList<>();
	private final List<Path> swaps = new ArrayList<>();

	private ModUpdater updater(Path own, ModUpdater.Fetcher fetcher) {
		return new ModUpdater(own, fetcher, (update, target) -> swaps.add(update), notices::add);
	}

	@Test
	void aNewerJarIsDownloadedBesideTheOldOneAndTheSwapIsArranged() throws IOException {
		Path own = folder.resolve("femboyclient-0.1.0.jar");
		Files.write(own, jar("femboyclient", "old"));
		byte[] newer = jar("femboyclient", "new");

		updater(own, website(newer, ModUpdater.sha256(newer))).check();

		Path staged = folder.resolve("femboyclient-0.1.0.jar.update");
		assertArrayEquals(newer, Files.readAllBytes(staged));
		assertEquals(List.of(staged), swaps);
		assertEquals(1, notices.size());
		assertTrue(notices.get(0).contains("when you close the game"));
		assertFalse(Files.exists(folder.resolve("femboyclient-0.1.0.jar.update.part")));
		assertArrayEquals(jar("femboyclient", "old"), Files.readAllBytes(own));      // the running jar is never touched
	}

	@Test
	void nothingHappensWhenTheJarIsAlreadyTheNewest() throws IOException {
		Path own = folder.resolve("femboyclient-0.1.0.jar");
		byte[] same = jar("femboyclient", "same");
		Files.write(own, same);

		updater(own, website(same, ModUpdater.sha256(same))).check();

		assertTrue(swaps.isEmpty());
		assertTrue(notices.isEmpty());
		assertFalse(Files.exists(folder.resolve("femboyclient-0.1.0.jar.update")));
	}

	@Test
	void aDownloadThatDoesNotMatchTheChecksumIsNotInstalled() throws IOException {
		Path own = folder.resolve("femboyclient-0.1.0.jar");
		Files.write(own, jar("femboyclient", "old"));
		byte[] newer = jar("femboyclient", "new");

		updater(own, website(newer, ModUpdater.sha256(jar("femboyclient", "something else")))).check();

		assertTrue(swaps.isEmpty());
		assertFalse(Files.exists(folder.resolve("femboyclient-0.1.0.jar.update")));
	}

	@Test
	void aJarOfAnotherModIsNotInstalled() throws IOException {
		Path own = folder.resolve("femboyclient-0.1.0.jar");
		Files.write(own, jar("femboyclient", "old"));
		byte[] other = jar("someothermod", "x");

		updater(own, website(other, ModUpdater.sha256(other))).check();

		assertTrue(swaps.isEmpty());
		assertFalse(Files.exists(folder.resolve("femboyclient-0.1.0.jar.update")));
	}

	@Test
	void theSameUpdateIsNotDownloadedOrArrangedTwice() throws IOException {
		Path own = folder.resolve("femboyclient-0.1.0.jar");
		Files.write(own, jar("femboyclient", "old"));
		byte[] newer = jar("femboyclient", "new");
		int[] downloads = {0};
		ModUpdater.Fetcher counting = path -> {
			if (path.endsWith(".b64")) {
				downloads[0]++;
			}
			return website(newer, ModUpdater.sha256(newer)).get(path);
		};
		ModUpdater updater = updater(own, counting);

		updater.check();
		updater.check();

		assertEquals(1, downloads[0]);
		assertEquals(1, swaps.size());
		assertEquals(1, notices.size());
	}

	@Test
	void aNewerUpdateReplacesTheStagedOneWithoutASecondSwapProcess() throws IOException {
		Path own = folder.resolve("femboyclient-0.1.0.jar");
		Files.write(own, jar("femboyclient", "old"));
		byte[] second = jar("femboyclient", "second");
		byte[] third = jar("femboyclient", "third");
		byte[][] offered = {second};
		ModUpdater.Fetcher fetcher = path -> website(offered[0], ModUpdater.sha256(offered[0])).get(path);
		ModUpdater updater = updater(own, fetcher);

		updater.check();
		offered[0] = third;
		updater.check();

		assertArrayEquals(third, Files.readAllBytes(folder.resolve("femboyclient-0.1.0.jar.update")));
		assertEquals(1, swaps.size());
	}

	@Test
	void aWebsiteThatIsDownOrRefusesChangesNothing() throws IOException {
		Path own = folder.resolve("femboyclient-0.1.0.jar");
		Files.write(own, jar("femboyclient", "old"));

		updater(own, path -> new WebClient.Response(-1, "", "connection refused")).check();
		updater(own, path -> new WebClient.Response(404, "{}", null)).check();
		updater(own, path -> new WebClient.Response(200, "not json", null)).check();

		assertTrue(swaps.isEmpty());
		assertTrue(notices.isEmpty());
	}

	@Test
	void anUpdateAlreadyInstalledIsCleanedUp() throws IOException {
		Path own = folder.resolve("femboyclient-0.1.0.jar");
		byte[] same = jar("femboyclient", "same");
		Files.write(own, same);
		Files.write(folder.resolve("femboyclient-0.1.0.jar.update"), same);

		updater(own, website(same, ModUpdater.sha256(same))).check();

		assertFalse(Files.exists(folder.resolve("femboyclient-0.1.0.jar.update")));
	}

	@Test
	void onlyARealJarFileCanBeReplaced() {
		assertFalse(ModUpdater.replaceable(folder));                                 // a folder, as in a development run
		assertFalse(ModUpdater.replaceable(folder.resolve("missing.jar")));
		assertFalse(ModUpdater.replaceable(null));
	}
}
