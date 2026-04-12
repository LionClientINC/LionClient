package com.lionclient.feature.module;

import com.lionclient.config.ConfigManager;
import com.lionclient.feature.setting.Setting;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.network.Packet;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public abstract class Module {
    private final String name;
    private final String description;
    private final Category category;
    private final List<Setting> settings = new ArrayList<Setting>();
    private int keyCode;
    private boolean enabled;

    protected Module(String name, String description, Category category, int keyCode) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.keyCode = keyCode;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Category getCategory() {
        return category;
    }

    protected void addSetting(Setting setting) {
        settings.add(setting);
    }

    protected void clearSettings() {
        settings.clear();
    }

    public List<Setting> getSettings() {
        return Collections.unmodifiableList(settings);
    }

    public int getKeyCode() {
        return keyCode;
    }

    public void setKeyCode(int keyCode) {
        this.keyCode = keyCode;
        ConfigManager.saveActiveConfig();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }

        this.enabled = enabled;
        if (enabled) {
            onEnable();
        } else {
            onDisable();
        }
        ConfigManager.saveActiveConfig();
    }

    protected void onEnable() {
    }

    protected void onDisable() {
    }

    public void onClientTick() {
    }

    public void onMouseEvent(MouseEvent event) {
    }

    public void onRenderTick(TickEvent.RenderTickEvent event) {
    }

    public void onRenderWorld(RenderWorldLastEvent event) {
    }

    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
    }

    public int getOutboundPacketDelay(Packet<?> packet) {
        return 0;
    }

    public void onInboundPacket(Packet<?> packet) {
    }

    public boolean isPacketDelayActive() {
        return false;
    }

    public boolean consumeFlushRequest() {
        return false;
    }
}
