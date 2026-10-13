package com.femboyclient.mixin26;

import com.femboyclient.NoDebuff;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * {@link NoDebuff}'s No Portal Effect: the nether portal overlay is not drawn. The class only exists in 26.2, so this mixin is
 * in its own optional config (femboyclient-26_2.mixins.json) and 26.1.2 simply goes without it.
 */
@Mixin(targets = "net.minecraft.client.gui.Hud")
public abstract class HudMixin {

	@Inject(method = "extractPortalOverlay", at = @At("HEAD"), cancellable = true, require = 0)
	private void femboyclient$noPortal(GuiGraphicsExtractor graphics, float alpha, CallbackInfo ci) {
		if (NoDebuff.noPortal()) {
			ci.cancel();
		}
	}
}
