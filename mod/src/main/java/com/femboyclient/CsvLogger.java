package com.femboyclient;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.format.DateTimeFormatter;

/**
 * Appends one line per coin change to a CSV file in the game directory.
 *
 * The file lives at:  &lt;game dir&gt;/femboyclient/coins.csv
 *
 * Rows are written immediately rather than buffered, so nothing is lost if the
 * game crashes. Coin changes happen a few times a minute at most, so the cost
 * of opening the file per row does not matter.
 */
public final class CsvLogger {

	/** Column order. Keep this in sync with the row() method below. */
	private static final String HEADER =
			"session_id,player_uuid,player_name,timestamp,coins,change,cause_label,cause_message";

	/** A double quote, written this way so the source stays free of escapes. */
	private static final String QUOTE = String.valueOf('"');

	private Path file;

	/** Returns the CSV path, creating the folder and header on first use. */
	private Path file() throws IOException {
		if (file != null) {
			return file;
		}

		Path path = FabricLoader.getInstance().getGameDir().resolve("femboyclient").resolve("coins.csv");
		Files.createDirectories(path.getParent());

		if (!Files.exists(path)) {
			Files.writeString(path, HEADER + System.lineSeparator(),
					StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
			FemboyClientMod.LOGGER.info("created {}", path);
		}

		file = path;
		return file;
	}

	/** Appends one row. Failures are logged and then ignored, never thrown at the game. */
	public synchronized void append(CoinRow row) {
		try {
			Files.writeString(file(), toCsv(row) + System.lineSeparator(),
					StandardCharsets.UTF_8, StandardOpenOption.APPEND);
		} catch (IOException e) {
			FemboyClientMod.LOGGER.warn("Could not write CSV row", e);
		}
	}

	/** Builds the comma separated line for one row. */
	static String toCsv(CoinRow row) {
		return String.join(",",
				quote(row.sessionId()),
				quote(row.playerUuid().toString()),
				quote(row.playerName()),
				quote(row.timestamp().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)),
				Long.toString(row.coins()),
				Long.toString(row.change()),
				quote(row.causeLabel()),
				quote(row.causeMessage()));
	}

	/**
	 * Wraps a value in double quotes and doubles any quotes inside it, which is
	 * the standard CSV way of handling commas and quotes in a field. Chat messages
	 * regularly contain commas, e.g. "Sold 192x ... for 278,246 coins!".
	 */
	static String quote(String value) {
		if (value == null) {
			return QUOTE + QUOTE;
		}

		// Strip line breaks so one row can never span two lines.
		// Written as char codes (13 = CR, 10 = LF) to keep the source escape-free.
		String clean = value.replace((char) 13, ' ').replace((char) 10, ' ');

		return QUOTE + clean.replace(QUOTE, QUOTE + QUOTE) + QUOTE;
	}
}
