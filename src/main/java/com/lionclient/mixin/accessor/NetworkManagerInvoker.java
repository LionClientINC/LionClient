package com.lionclient.mixin.accessor;

import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.minecraft.network.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(net.minecraft.network.NetworkManager.class)
public interface NetworkManagerInvoker {
    @Invoker("channelRead0")
    void lionclient$invokeChannelRead0(ChannelHandlerContext context, Packet<?> packet);

    @Invoker("dispatchPacket")
    void lionclient$invokeDispatchPacket(
        Packet<?> packet,
        GenericFutureListener<? extends Future<? super Void>>[] listeners
    );
}
