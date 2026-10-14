package com.femboyclient.mixin;

import com.femboyclient.CameraTweaks;
import com.femboyclient.PetSwapper;
import com.femboyclient.StarredMobs;
import com.femboyclient.TeammatesHighlight;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link TeammatesHighlight}'s and {@link StarredMobs}' outline colour, {@link CameraTweaks}' Freelook: while it is on, the
 * mouse turns the camera instead of your player, and {@link PetSwapper}: while it swaps, the mouse does not turn you, as with a
 * menu open. All optional (require = 0).
 */
@Mixin(Entity.class)
public abstract class EntityMixin {

	@Inject(method = "getTeamColor", at = @At("HEAD"), cancellable = true, require = 0)
	private void femboyclient$teammateColor(CallbackInfoReturnable<Integer> cir) {
		int color = TeammatesHighlight.outlineColor((Entity) (Object) this);
		if (color == -1) {
			color = StarredMobs.outlineColor((Entity) (Object) this);
		}
		if (color != -1) {
			cir.setReturnValue(color);
		}
	}

	@Inject(method = "turn", at = @At("HEAD"), cancellable = true, require = 0)
	private void femboyclient$freelook(double dx, double dy, CallbackInfo ci) {
		if (CameraTweaks.freelook() && (Object) this == Minecraft.getInstance().player) {
			CameraTweaks.turn(dx, dy);
			ci.cancel();
		} else if (PetSwapper.frozen() && (Object) this == Minecraft.getInstance().player) {
			ci.cancel();
		}
	}
}
