package com.femboyclient;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Breaks a recipe down to raw materials: every ingredient that has a crafting recipe of its own on the wiki is replaced by what
 * that recipe takes, a few levels deep ("Hyperion" → 8 L.A.S.R.'s Eye and the blade's own parts). An ingredient stays as it is
 * when its page has no recipe for it, when its recipe only unpacks a block (1 block → 9 items), when it is already in the
 * chain (a loop), or when the limits are reached. Pages come from {@code fetch} (the wiki client, cached), one at a time.
 * Kept free of Minecraft classes so it can be tested on its own.
 */
final class RawMaterials {

	static final int MAX_DEPTH = 4, MAX_PAGES = 40;

	private final Function<String, CompletableFuture<WikiDoc>> fetch;
	private final AtomicInteger pages = new AtomicInteger();

	RawMaterials(Function<String, CompletableFuture<WikiDoc>> fetch) {
		this.fetch = fetch;
	}

	/** The raw materials for one craft of {@code recipe}, most needed first. Never fails: a page that fails stays an ingredient. */
	CompletableFuture<Map<String, Long>> resolve(WikiDoc.Recipe recipe) {
		Map<String, Long> raw = new LinkedHashMap<>();
		CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
		String made = "|" + recipe.output().name().toLowerCase(Locale.ROOT) + "|";
		for (Map.Entry<String, Integer> part : recipe.totals().entrySet()) {
			chain = chain.thenCompose(v -> expand(part.getKey(), part.getValue(), 1, made)).thenAccept(found -> merge(raw, found));
		}
		return chain.thenApply(v -> sorted(raw));
	}

	private CompletableFuture<Map<String, Long>> expand(String name, long need, int depth, String chainSoFar) {
		String key = name.toLowerCase(Locale.ROOT);
		if (depth >= MAX_DEPTH || chainSoFar.contains("|" + key + "|") || pages.incrementAndGet() > MAX_PAGES) {
			return CompletableFuture.completedFuture(Map.of(name, need));
		}
		return fetch.apply(name).handle((doc, failure) -> doc).thenCompose(doc -> {
			WikiDoc.Recipe recipe = doc == null ? null : craftingRecipeFor(doc, name);
			if (recipe == null || unpacks(recipe)) {
				return CompletableFuture.completedFuture(Map.of(name, need));
			}
			long crafts = (need + recipe.output().count() - 1) / recipe.output().count();
			Map<String, Long> raw = new LinkedHashMap<>();
			CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
			String deeper = chainSoFar + "|" + key + "|";
			for (Map.Entry<String, Integer> part : recipe.totals().entrySet()) {
				chain = chain.thenCompose(v -> expand(part.getKey(), part.getValue() * crafts, depth + 1, deeper))
						.thenAccept(found -> merge(raw, found));
			}
			return chain.thenApply(v -> raw);
		});
	}

	/** The recipe on a page that makes the page's item (not one of the recipes that use it), or null. */
	static WikiDoc.Recipe craftingRecipeFor(WikiDoc doc, String name) {
		for (WikiDoc.Block block : doc.blocks()) {
			if (block instanceof WikiDoc.Recipe recipe && (recipe.output().name().equalsIgnoreCase(name)
					|| recipe.output().name().equalsIgnoreCase(doc.title()))) {
				return recipe;
			}
		}
		return null;
	}

	/** A recipe that makes more items than it takes: a block unpacked into its items. Following it would go the wrong way. */
	static boolean unpacks(WikiDoc.Recipe recipe) {
		int in = 0;
		for (int count : recipe.totals().values()) {
			in += count;
		}
		return recipe.output().count() > in;
	}

	private static void merge(Map<String, Long> into, Map<String, Long> more) {
		more.forEach((name, count) -> into.merge(name, count, Long::sum));
	}

	private static Map<String, Long> sorted(Map<String, Long> raw) {
		Map<String, Long> out = new LinkedHashMap<>();
		raw.entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
				.forEach(e -> out.put(e.getKey(), e.getValue()));
		return out;
	}
}
