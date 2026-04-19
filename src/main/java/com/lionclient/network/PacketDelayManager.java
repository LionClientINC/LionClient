package com.lionclient.network;

import com.lionclient.feature.module.ModuleManager;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.Packet;
import net.minecraft.network.ThreadQuickExitException;
import net.minecraft.network.play.INetHandlerPlayClient;

public final class PacketDelayManager {
    private static volatile PacketDelayManager instance;

    private final Minecraft minecraft = Minecraft.getMinecraft();
    private final ModuleManager moduleManager;
    private final Queue<QueuedOutboundPacket> outboundQueue = new ArrayDeque<QueuedOutboundPacket>();
    private final Queue<QueuedInboundPacket> inboundQueue = new ArrayDeque<QueuedInboundPacket>();
    private final Set<Packet<?>> outboundFastTrack = Collections.newSetFromMap(
        Collections.synchronizedMap(new IdentityHashMap<Packet<?>, Boolean>())
    );

    public PacketDelayManager(ModuleManager moduleManager) {
        this.moduleManager = moduleManager;
        instance = this;
    }

    public static PacketDelayManager getInstance() {
        return instance;
    }

    public void onClientTick() {
        NetHandlerPlayClient netHandler = minecraft.getNetHandler();
        if (netHandler == null) {
            clearQueues();
            return;
        }

        if (moduleManager.consumeOutboundFlushRequest() || moduleManager.consumeFlushRequest()) {
            flushQueuedOutboundPackets();
        } else if (!moduleManager.isOutboundPacketDelayActive()) {
            flushQueuedOutboundPackets();
        }

        if (moduleManager.consumeInboundFlushRequest()) {
            flushQueuedInboundPackets();
        }

        flushReadyOutboundPackets();
    }

    public boolean interceptOutbound(
        Packet<?> packet,
        GenericFutureListener<? extends Future<? super Void>>[] listeners
    ) {
        if (consumeOutboundFastTrack(packet)) {
            return false;
        }

        moduleManager.onOutboundPacket(packet);
        int delay = moduleManager.getOutboundPacketDelay(packet);
        if (delay <= 0) {
            return false;
        }

        synchronized (outboundQueue) {
            outboundQueue.add(new QueuedOutboundPacket(packet, System.currentTimeMillis() + delay));
        }
        return true;
    }

    public boolean interceptInbound(ChannelHandlerContext context, Packet<?> packet) {
        moduleManager.onInboundPacket(packet);
        int delay = moduleManager.getInboundPacketDelay(packet);
        if (delay <= 0 && !hasQueuedInboundPackets()) {
            return false;
        }

        synchronized (inboundQueue) {
            inboundQueue.add(new QueuedInboundPacket(packet, System.currentTimeMillis()));
        }
        return true;
    }

    public void releaseExpiredInboundPackets(long maxAgeMs) {
        long cutoff = System.currentTimeMillis() - Math.max(0L, maxAgeMs);
        List<QueuedInboundPacket> packets = new ArrayList<QueuedInboundPacket>();

        synchronized (inboundQueue) {
            for (QueuedInboundPacket packet : new ArrayList<QueuedInboundPacket>(inboundQueue)) {
                if (packet.queuedAt <= cutoff) {
                    packets.add(packet);
                }
            }
            inboundQueue.removeAll(packets);
        }

        for (QueuedInboundPacket packet : packets) {
            releaseInbound(packet.packet);
        }
    }

    private void flushQueuedOutboundPackets() {
        List<QueuedOutboundPacket> packets = new ArrayList<QueuedOutboundPacket>();
        synchronized (outboundQueue) {
            while (!outboundQueue.isEmpty()) {
                packets.add(outboundQueue.poll());
            }
        }

        for (QueuedOutboundPacket packet : packets) {
            releaseOutbound(packet.packet);
        }
    }

    private void flushQueuedInboundPackets() {
        List<QueuedInboundPacket> packets = new ArrayList<QueuedInboundPacket>();
        synchronized (inboundQueue) {
            while (!inboundQueue.isEmpty()) {
                packets.add(inboundQueue.poll());
            }
        }

        for (QueuedInboundPacket packet : packets) {
            releaseInbound(packet.packet);
        }
    }

    private void flushReadyOutboundPackets() {
        long now = System.currentTimeMillis();
        List<QueuedOutboundPacket> packets = new ArrayList<QueuedOutboundPacket>();
        synchronized (outboundQueue) {
            while (!outboundQueue.isEmpty() && outboundQueue.peek().releaseAt <= now) {
                packets.add(outboundQueue.poll());
            }
        }

        for (QueuedOutboundPacket packet : packets) {
            releaseOutbound(packet.packet);
        }
    }

    private void releaseOutbound(Packet<?> packet) {
        NetHandlerPlayClient netHandler = minecraft.getNetHandler();
        if (netHandler == null) {
            return;
        }

        outboundFastTrack.add(packet);
        netHandler.addToSendQueue(packet);
    }

    private void releaseInbound(Packet<?> packet) {
        NetHandlerPlayClient netHandler = minecraft.getNetHandler();
        if (netHandler == null) {
            return;
        }

        try {
            ((Packet<INetHandlerPlayClient>) packet).processPacket(netHandler);
        } catch (ThreadQuickExitException ignored) {
        } catch (Exception ignored) {
        }
    }

    private boolean consumeOutboundFastTrack(Packet<?> packet) {
        return outboundFastTrack.remove(packet);
    }

    private void clearQueues() {
        synchronized (outboundQueue) {
            outboundQueue.clear();
        }
        synchronized (inboundQueue) {
            inboundQueue.clear();
        }
        outboundFastTrack.clear();
    }

    private boolean hasQueuedInboundPackets() {
        synchronized (inboundQueue) {
            return !inboundQueue.isEmpty();
        }
    }

    private static final class QueuedOutboundPacket {
        private final Packet<?> packet;
        private final long releaseAt;

        private QueuedOutboundPacket(Packet<?> packet, long releaseAt) {
            this.packet = packet;
            this.releaseAt = releaseAt;
        }
    }

    private static final class QueuedInboundPacket {
        private final Packet<?> packet;
        private final long queuedAt;

        private QueuedInboundPacket(Packet<?> packet, long queuedAt) {
            this.packet = packet;
            this.queuedAt = queuedAt;
        }
    }
}
