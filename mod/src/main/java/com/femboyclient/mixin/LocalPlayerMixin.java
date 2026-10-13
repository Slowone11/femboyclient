package com.femboyclient.mixin;

import com.femboyclient.NoDebuff;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link NoDebuff}'s No Push: you are not pushed out of a block you are standing in. Optional (require = 0). */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {

	@Inject(method = "moveTowardsClosestSpace", at = @At("HEAD"), cancellable = true, require = 0)
	private void femboyclient$noPush(double x, double z, CallbackInfo ci) {
		if (NoDebuff.noPush()) {
			ci.cancel();
		}
	}
}
