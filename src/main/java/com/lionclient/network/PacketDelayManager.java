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
    private final Queue<QueuedPacket> queue = new ArrayDeque<QueuedPacket>();
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
            detachHandler();
            channel = currentChannel;
            attachHandler(currentChannel);
        }

        if (moduleManager.consumeFlushRequest()) {
            flushQueuedPackets();
        } else if (!moduleManager.isPacketDelayActive()) {
            flushQueuedPackets();
        }

        flushReadyPackets();
    }

    public void flushQueuedPackets() {
        List<Packet<?>> packets = new ArrayList<Packet<?>>();
        synchronized (queue) {
            while (!queue.isEmpty()) {
                packets.add(queue.poll().packet);
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

    private void flushReadyPackets() {
        if (channel == null || !channel.isOpen()) {
            return;
        }

        long now = System.currentTimeMillis();
        List<Packet<?>> readyPackets = new ArrayList<Packet<?>>();
        synchronized (queue) {
            while (!queue.isEmpty() && queue.peek().releaseAt <= now) {
                readyPackets.add(queue.poll().packet);
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
                    synchronized (queue) {
                        queue.add(new QueuedPacket(packet, System.currentTimeMillis() + delay));
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
                moduleManager.onInboundPacket((Packet<?>) message);
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
}
