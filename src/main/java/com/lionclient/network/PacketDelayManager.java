package com.lionclient.network;

import com.lionclient.feature.module.ModuleManager;
import com.lionclient.mixin.accessor.NetworkManagerInvoker;
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
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;

public final class PacketDelayManager {
    private static final int MAX_RELEASES_PER_TICK = 50;

    private static volatile PacketDelayManager instance;

    private final Minecraft minecraft = Minecraft.getMinecraft();
    private final ModuleManager moduleManager;
    private final Queue<QueuedOutboundPacket> outboundQueue = new ArrayDeque<QueuedOutboundPacket>();
    private final Queue<QueuedInboundPacket> inboundQueue = new ArrayDeque<QueuedInboundPacket>();
    private final Set<Packet<?>> outboundFastTrack = Collections.newSetFromMap(
        Collections.synchronizedMap(new IdentityHashMap<Packet<?>, Boolean>())
    );
    private long lastOutboundReleaseAt;
    private long lastInboundReleaseAt;

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
        }

        if (moduleManager.consumeInboundFlushRequest()) {
            flushQueuedInboundPackets();
        }

        flushReadyInboundPackets();
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
            if (hasQueuedOutboundPackets()) {
                flushQueuedOutboundPackets();
            }
            return false;
        }

        synchronized (outboundQueue) {
            long now = System.currentTimeMillis();
            long releaseAt = Math.max(now + delay, lastOutboundReleaseAt);
            lastOutboundReleaseAt = releaseAt;
            outboundQueue.add(new QueuedOutboundPacket(packet, listeners, releaseAt));
        }
        return true;
    }

    public boolean interceptInbound(Packet<?> packet, INetHandler listener) {
        if (listener == null) {
            return false;
        }

        moduleManager.onInboundPacket(packet);

        int delay = moduleManager.getInboundPacketDelay(packet);
        if (delay <= 0) {
            if (hasQueuedInboundPackets()) {
                flushQueuedInboundPackets();
            }
            return false;
        }

        queueInbound(packet, listener, delay);
        return true;
    }

    private void flushQueuedOutboundPackets() {
        List<QueuedOutboundPacket> packets = new ArrayList<QueuedOutboundPacket>();
        synchronized (outboundQueue) {
            while (!outboundQueue.isEmpty()) {
                packets.add(outboundQueue.poll());
            }
            lastOutboundReleaseAt = 0L;
        }

        for (QueuedOutboundPacket packet : packets) {
            releaseOutbound(packet);
        }
    }

    private void flushQueuedInboundPackets() {
        List<QueuedInboundPacket> packets = new ArrayList<QueuedInboundPacket>();
        synchronized (inboundQueue) {
            while (!inboundQueue.isEmpty()) {
                packets.add(inboundQueue.poll());
            }
            lastInboundReleaseAt = 0L;
        }

        for (QueuedInboundPacket packet : packets) {
            releaseInbound(packet);
        }
    }

    private void flushReadyOutboundPackets() {
        long now = System.currentTimeMillis();
        List<QueuedOutboundPacket> packets = new ArrayList<QueuedOutboundPacket>();
        synchronized (outboundQueue) {
            while (packets.size() < MAX_RELEASES_PER_TICK
                && !outboundQueue.isEmpty()
                && outboundQueue.peek().releaseAt <= now) {
                packets.add(outboundQueue.poll());
            }
            if (outboundQueue.isEmpty()) {
                lastOutboundReleaseAt = 0L;
            }
        }

        for (QueuedOutboundPacket packet : packets) {
            releaseOutbound(packet);
        }
    }

    private void flushReadyInboundPackets() {
        long now = System.currentTimeMillis();
        List<QueuedInboundPacket> packets = new ArrayList<QueuedInboundPacket>();
        synchronized (inboundQueue) {
            while (packets.size() < MAX_RELEASES_PER_TICK
                && !inboundQueue.isEmpty()
                && inboundQueue.peek().releaseAt <= now) {
                packets.add(inboundQueue.poll());
            }
            if (inboundQueue.isEmpty()) {
                lastInboundReleaseAt = 0L;
            }
        }

        for (QueuedInboundPacket packet : packets) {
            releaseInbound(packet);
        }
    }

    private void releaseOutbound(QueuedOutboundPacket queuedPacket) {
        NetHandlerPlayClient netHandler = minecraft.getNetHandler();
        if (netHandler == null) {
            return;
        }

        if (netHandler.getNetworkManager() == null) {
            return;
        }

        outboundFastTrack.add(queuedPacket.packet);
        try {
            ((NetworkManagerInvoker) netHandler.getNetworkManager()).lionclient$invokeDispatchPacket(
                queuedPacket.packet,
                queuedPacket.listeners
            );
        } catch (Exception ignored) {
            outboundFastTrack.remove(queuedPacket.packet);
        }
    }

    private void releaseInbound(final QueuedInboundPacket queuedPacket) {
        moduleManager.onInboundPacketReleased(queuedPacket.packet);
        try {
            queuedPacket.action.run();
        } catch (Exception ignored) {
        }
    }

    private boolean consumeOutboundFastTrack(Packet<?> packet) {
        return outboundFastTrack.remove(packet);
    }

    @SuppressWarnings("unchecked")
    private Runnable createInboundAction(final Packet<?> packet, final INetHandler listener) {
        final Packet<INetHandler> typedPacket = (Packet<INetHandler>) packet;
        return new Runnable() {
            @Override
            public void run() {
                typedPacket.processPacket(listener);
            }
        };
    }

    private void clearQueues() {
        synchronized (outboundQueue) {
            outboundQueue.clear();
        }
        synchronized (inboundQueue) {
            inboundQueue.clear();
        }
        lastOutboundReleaseAt = 0L;
        lastInboundReleaseAt = 0L;
        outboundFastTrack.clear();
    }

    private boolean hasQueuedOutboundPackets() {
        synchronized (outboundQueue) {
            return !outboundQueue.isEmpty();
        }
    }

    private boolean hasQueuedInboundPackets() {
        synchronized (inboundQueue) {
            return !inboundQueue.isEmpty();
        }
    }

    private void queueInbound(
        Packet<?> packet,
        INetHandler listener,
        int delay
    ) {
        Runnable action = createInboundAction(packet, listener);
        synchronized (inboundQueue) {
            long now = System.currentTimeMillis();
            long releaseAt = Math.max(now + delay, lastInboundReleaseAt);
            lastInboundReleaseAt = releaseAt;
            inboundQueue.add(new QueuedInboundPacket(packet, action, releaseAt));
        }
        moduleManager.onInboundPacketQueued(packet);
    }

    private static final class QueuedOutboundPacket {
        private final Packet<?> packet;
        private final GenericFutureListener<? extends Future<? super Void>>[] listeners;
        private final long releaseAt;

        private QueuedOutboundPacket(
            Packet<?> packet,
            GenericFutureListener<? extends Future<? super Void>>[] listeners,
            long releaseAt
        ) {
            this.packet = packet;
            this.listeners = listeners;
            this.releaseAt = releaseAt;
        }
    }

    private static final class QueuedInboundPacket {
        private final Packet<?> packet;
        private final Runnable action;
        private final long releaseAt;

        private QueuedInboundPacket(Packet<?> packet, Runnable action, long releaseAt) {
            this.packet = packet;
            this.action = action;
            this.releaseAt = releaseAt;
        }
    }
}
