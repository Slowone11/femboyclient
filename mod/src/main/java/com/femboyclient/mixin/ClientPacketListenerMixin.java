package com.femboyclient.mixin;

import com.femboyclient.NoDebuff;
import com.femboyclient.ServerStats;
import com.femboyclient.TPMaze;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.ping.ClientboundPongResponsePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Packets the ported Odin modules read: the server's time (TPS) and pings (Server Display, Chat Commands), teleports (TP Maze
 * solver) and particles (No Debuff). The handlers run once on the network thread, which hands the packet to the game thread
 * and stops; TAIL is only reached on the game thread. All optional (require = 0).
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

	@Inject(method = "handleSetTime", at = @At("TAIL"), require = 0)
	private void femboyclient$tps(ClientboundSetTimePacket packet, CallbackInfo ci) {
		ServerStats.onTimeUpdate();
	}

	@Inject(method = "handlePongResponse", at = @At("TAIL"), require = 0)
	private void femboyclient$ping(ClientboundPongResponsePacket packet, CallbackInfo ci) {
		ServerStats.onPong(packet.time());
	}

	/** The game only pings the server while its network chart is open; Server Display needs the pings all the time. */
	@ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/gui/components/DebugScreenOverlay;showNetworkCharts()Z"), require = 0)
	private boolean femboyclient$alwaysPing(boolean original) {
		return original || ServerStats.wanted();
	}

	@Inject(method = "handleMovePlayer", at = @At("TAIL"), require = 0)
	private void femboyclient$teleport(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		TPMaze.onTeleport();
	}

	@Inject(method = "handleParticleEvent", at = @At("HEAD"), cancellable = true, require = 0)
	private void femboyclient$noShieldParticles(ClientboundLevelParticlesPacket packet, CallbackInfo ci) {
		if (NoDebuff.hideParticle(packet.getParticle().getType(), packet.getX(), packet.getY(), packet.getZ())) {
			ci.cancel();
		}
	}
}
