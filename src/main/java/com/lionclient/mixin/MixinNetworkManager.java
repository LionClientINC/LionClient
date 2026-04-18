package com.lionclient.mixin;

import com.lionclient.network.PacketDelayManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.lang.reflect.Array;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NetworkManager.class)
public abstract class MixinNetworkManager {
    @Inject(method = "sendPacket(Lnet/minecraft/network/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void lionclient$interceptOutbound(Packet<?> packet, CallbackInfo callbackInfo) {
        PacketDelayManager packetDelayManager = PacketDelayManager.getInstance();
        if (packetDelayManager != null
            && packetDelayManager.interceptOutbound(packet, null)) {
            callbackInfo.cancel();
        }
    }

    @Inject(
        method = "sendPacket(Lnet/minecraft/network/Packet;Lio/netty/util/concurrent/GenericFutureListener;[Lio/netty/util/concurrent/GenericFutureListener;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void lionclient$interceptOutboundWithListeners(
        Packet<?> packet,
        GenericFutureListener<? extends Future<? super Void>> listener,
        GenericFutureListener<? extends Future<? super Void>>[] listeners,
        CallbackInfo callbackInfo
    ) {
        PacketDelayManager packetDelayManager = PacketDelayManager.getInstance();
        if (packetDelayManager != null
            && packetDelayManager.interceptOutbound(
                packet,
                lionclient$mergeListeners(listener, listeners)
            )) {
            callbackInfo.cancel();
        }
    }

    @Inject(
        method = "dispatchPacket(Lnet/minecraft/network/Packet;[Lio/netty/util/concurrent/GenericFutureListener;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void lionclient$interceptDispatchPacket(
        Packet<?> packet,
        GenericFutureListener<? extends Future<? super Void>>[] listeners,
        CallbackInfo callbackInfo
    ) {
        PacketDelayManager packetDelayManager = PacketDelayManager.getInstance();
        if (packetDelayManager != null
            && packetDelayManager.interceptOutbound(packet, listeners)) {
            callbackInfo.cancel();
        }
    }

    @Inject(
        method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/Packet;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void lionclient$interceptInbound(ChannelHandlerContext context, Packet<?> packet, CallbackInfo callbackInfo) {
        PacketDelayManager packetDelayManager = PacketDelayManager.getInstance();
        if (packetDelayManager != null
            && packetDelayManager.interceptInbound(context, packet)) {
            callbackInfo.cancel();
        }
    }

    @SuppressWarnings("unchecked")
    private static GenericFutureListener<? extends Future<? super Void>>[] lionclient$mergeListeners(
        GenericFutureListener<? extends Future<? super Void>> first,
        GenericFutureListener<? extends Future<? super Void>>[] remaining
    ) {
        if (first == null) {
            return remaining;
        }

        int remainingLength = remaining == null ? 0 : remaining.length;
        GenericFutureListener<? extends Future<? super Void>>[] merged =
            (GenericFutureListener<? extends Future<? super Void>>[]) Array.newInstance(GenericFutureListener.class, remainingLength + 1);
        merged[0] = first;
        if (remainingLength > 0) {
            System.arraycopy(remaining, 0, merged, 1, remainingLength);
        }
        return merged;
    }
}
