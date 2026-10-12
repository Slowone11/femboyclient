package com.femboyclient;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Keeps the mod up to date. A {@link Source} says which jar is the newest: the latest GitHub release of the mod's repository
 * ({@link GitHubReleases}, for a jar built with updateRepo and updateToken), or else the website, which serves the jar built in the mod
 * folder next to it. When the newest jar differs from the one this game loaded, it is downloaded beside it as "&lt;jar&gt;.update" (never
 * as a .jar, so the game cannot load it by mistake) and a small helper process swaps it in after the game has closed. The running jar
 * is locked on Windows while the game runs, which is why the swap has to wait.
 *
 * Nothing is installed that the source did not vouch for: the download must match the SHA-256 the source announced and must be a
 * Femboy Client jar. Always on, no setting turns it off.
 *
 * Kept free of Minecraft classes so it can be tested.
 */
public final class ModUpdater {

	private static final String MOD_ID = "femboyclient";
	private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(MOD_ID);

	/** Asks the website for a path, with this account's API key. Completes normally with a failed Response when it cannot. */
	public interface Fetcher {
		WebClient.Response get(String path);
	}

	/** Where updates come from: which jar is the newest, and its bytes. */
	public interface Source {
		/** The newest jar's SHA-256 as lower-case hex, or null when it cannot be told right now. */
		String latestSha() throws IOException;

		/** The bytes of the jar the last {@link #latestSha()} announced, or null when they cannot be had right now. */
		byte[] download() throws IOException;
	}

	/** The website as the source: /mod/latest.json names the newest jar's SHA-256 and /mod/latest.b64 is the jar itself. */
	public static Source website(Fetcher fetcher) {
		return new Source() {
			@Override
			public String latestSha() {
				WebClient.Response meta = fetcher.get("/mod/latest.json");
				if (meta == null || !meta.ok()) {
					LOGGER.debug("update check: could not reach the website ({})", meta == null ? "no response" : meta.describe());
					return null;
				}
				JsonObject info = JsonParser.parseString(meta.body()).getAsJsonObject();
				return info.get("sha256").getAsString().toLowerCase(java.util.Locale.ROOT);
			}

			@Override
			public byte[] download() {
				WebClient.Response body = fetcher.get("/mod/latest.b64");
				if (body == null || !body.ok()) {
					return null;
				}
				return Base64.getMimeDecoder().decode(body.body().getBytes(StandardCharsets.US_ASCII));
			}
		};
	}

	/** Arranges for the downloaded file to replace the jar once this game has closed. */
	public interface Swapper {
		void swapAfterExit(Path update, Path target) throws IOException;
	}

	private final Path ownJar;
	private final Source source;
	private final Swapper swapper;
	private final Consumer<String> notice;
	private String ownSha;
	private String stagedSha;
	private boolean swapArranged;

	/** Updates from the website. */
	public ModUpdater(Path ownJar, Fetcher fetcher, Swapper swapper, Consumer<String> notice) {
		this(ownJar, website(fetcher), swapper, notice);
	}

	public ModUpdater(Path ownJar, Source source, Swapper swapper, Consumer<String> notice) {
		this.ownJar = ownJar;
		this.source = source;
		this.swapper = swapper;
		this.notice = notice;
	}

	/** True when the given path is a real jar file that can be replaced (not a development run from a folder). */
	public static boolean replaceable(Path jar) {
		return jar != null && Files.isRegularFile(jar) && jar.getFileName().toString().endsWith(".jar");
	}

	private Path updateFile() {
		return ownJar.resolveSibling(ownJar.getFileName() + ".update");
	}

	/** One round: asks what the newest jar is and, if it is not this one, downloads it and arranges the swap. Never throws. */
	public void check() {
		try {
			run();
		} catch (IOException | RuntimeException e) {
			LOGGER.debug("update check failed", e);
		}
	}

	private void run() throws IOException {
		if (!replaceable(ownJar)) {
			return;
		}
		if (ownSha == null) {
			ownSha = sha256(Files.readAllBytes(ownJar));
		}
		if (stagedSha == null && sameAsOwn(updateFile())) {   // a jar staged this run is known to differ: no need to read it again
			Files.deleteIfExists(updateFile());          // left over from an update that is already installed
		}

		String sha = source.latestSha();
		if (sha == null || sha.equals(ownSha) || sha.equals(stagedSha)) {
			return;
		}

		byte[] jar = source.download();
		if (jar == null) {
			return;
		}
		if (!sha256(jar).equals(sha)) {
			LOGGER.warn("update: the download did not match the checksum it was announced with, not installing it");
			return;
		}
		if (!isFemboyClientJar(jar)) {
			LOGGER.warn("update: the download is not a Femboy Client mod jar, not installing it");
			return;
		}

		Path target = updateFile();
		Path partial = target.resolveSibling(target.getFileName() + ".part");
		Files.write(partial, jar);
		Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
		stagedSha = sha;

		if (!swapArranged) {
			swapper.swapAfterExit(target, ownJar);
			swapArranged = true;
		}
		notice.accept("[Update] A new Femboy Client was downloaded. It is installed when you close the game.");
	}

	private boolean sameAsOwn(Path file) {
		try {
			return Files.isRegularFile(file) && sha256(Files.readAllBytes(file)).equals(ownSha);
		} catch (IOException e) {
			return false;
		}
	}

	/** A zip with a fabric.mod.json whose id is femboyclient. */
	static boolean isFemboyClientJar(byte[] jar) {
		try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(jar))) {
			for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
				if (entry.getName().equals("fabric.mod.json")) {
					JsonObject json = JsonParser.parseString(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
					return json.has("id") && json.get("id").getAsString().equals(MOD_ID);
				}
			}
		} catch (IOException | RuntimeException e) {
			return false;
		}
		return false;
	}

	static String sha256(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	// ------------------------------------------------------------ the swap

	/**
	 * Starts a small hidden process that waits for this game to exit and then moves the downloaded file over the jar, retrying for a
	 * minute in case the file is still locked. It is independent of this process, so it also works after a crash.
	 */
	public static Swapper processSwapper() {
		return (update, target) -> {
			long pid = ProcessHandle.current().pid();
			boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
			ProcessBuilder builder;
			if (windows) {
				String script = "try { Wait-Process -Id " + pid + " -ErrorAction SilentlyContinue } catch {}; "
						+ "for ($i = 0; $i -lt 60; $i++) { try { Move-Item -LiteralPath " + quote(update) + " -Destination " + quote(target)
						+ " -Force -ErrorAction Stop; break } catch { Start-Sleep -Seconds 1 } }";
				builder = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", script);
			} else {
				String script = "while kill -0 " + pid + " 2>/dev/null; do sleep 1; done; mv -f '" + update.toString().replace("'", "'\\''")
						+ "' '" + target.toString().replace("'", "'\\''") + "'";
				builder = new ProcessBuilder("sh", "-c", script);
			}
			builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
			builder.start();
		};
	}

	/** A PowerShell single-quoted string. */
	private static String quote(Path path) {
		return "'" + path.toAbsolutePath().toString().replace("'", "''") + "'";
	}
}
