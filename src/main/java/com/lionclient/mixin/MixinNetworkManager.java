package com.lionclient.mixin;

import com.lionclient.LionClient;
import com.lionclient.feature.module.impl.KnockbackDelayModule;
import com.lionclient.network.PacketDelayManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.lang.reflect.Array;
import net.minecraft.network.INetHandler;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(NetworkManager.class)
public abstract class MixinNetworkManager {
    @Shadow
    private INetHandler packetListener;

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
    @SuppressWarnings("unchecked")
    private void lionclient$interceptInbound(ChannelHandlerContext context, Packet<?> packet, CallbackInfo callbackInfo) {
        LionClient client = LionClient.getInstance();
        if (client != null) {
            KnockbackDelayModule knockbackDelayModule = client.getModuleManager().getModule(KnockbackDelayModule.class);
            if (knockbackDelayModule != null
                && knockbackDelayModule.isEnabled()
                && !knockbackDelayModule.isHolding()
                && packet instanceof S12PacketEntityVelocity) {
                knockbackDelayModule.tryTriggerVelocity((S12PacketEntityVelocity) packet);
            }

            if (client.getKnockbackDelayBuffer().shouldBufferIncoming()) {
                Packet<INetHandler> typedPacket = (Packet<INetHandler>) packet;
                callbackInfo.cancel();
                client.getKnockbackDelayBuffer().bufferIncoming(new Runnable() {
                    @Override
                    public void run() {
                        typedPacket.processPacket(packetListener);
                    }
                }, knockbackDelayModule == null ? 0 : knockbackDelayModule.getRemainingHoldMillis());
                return;
            }
        }

        PacketDelayManager packetDelayManager = PacketDelayManager.getInstance();
        if (packetDelayManager != null
            && packetDelayManager.interceptInbound(packet, packetListener)) {
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
