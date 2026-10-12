package com.femboyclient;

import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;

/**
 * Fullbright: renders every light level as fully lit. Minecraft 26.x refuses a gamma option above the slider's 1.0
 * (it logs "Illegal option value" and falls back to the default), so instead of touching gamma this switches on the
 * lightmap's night-vision term with a white colour each time the lightmap is rebuilt. The lightmap shader takes the
 * brighter of that and the ambient light, so every cell comes out white. Fog and sky colour are left alone, and the
 * player's options are never changed, so switching it off needs no restore.
 */
public final class Fullbright {

	private Fullbright() {
	}

	/** Called by {@code LightmapRenderStateExtractorMixin} after the game fills the lightmap state for a frame. */
	public static void apply(LightmapRenderState state) {
		if (FemboyClientConfig.get().fullbright) {
			state.nightVisionEffectIntensity = 1.0F;
			state.nightVisionColor = LightmapRenderStateExtractor.WHITE;
		}
	}
}
