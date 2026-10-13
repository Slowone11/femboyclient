package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RawMaterialsTest {

	private static WikiDoc.Slot slot(String name, int count) {
		return new WikiDoc.Slot(name, null, name, count);
	}

	/** A recipe: {@code fill} in the given grid slots, {@code made} out. */
	private static WikiDoc.Recipe recipe(WikiDoc.Slot made, WikiDoc.Slot fill, int... slots) {
		List<WikiDoc.Slot> grid = new ArrayList<>(Collections.nCopies(9, null));
		for (int i : slots) {
			grid.set(i, fill);
		}
		return new WikiDoc.Recipe(Collections.unmodifiableList(grid), made);
	}

	private static WikiDoc page(String title, WikiDoc.Recipe... recipes) {
		return new WikiDoc(title, null, null, List.of(recipes), 0);
	}

	private final Map<String, WikiDoc> wiki = new HashMap<>();
	private final List<String> fetched = new ArrayList<>();

	private RawMaterials raw() {
		return new RawMaterials(name -> {
			fetched.add(name);
			WikiDoc doc = wiki.get(name);
			return doc == null ? CompletableFuture.failedFuture(new RuntimeException("no page")) : CompletableFuture.completedFuture(doc);
		});
	}

	@Test
	void ingredientsWithRecipesAreBrokenDown() {
		// Enchanted Diamond: 5 slots of 32 Diamond. Diamond has no recipe of its own.
		wiki.put("Enchanted Diamond", page("Enchanted Diamond", recipe(slot("Enchanted Diamond", 1), slot("Diamond", 32), 1, 3, 4, 5, 7)));
		wiki.put("Diamond", page("Diamond"));
		WikiDoc.Recipe sword = recipe(slot("Sword", 1), slot("Enchanted Diamond", 1), 1, 4);
		Map<String, Long> result = raw().resolve(sword).join();
		assertEquals(Map.of("Diamond", 320L), result);
	}

	@Test
	void unpackingRecipesAndMissingPagesStopTheBreakdown() {
		// Diamond's page has "1 Block of Diamond -> 9 Diamond": following it would go the wrong way.
		wiki.put("Diamond", page("Diamond", recipe(slot("Diamond", 9), slot("Block of Diamond", 1), 4)));
		WikiDoc.Recipe thing = recipe(slot("Thing", 1), slot("Diamond", 2), 0);
		WikiDoc.Recipe other = new WikiDoc.Recipe(Collections.unmodifiableList(Arrays.asList(slot("Diamond", 2), slot("Mystery", 1), null, null,
				null, null, null, null, null)), slot("Thing", 1));
		assertEquals(Map.of("Diamond", 2L), raw().resolve(thing).join());
		assertEquals(Map.of("Diamond", 2L, "Mystery", 1L), raw().resolve(other).join());
	}

	@Test
	void outputsOfMoreThanOneAreRoundedUpPerCraft() {
		// 1 Wood -> 4 Planks; the parent needs 6 Planks: 2 crafts, so 2 Wood.
		wiki.put("Planks", page("Planks", recipe(slot("Planks", 4), slot("Wood", 1), 0, 1, 2, 3, 4)));
		wiki.put("Wood", page("Wood"));
		WikiDoc.Recipe table = recipe(slot("Table", 1), slot("Planks", 1), 0, 1, 2, 3, 4, 5);
		assertEquals(Map.of("Wood", 10L), raw().resolve(table).join());     // 2 crafts take 5 Wood each
	}

	@Test
	void loopsStop() {
		wiki.put("A", page("A", recipe(slot("A", 1), slot("B", 1), 0)));
		wiki.put("B", page("B", recipe(slot("B", 1), slot("A", 1), 0)));
		assertEquals(Map.of("A", 1L), raw().resolve(recipe(slot("Top", 1), slot("A", 1), 0)).join());
	}

	@Test
	void theRecipeForThePageItemIsTheOneThatMakesIt() {
		WikiDoc.Recipe usage = recipe(slot("Astraea", 1), slot("Hyperion", 1), 4);
		WikiDoc.Recipe making = recipe(slot("Hyperion", 1), slot("Eye", 1), 0);
		assertEquals(making, RawMaterials.craftingRecipeFor(page("Hyperion", usage, making), "Hyperion"));
		assertNull(RawMaterials.craftingRecipeFor(page("Hyperion", usage), "Hyperion"));
	}

	@Test
	void placementHints() {
		assertEquals(List.of("All 9: Diamond"), recipe(slot("X", 1), slot("Diamond", 1), 0, 1, 2, 3, 4, 5, 6, 7, 8).placementHint());
		List<WikiDoc.Slot> ring = new ArrayList<>(Collections.nCopies(9, slot("Eye", 1)));
		ring.set(4, slot("Blade", 1));
		assertEquals(List.of("Center: Blade", "Ring: 8 Eye"), new WikiDoc.Recipe(Collections.unmodifiableList(ring), slot("Hyperion", 1)).placementHint());
		assertEquals(List.of("Any slot: Plasma"), recipe(slot("X", 1), slot("Plasma", 1), 6).placementHint());
		assertEquals(List.of(), recipe(slot("X", 1), slot("Stick", 1), 1, 4).placementHint());
	}
}
