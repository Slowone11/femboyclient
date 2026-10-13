package com.femboyclient.mixin;

import com.femboyclient.HidePlayers;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hide Players' Click Through: a hidden player cannot be aimed at, so a click reaches whatever is behind them. Only the
 * game's own aiming asks this for other players on your screen. Optional (require = 0).
 */
@Mixin(Player.class)
public abstract class PlayerMixin {

	@Inject(method = "isPickable", at = @At("HEAD"), cancellable = true, require = 0)
	private void femboyclient$clickThrough(CallbackInfoReturnable<Boolean> cir) {
		if (HidePlayers.clickThrough((Player) (Object) this)) {
			cir.setReturnValue(false);
		}
	}
}
