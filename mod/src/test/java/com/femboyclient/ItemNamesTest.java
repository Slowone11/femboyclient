package com.femboyclient;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemNamesTest {

	@Test
	void parseCleansSortsAndRemovesDuplicates() {
		String json = "{\"success\":true,\"items\":[{\"name\":\"Hyperion\"},{\"name\":\"§6Aspect of the End\"},{\"name\":\"hyperion\"},"
				+ "{\"id\":\"NO_NAME\"},{\"name\":\"Enchanted Diamond\"}]}";
		assertEquals(List.of("Aspect of the End", "Enchanted Diamond", "Hyperion"), ItemNames.parse(json));
		assertEquals(List.of(), ItemNames.parse("not json"));
	}

	@Test
	void bestMatchesFirst() {
		ItemNames names = new ItemNames();
		names.set(List.of("Hyperion", "Hyper Cleaver", "Aspect of the End", "Aspect of the Dragons", "Enchanted Hay Bale",
				"Shadow Fury", "Necron's Handle"));
		assertEquals(List.of("Hyperion", "Hyper Cleaver"), names.match("hyp", 2));
		assertEquals("Aspect of the End", names.match("aspect of the end", 3).get(0));
		assertEquals(List.of("Aspect of the End", "Aspect of the Dragons"), names.match("aspect", 5));
		assertEquals(List.of("Necron's Handle"), names.match("handle", 5));
		assertTrue(names.match("aotd", 5).contains("Aspect of the Dragons"));
		assertEquals(List.of(), names.match("", 5));
		assertEquals(List.of(), names.match("zz", 5));
	}

	@Test
	void wikiTitlesThatOnlyMentionTheWordDoNotMatch() {
		assertTrue(ItemNames.score("plasma bucket", "plasma") >= 0);
		assertTrue(ItemNames.score("plasmaflux power orb", "plasma") >= 0);
		assertEquals(-1, ItemNames.score("anguish ring", "plasma"));
		assertEquals(-1, ItemNames.score("habanero tactics", "plasma"));
	}

	@Test
	void baseNameDropsReforgesStarsAndPetLevels() {
		ItemNames names = new ItemNames();
		names.set(List.of("Hyperion", "Aspect of the End", "Golden Dragon", "Wise Wither Chestplate"));
		assertEquals("Hyperion", names.baseName("§dHeroic Hyperion §6✪✪✪✪✪§c➎"));
		assertEquals("Aspect of the End", names.baseName("Very Spicy Aspect of the End"));
		assertEquals("Golden Dragon", names.baseName("[Lvl 100] Golden Dragon"));
		assertEquals("Wise Wither Chestplate", names.baseName("Ancient Wise Wither Chestplate ✪"));
		assertEquals("Unknown Thing", names.baseName("§aUnknown Thing ✿"));
	}

	@Test
	void idsMapToNames() {
		ItemNames names = new ItemNames();
		names.setIds(ItemNames.parseIds("{\"items\":[{\"id\":\"HYPERION\",\"name\":\"Hyperion\"},{\"id\":\"X\"}]}"));
		assertEquals("Hyperion", names.nameForId("HYPERION"));
		assertEquals(null, names.nameForId("X"));
		assertEquals(null, names.nameForId(null));
	}

	@Test
	void rarityAndMaterialByName() {
		java.util.Map<String, ItemNames.Info> info = ItemNames.parseInfo("{\"items\":[{\"name\":\"Hyperion\",\"tier\":\"LEGENDARY\","
				+ "\"material\":\"IRON_SWORD\"},{\"name\":\"Odd\",\"tier\":\"VERY_SPECIAL\"}]}");
		ItemNames names = new ItemNames();
		names.setInfo(info);
		assertEquals(new ItemNames.Info("LEGENDARY", "IRON_SWORD"), names.infoFor("HYPERION"));
		assertEquals("VERY SPECIAL", names.infoFor("odd").tier());
		assertEquals(null, names.infoFor("nope"));
	}
}
