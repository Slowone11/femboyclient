package com.femboyclient;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The wiki's pictures (the infobox picture, the items in a recipe), downloaded in the background by {@link WikiClient} and
 * made into textures the first time they are drawn. Textures are freed when the screen closes ({@link #releaseTextures}); the
 * downloaded bytes stay (the most recent {@link #MAX_KEPT}) so the next screen gets them back at once. Game thread only.
 */
final class WikiImages {

	private static final int MAX_KEPT = 128;

	private static final class Entry {
		byte[] bytes;
		boolean failed;
		Identifier id;
		int width, height;
	}

	private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>(32, 0.75f, true);
	private static int nextId;

	private WikiImages() {
	}

	/** Starts downloading a picture, once. */
	static void request(String url) {
		if (url == null || ENTRIES.containsKey(url)) {
			return;
		}
		Entry entry = new Entry();
		ENTRIES.put(url, entry);
		trim();
		Minecraft mc = Minecraft.getInstance();
		WikiPanel.client().image(url).whenComplete((bytes, failure) -> mc.execute(() -> {
			entry.bytes = bytes;
			entry.failed = bytes == null;
		}));
	}

	/** True once the picture has arrived (or false while it loads, or when it failed). */
	static boolean ready(String url) {
		Entry entry = url == null ? null : ENTRIES.get(url);
		return entry != null && entry.bytes != null;
	}

	/**
	 * Draws a picture fitted into a {@code size} square, pixel-sharp (the wiki's pictures are pixel art). Asks for it if it was
	 * never asked for. Returns false while it is not there yet.
	 */
	static boolean draw(GuiGraphicsExtractor g, String url, int x, int y, int size) {
		if (url == null) {
			return false;
		}
		request(url);
		Entry entry = ENTRIES.get(url);
		if (entry == null || entry.bytes == null || !texture(entry)) {
			return false;
		}
		int w = entry.width >= entry.height ? size : Math.max(1, size * entry.width / entry.height);
		int h = entry.height >= entry.width ? size : Math.max(1, size * entry.height / entry.width);
		g.blit(RenderPipelines.GUI_TEXTURED, entry.id, x + (size - w) / 2, y + (size - h) / 2, 0, 0, w, h, entry.width, entry.height,
				entry.width, entry.height);
		return true;
	}

	private static boolean texture(Entry entry) {
		if (entry.id != null) {
			return true;
		}
		try {
			NativeImage image = NativeImage.read(entry.bytes);
			entry.width = image.getWidth();
			entry.height = image.getHeight();
			entry.id = Identifier.fromNamespaceAndPath(FemboyClientMod.MOD_ID, "wiki/picture_" + nextId++);
			Minecraft.getInstance().getTextureManager().register(entry.id, new DynamicTexture(() -> "femboyclient wiki picture", image));
			return true;
		} catch (Exception e) {
			entry.bytes = null;                     // not a picture the game can read: do without
			entry.failed = true;
			return false;
		}
	}

	/** Frees every texture. The bytes stay for next time. */
	static void releaseTextures() {
		for (Entry entry : ENTRIES.values()) {
			release(entry);
		}
	}

	private static void release(Entry entry) {
		if (entry.id != null) {
			Minecraft.getInstance().getTextureManager().release(entry.id);
			entry.id = null;
		}
	}

	private static void trim() {
		Iterator<Entry> oldest = ENTRIES.values().iterator();
		while (ENTRIES.size() > MAX_KEPT && oldest.hasNext()) {
			release(oldest.next());
			oldest.remove();
		}
	}
}
