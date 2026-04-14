package com.lionclient.network;

import com.lionclient.feature.module.ModuleManager;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Queue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;

public final class PacketDelayManager {
    private static final String HANDLER_NAME = "lionclient_packet_delay";
    private static final Field CHANNEL_FIELD = findChannelField();

    private final Minecraft minecraft = Minecraft.getMinecraft();
    private final ModuleManager moduleManager;
    private final Queue<QueuedPacket> outboundQueue = new ArrayDeque<QueuedPacket>();
    private final Queue<QueuedInboundPacket> inboundQueue = new ArrayDeque<QueuedInboundPacket>();
    private final ChannelDuplexHandler handler = new PacketInterceptor();

    private Channel channel;
    private boolean flushing;

    public PacketDelayManager(ModuleManager moduleManager) {
        this.moduleManager = moduleManager;
    }

    public void onClientTick() {
        Channel currentChannel = resolveChannel();
        if (currentChannel == null || !currentChannel.isOpen()) {
            flushQueuedPackets();
            detachHandler();
            return;
        }

        if (channel != currentChannel) {
            flushQueuedPackets();
            detachHandler();
            channel = currentChannel;
            attachHandler(currentChannel);
        }

        if (moduleManager.consumeOutboundFlushRequest() || moduleManager.consumeFlushRequest()) {
            flushQueuedOutboundPackets();
        } else if (!moduleManager.isOutboundPacketDelayActive()) {
            flushQueuedOutboundPackets();
        }

        if (moduleManager.consumeInboundFlushRequest()) {
            flushQueuedInboundPackets();
        } else if (!moduleManager.isInboundPacketDelayActive()) {
            flushQueuedInboundPackets();
        }

        flushReadyOutboundPackets();
        flushReadyInboundPackets();
    }

    public void flushQueuedPackets() {
        flushQueuedOutboundPackets();
        flushQueuedInboundPackets();
    }

    public void flushQueuedOutboundPackets() {
        List<Packet<?>> packets = new ArrayList<Packet<?>>();
        synchronized (outboundQueue) {
            while (!outboundQueue.isEmpty()) {
                packets.add(outboundQueue.poll().packet);
            }
        }

        if (channel == null || !channel.isOpen()) {
            return;
        }

        try {
            flushing = true;
            for (Packet<?> packet : packets) {
                channel.write(packet);
            }
            if (!packets.isEmpty()) {
                channel.flush();
            }
        } finally {
            flushing = false;
        }
    }

    public void flushQueuedInboundPackets() {
        List<QueuedInboundPacket> packets = new ArrayList<QueuedInboundPacket>();
        synchronized (inboundQueue) {
            while (!inboundQueue.isEmpty()) {
                packets.add(inboundQueue.poll());
            }
        }

        for (QueuedInboundPacket packet : packets) {
            releaseInbound(packet);
        }
    }

    private void attachHandler(Channel targetChannel) {
        if (targetChannel.pipeline().get(HANDLER_NAME) != null) {
            return;
        }

        try {
            targetChannel.pipeline().addBefore("packet_handler", HANDLER_NAME, handler);
        } catch (NoSuchElementException ignored) {
        }
    }

    private void detachHandler() {
        if (channel == null) {
            return;
        }

        try {
            if (channel.pipeline().get(HANDLER_NAME) != null) {
                channel.pipeline().remove(HANDLER_NAME);
            }
        } catch (Exception ignored) {
        } finally {
            channel = null;
        }
    }

    private void flushReadyOutboundPackets() {
        if (channel == null || !channel.isOpen()) {
            return;
        }

        long now = System.currentTimeMillis();
        List<Packet<?>> readyPackets = new ArrayList<Packet<?>>();
        synchronized (outboundQueue) {
            while (!outboundQueue.isEmpty() && outboundQueue.peek().releaseAt <= now) {
                readyPackets.add(outboundQueue.poll().packet);
            }
        }

        try {
            flushing = true;
            for (Packet<?> packet : readyPackets) {
                channel.write(packet);
            }
            if (!readyPackets.isEmpty()) {
                channel.flush();
            }
        } finally {
            flushing = false;
        }
    }

    private void flushReadyInboundPackets() {
        long now = System.currentTimeMillis();
        List<QueuedInboundPacket> readyPackets = new ArrayList<QueuedInboundPacket>();
        synchronized (inboundQueue) {
            while (!inboundQueue.isEmpty() && inboundQueue.peek().releaseAt <= now) {
                readyPackets.add(inboundQueue.poll());
            }
        }

        for (QueuedInboundPacket packet : readyPackets) {
            releaseInbound(packet);
        }
    }

    private void releaseInbound(final QueuedInboundPacket queuedPacket) {
        final ChannelHandlerContext context = queuedPacket.context;
        if (context == null || context.channel() == null || !context.channel().isOpen()) {
            return;
        }

        context.channel().eventLoop().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    context.fireChannelRead(queuedPacket.packet);
                } catch (Exception ignored) {
                }
            }
        });
    }

    private Channel resolveChannel() {
        NetHandlerPlayClient handler = minecraft.getNetHandler();
        if (handler == null || CHANNEL_FIELD == null) {
            return null;
        }

        try {
            NetworkManager networkManager = handler.getNetworkManager();
            return networkManager == null ? null : (Channel) CHANNEL_FIELD.get(networkManager);
        } catch (IllegalAccessException ignored) {
            return null;
        }
    }

    private static Field findChannelField() {
        try {
            Field field = NetworkManager.class.getDeclaredField("channel");
            field.setAccessible(true);
            return field;
        } catch (Exception ignored) {
            return null;
        }
    }

    private final class PacketInterceptor extends ChannelDuplexHandler {
        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
            if (!flushing && message instanceof Packet<?>) {
                Packet<?> packet = (Packet<?>) message;
                moduleManager.onOutboundPacket(packet);
                int delay = moduleManager.getOutboundPacketDelay(packet);
                if (delay > 0) {
                    synchronized (outboundQueue) {
                        outboundQueue.add(new QueuedPacket(packet, System.currentTimeMillis() + delay));
                    }
                    promise.setSuccess();
                    return;
                }
            }

            super.write(context, message, promise);
        }

        @Override
        public void channelRead(ChannelHandlerContext context, Object message) throws Exception {
            if (message instanceof Packet<?>) {
                Packet<?> packet = (Packet<?>) message;
                moduleManager.onInboundPacket(packet);
                int delay = moduleManager.getInboundPacketDelay(packet);
                if (delay > 0) {
                    synchronized (inboundQueue) {
                        inboundQueue.add(new QueuedInboundPacket(context, packet, System.currentTimeMillis() + delay));
                    }
                    return;
                }
            }

            super.channelRead(context, message);
        }
    }

    private static final class QueuedPacket {
        private final Packet<?> packet;
        private final long releaseAt;

        private QueuedPacket(Packet<?> packet, long releaseAt) {
            this.packet = packet;
            this.releaseAt = releaseAt;
        }
    }

    private static final class QueuedInboundPacket {
        private final ChannelHandlerContext context;
        private final Packet<?> packet;
        private final long releaseAt;

        private QueuedInboundPacket(ChannelHandlerContext context, Packet<?> packet, long releaseAt) {
            this.context = context;
            this.packet = packet;
            this.releaseAt = releaseAt;
        }
    }
}
