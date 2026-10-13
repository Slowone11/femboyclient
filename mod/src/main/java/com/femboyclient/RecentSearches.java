package com.femboyclient;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * The Wiki Bar's recent searches: pages opened and calculations copied, newest first, shown under the results. Kept on disk so
 * they survive a restart. Kept free of Minecraft classes (the file is passed in) so it can be tested on its own.
 */
final class RecentSearches {

	static final int MAX = 6;

	/** A page opened ({@code answer} null), or a calculation and its answer. */
	record Entry(String text, String answer) {
		boolean isCalculation() {
			return answer != null;
		}
	}

	private final Path file;
	private List<Entry> entries;

	RecentSearches(Path file) {
		this.file = file;
	}

	List<Entry> entries() {
		if (entries == null) {
			entries = load();
		}
		return List.copyOf(entries);
	}

	void addPage(String title) {
		add(new Entry(title, null));
	}

	void addCalculation(String expression, String answer) {
		add(new Entry(expression, answer));
	}

	private void add(Entry entry) {
		entries();
		entries.removeIf(e -> e.text().equalsIgnoreCase(entry.text()) && e.isCalculation() == entry.isCalculation());
		entries.add(0, entry);
		while (entries.size() > MAX) {
			entries.remove(entries.size() - 1);
		}
		save();
	}

	private List<Entry> load() {
		List<Entry> loaded = new ArrayList<>();
		try {
			if (file != null && Files.exists(file)) {
				for (JsonElement element : JsonParser.parseString(Files.readString(file)).getAsJsonArray()) {
					JsonObject o = element.getAsJsonObject();
					JsonElement answer = o.get("answer");
					loaded.add(new Entry(o.get("text").getAsString(), answer == null || answer.isJsonNull() ? null : answer.getAsString()));
				}
			}
		} catch (IOException | RuntimeException e) {
			// a broken file: start empty
		}
		return loaded;
	}

	private void save() {
		if (file == null) {
			return;
		}
		JsonArray all = new JsonArray();
		for (Entry entry : entries) {
			JsonObject o = new JsonObject();
			o.addProperty("text", entry.text());
			o.addProperty("answer", entry.answer());
			all.add(o);
		}
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(temp, all.toString());
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ignored) {
			// not saved: still remembered until the game closes
		}
	}
}
