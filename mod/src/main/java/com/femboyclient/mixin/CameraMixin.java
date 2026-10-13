package com.femboyclient.mixin;

import com.femboyclient.CameraTweaks;
import com.femboyclient.NoDebuff;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.Camera;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link CameraTweaks}: the third person distance, Camera Clip and Freelook's own camera rotation; and {@link NoDebuff}'s No
 * Water FOV. All optional (require = 0).
 */
@Mixin(Camera.class)
public abstract class CameraMixin {

	@ModifyArg(method = "alignWithEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getMaxZoom(F)F"),
			require = 0)
	private float femboyclient$distance(float distance) {
		return CameraTweaks.distance(distance);
	}

	@Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true, require = 0)
	private void femboyclient$clip(float distance, CallbackInfoReturnable<Float> cir) {
		if (CameraTweaks.clip()) {
			cir.setReturnValue(distance);
		}
	}

	@ModifyExpressionValue(method = "alignWithEntity",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewYRot(F)F"), require = 0)
	private float femboyclient$freelookYaw(float original) {
		return CameraTweaks.freelook() ? CameraTweaks.yaw() : original;
	}

	@ModifyExpressionValue(method = "alignWithEntity",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;getViewXRot(F)F"), require = 0)
	private float femboyclient$freelookPitch(float original) {
		return CameraTweaks.freelook() ? CameraTweaks.pitch() : original;
	}

	@ModifyExpressionValue(method = "modifyFovBasedOnDeathOrFluid",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Camera;getFluidInCamera()Lnet/minecraft/world/level/material/FogType;"),
			require = 0)
	private FogType femboyclient$noWaterFov(FogType original) {
		return original == FogType.WATER && NoDebuff.noWaterFov() ? FogType.NONE : original;
	}
}
