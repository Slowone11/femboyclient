package com.femboyclient.mixin;

import com.femboyclient.FemboyClientConfig;
import com.femboyclient.HandMan;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets {@link HandMan}'s Speed change how long your own swing animation lasts. Only your player is changed, and the swing
 * length is only used to animate it: attacks, mining and what the server sees are the same. Optional (require = 0).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

	@Inject(method = "getCurrentSwingDuration", at = @At("RETURN"), cancellable = true, require = 0)
	private void femboyclient$handManSpeed(CallbackInfoReturnable<Integer> cir) {
		if (!((Object) this instanceof LocalPlayer player) || !HandMan.active()) {
			return;
		}
		FemboyClientConfig config = FemboyClientConfig.get();
		if (config.handSpeed == 0 && !config.handIgnoreHaste) {
			return;
		}
		InteractionHand hand = player.swingingArm != null ? player.swingingArm : InteractionHand.MAIN_HAND;
		int plain = player.getItemInHand(hand).getSwingAnimation().duration();
		cir.setReturnValue(HandMan.swingDuration(cir.getReturnValueI(), plain, config.handSpeed, config.handIgnoreHaste));
	}
}
