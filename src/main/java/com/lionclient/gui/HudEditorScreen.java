package com.lionclient.gui;

import com.lionclient.feature.module.impl.HudModule;
import java.io.IOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;

public final class HudEditorScreen extends GuiScreen {
    private static final int PADDING = 4;

    private final HudModule hudModule;
    private boolean dragging;
    private int dragOffsetX;
    private int dragOffsetY;

    public HudEditorScreen(HudModule hudModule) {
        this.hudModule = hudModule;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();

        if (hudModule == null) {
            drawCenteredString(this.fontRendererObj, "HUD module missing", this.width / 2, this.height / 2, 0xFFFFFFFF);
            super.drawScreen(mouseX, mouseY, partialTicks);
            return;
        }

        if (dragging) {
            updatePosition(mouseX, mouseY);
        }

        drawCenteredString(this.fontRendererObj, "HUD Editor", this.width / 2, 12, 0xFFFFFFFF);
        drawCenteredString(this.fontRendererObj, "Drag the module list. ESC closes.", this.width / 2, 24, 0xFFB8BECF);

        drawPreviewBox(mouseX, mouseY);
        hudModule.renderEditorPreview(new ScaledResolution(this.mc));
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0 || hudModule == null) {
            return;
        }

        Bounds bounds = getPreviewBounds();
        if (bounds.contains(mouseX, mouseY)) {
            dragging = true;
            dragOffsetX = mouseX - hudModule.getAnchorX();
            dragOffsetY = mouseY - hudModule.getAnchorY();
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        dragging = false;
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void drawPreviewBox(int mouseX, int mouseY) {
        Bounds bounds = getPreviewBounds();
        int outline = bounds.contains(mouseX, mouseY) || dragging ? 0xFFE6EAF3 : 0xFF8A8F9E;
        Gui.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom, 0x40262B3E);
        Gui.drawRect(bounds.left, bounds.top, bounds.right, bounds.top + 1, outline);
        Gui.drawRect(bounds.left, bounds.bottom - 1, bounds.right, bounds.bottom, outline);
        Gui.drawRect(bounds.left, bounds.top, bounds.left + 1, bounds.bottom, outline);
        Gui.drawRect(bounds.right - 1, bounds.top, bounds.right, bounds.bottom, outline);
    }

    private void updatePosition(int mouseX, int mouseY) {
        Minecraft minecraft = Minecraft.getMinecraft();
        ScaledResolution resolution = new ScaledResolution(minecraft);
        FontRenderer fontRenderer = minecraft.fontRendererObj;
        int previewWidth = hudModule.getPreviewWidth(minecraft);
        int previewHeight = hudModule.getPreviewHeight(minecraft);
        int anchorX = mouseX - dragOffsetX;
        int anchorY = mouseY - dragOffsetY;
        boolean rightAligned = anchorX >= resolution.getScaledWidth() / 2;

        if (rightAligned) {
            anchorX = Math.max(previewWidth, Math.min(anchorX, resolution.getScaledWidth()));
        } else {
            anchorX = Math.max(0, Math.min(anchorX, Math.max(0, resolution.getScaledWidth() - previewWidth)));
        }

        int maxY = Math.max(0, resolution.getScaledHeight() - previewHeight - Math.max(0, fontRenderer.FONT_HEIGHT));
        anchorY = Math.max(0, Math.min(anchorY, maxY));
        hudModule.setPosition(anchorX, anchorY);
    }

    private Bounds getPreviewBounds() {
        Minecraft minecraft = Minecraft.getMinecraft();
        int anchorX = hudModule.getAnchorX();
        int anchorY = hudModule.getAnchorY();
        int previewWidth = hudModule.getPreviewWidth(minecraft);
        int previewHeight = hudModule.getPreviewHeight(minecraft);
        boolean rightAligned = hudModule.isRightAligned(new ScaledResolution(minecraft));
        int left = rightAligned ? anchorX - previewWidth - PADDING : anchorX - PADDING;
        int right = rightAligned ? anchorX + PADDING : anchorX + previewWidth + PADDING;
        int top = anchorY - PADDING;
        int bottom = anchorY + previewHeight + PADDING;
        return new Bounds(left, top, right, bottom);
    }

    private static final class Bounds {
        private final int left;
        private final int top;
        private final int right;
        private final int bottom;

        private Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        private boolean contains(int mouseX, int mouseY) {
            return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom;
        }
    }
}
