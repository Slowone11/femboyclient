package com.femboyclient.mixin;

import com.femboyclient.NoDebuff;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link NoDebuff}'s No Fire Overlay and See Through Blocks. Optional (require = 0). */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {

	@Inject(method = "submitFire", at = @At("HEAD"), cancellable = true, require = 0)
	private static void femboyclient$noFire(PoseStack poseStack, SubmitNodeCollector collector, TextureAtlasSprite sprite, CallbackInfo ci) {
		if (NoDebuff.noFire()) {
			ci.cancel();
		}
	}

	@Inject(method = "submitBlockSprite", at = @At("HEAD"), cancellable = true, require = 0)
	private static void femboyclient$seeThroughBlocks(TextureAtlasSprite sprite, PoseStack poseStack, SubmitNodeCollector collector,
			int light, CallbackInfo ci) {
		if (NoDebuff.seeThroughBlocks()) {
			ci.cancel();
		}
	}
}
