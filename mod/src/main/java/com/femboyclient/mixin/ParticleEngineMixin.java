package com.femboyclient.mixin;

import com.femboyclient.NoParticles;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lets {@link NoParticles} stop every particle before it exists. {@code add} is the one door every particle goes through
 * (block cracks, mob effects, packets from the server); the tracking emitters (crit and hit sparks that follow an entity)
 * are stopped too, so they do not keep making particles that are then thrown away. Both are optional (require = 0): if a
 * game version renames them, the game still starts and only No Particles stops working.
 */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineMixin {

	@Inject(method = "add", at = @At("HEAD"), cancellable = true, require = 0)
	private void femboyclient$noParticles(Particle particle, CallbackInfo ci) {
		if (NoParticles.active()) {
			NoParticles.blocked();
			ci.cancel();
		}
	}

	@Inject(method = "createTrackingEmitter(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void femboyclient$noTrackingEmitter(Entity entity, ParticleOptions options, CallbackInfo ci) {
		if (NoParticles.active()) {
			ci.cancel();
		}
	}

	@Inject(method = "createTrackingEmitter(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/core/particles/ParticleOptions;I)V",
			at = @At("HEAD"), cancellable = true, require = 0)
	private void femboyclient$noTimedTrackingEmitter(Entity entity, ParticleOptions options, int lifetime, CallbackInfo ci) {
		if (NoParticles.active()) {
			ci.cancel();
		}
	}
}
