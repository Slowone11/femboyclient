package com.femboyclient.mixin;

import com.femboyclient.NoDebuff;
import net.minecraft.client.renderer.fog.environment.BlindnessFogEnvironment;
import net.minecraft.client.renderer.fog.environment.MobEffectFogEnvironment;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@link NoDebuff}'s No Blindness: the blindness fog is never used. Optional (require = 0). */
@Mixin(MobEffectFogEnvironment.class)
public abstract class MobEffectFogEnvironmentMixin {

	@Inject(method = "isApplicable", at = @At("HEAD"), cancellable = true, require = 0)
	private void femboyclient$noBlindness(FogType fogType, Entity entity, CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof BlindnessFogEnvironment && NoDebuff.noBlindness()) {
			cir.setReturnValue(false);
		}
	}
}
