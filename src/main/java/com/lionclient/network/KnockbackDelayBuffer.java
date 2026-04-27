package com.lionclient.network;

import com.lionclient.LionClient;
import com.lionclient.feature.module.impl.KnockbackDelayModule;
import java.util.ArrayDeque;
import java.util.Queue;
import net.minecraft.network.INetHandler;
import net.minecraft.network.Packet;

public final class KnockbackDelayBuffer {
    private static final int MAX_RELEASES_PER_TICK = 50;

    private final Queue<QueuedPacket> incomingQueue = new ArrayDeque<QueuedPacket>();
    private long lastIncomingDeliveryMs;
    private boolean flushingIncoming;

    public void onClientTick() {
        KnockbackDelayModule module = getModule();
        if (module == null) {
            flushAllIncoming();
            return;
        }

        long now = System.currentTimeMillis();
        flushingIncoming = true;
        try {
            int processed = 0;
            while (processed < MAX_RELEASES_PER_TICK) {
                QueuedPacket peek = incomingQueue.peek();
                if (peek == null || peek.releaseAt > now) {
                    break;
                }

                QueuedPacket queued = incomingQueue.poll();
                if (queued == null) {
                    break;
                }

                try {
                    queued.action.run();
                } catch (Throwable ignored) {
                }
                processed++;
            }
        } finally {
            flushingIncoming = false;
        }

        if (incomingQueue.isEmpty() && !shouldBufferIncoming(module)) {
            lastIncomingDeliveryMs = 0L;
        }
    }

    public void flushAllIncoming() {
        flushingIncoming = true;
        try {
            while (true) {
                QueuedPacket queued = incomingQueue.poll();
                if (queued == null) {
                    break;
                }

                try {
                    queued.action.run();
                } catch (Throwable ignored) {
                }
            }
        } finally {
            flushingIncoming = false;
            lastIncomingDeliveryMs = 0L;
        }
    }

    private boolean shouldBufferIncoming(KnockbackDelayModule module) {
        if (flushingIncoming) {
            return false;
        }

        if (module == null || !module.isEnabled()) {
            if (!incomingQueue.isEmpty()) {
                flushAllIncoming();
            }
            return false;
        }

        if (!module.isHolding()) {
            if (!incomingQueue.isEmpty()) {
                flushAllIncoming();
            }
            return false;
        }

        return module.isEnabled() && module.isHolding();
    }

    public boolean shouldBufferIncoming() {
        return shouldBufferIncoming(getModule());
    }

    public void bufferIncoming(Runnable action, int delayMs) {
        if (flushingIncoming) {
            try {
                action.run();
            } catch (Throwable ignored) {
            }
            return;
        }

        long now = System.currentTimeMillis();
        long deliverAt = Math.max(now + Math.max(0, delayMs), lastIncomingDeliveryMs);
        lastIncomingDeliveryMs = deliverAt;
        incomingQueue.offer(new QueuedPacket(deliverAt, action));
    }

    @SuppressWarnings("unchecked")
    private Runnable createAction(final Packet<?> packet, final INetHandler listener) {
        final Packet<INetHandler> typedPacket = (Packet<INetHandler>) packet;
        return new Runnable() {
            @Override
            public void run() {
                typedPacket.processPacket(listener);
            }
        };
    }

    private KnockbackDelayModule getModule() {
        LionClient client = LionClient.getInstance();
        if (client == null) {
            return null;
        }
        return client.getModuleManager().getModule(KnockbackDelayModule.class);
    }

    private static final class QueuedPacket {
        private final long releaseAt;
        private final Runnable action;

        private QueuedPacket(long releaseAt, Runnable action) {
            this.releaseAt = releaseAt;
            this.action = action;
        }
    }
}
