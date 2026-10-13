package com.femboyclient.mixin;

import com.femboyclient.HidePlayers;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lets {@link HidePlayers} skip drawing a player (body, armour, name tag). Optional (require = 0). */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {

	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true, require = 0)
	private <E extends Entity> void femboyclient$hidePlayers(E entity, Frustum frustum, double x, double y, double z,
			CallbackInfoReturnable<Boolean> cir) {
		if (HidePlayers.hidden(entity)) {
			cir.setReturnValue(false);
		}
	}
}
