package com.femboyclient.mixin;

import com.femboyclient.PetSwapper;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While {@link PetSwapper} swaps, new key and mouse-button presses are ignored, the way a menu on screen ignores them: no
 * walking, jumping, attacking or hotbar keys reach the game. Letting go of a key still counts, so nothing stays stuck down.
 * Optional (require = 0).
 */
@Mixin(KeyMapping.class)
public abstract class KeyMappingMixin {

	@Inject(method = "set", at = @At("HEAD"), cancellable = true, require = 0)
	private static void femboyclient$noPressWhileSwapping(InputConstants.Key key, boolean down, CallbackInfo ci) {
		if (down && PetSwapper.frozen()) {
			ci.cancel();
		}
	}

	@Inject(method = "click", at = @At("HEAD"), cancellable = true, require = 0)
	private static void femboyclient$noClickWhileSwapping(InputConstants.Key key, CallbackInfo ci) {
		if (PetSwapper.frozen()) {
			ci.cancel();
		}
	}
}
