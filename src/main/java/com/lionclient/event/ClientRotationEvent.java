package com.lionclient.event;

import net.minecraftforge.fml.common.eventhandler.Event;

public final class ClientRotationEvent extends Event {
    public Float yaw;
    public Float pitch;

    public ClientRotationEvent(Float yaw, Float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }
}
