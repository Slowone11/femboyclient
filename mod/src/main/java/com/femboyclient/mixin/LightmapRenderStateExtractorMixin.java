package com.femboyclient.mixin;

import com.femboyclient.Fullbright;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hooks the lightmap rebuild so {@link Fullbright} can override the state before it is sent to the shader. */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapRenderStateExtractorMixin {

	// TAIL only: the early returns (no update due, or no world loaded) leave the state as it is.
	@Inject(method = "extract", at = @At("TAIL"))
	private void femboyclient$fullbright(LightmapRenderState state, float partialTick, CallbackInfo ci) {
		Fullbright.apply(state);
	}
}
