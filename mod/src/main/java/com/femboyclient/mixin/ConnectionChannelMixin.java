package com.femboyclient.mixin;

import com.femboyclient.ProxyHandlers;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sends server connections through the proxy set on the server list (see {@link ProxyHandlers}). Connection$1 is the
 * channel initializer of Connection.connect, which the game uses for every connection to a server: joining and the server
 * list's pings, never singleplayer. Required, unlike most mixins here: if it ever stopped applying, the game would join
 * servers directly while the Proxy button still said "Connected".
 */
@Mixin(targets = "net.minecraft.network.Connection$1")
public abstract class ConnectionChannelMixin {

	@Inject(method = "initChannel", at = @At("TAIL"), require = 1)
	private void femboyclient$proxy(Channel channel, CallbackInfo ci) {
		ChannelHandler proxy = ProxyHandlers.create();
		if (proxy != null) {
			channel.pipeline().addFirst("femboyclient_proxy", proxy);
		}
	}
}
