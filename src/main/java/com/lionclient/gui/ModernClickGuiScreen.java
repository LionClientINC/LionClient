package com.lionclient.gui;

import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.module.ModuleManager;
import com.lionclient.feature.module.impl.ClickGuiModule;
import com.lionclient.feature.setting.ActionSetting;
import com.lionclient.feature.setting.BooleanSetting;
import com.lionclient.feature.setting.DecimalSetting;
import com.lionclient.feature.setting.EnumSetting;
import com.lionclient.feature.setting.NumberSetting;
import com.lionclient.feature.setting.Setting;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

public final class ModernClickGuiScreen extends GuiScreen {
    private static final int PANEL_MARGIN = 46;
    private static final int HEADER_HEIGHT = 54;
    private static final int INNER_PADDING = 14;
    private static final int MODULE_ROW_HEIGHT = 27;
    private static final int MODULE_ROW_GAP = 5;
    private static final int MODULE_SECTION_TITLE_HEIGHT = 20;
    private static final int SETTINGS_HEADER_HEIGHT = 58;
    private static final int SETTING_ROW_GAP = 6;
    private static final int DEFAULT_SNOWFLAKE_COUNT = 110;
    private static final float WINDOW_RADIUS = 11.0F;
    private static final float SLIDER_HINT_SCALE = 0.55F;

    private final ModuleManager moduleManager;
    private final Random random = new Random();
    private final List<Snowflake> snowflakes = new ArrayList<Snowflake>();
    private final EnumMap<Category, Float> categoryAnimations = new EnumMap<Category, Float>(Category.class);
    private final Map<Module, Float> moduleAnimations = new HashMap<Module, Float>();
    private final Map<Module, Float> moduleToggleAnimations = new HashMap<Module, Float>();
    private final Map<Setting, Float> booleanAnimations = new IdentityHashMap<Setting, Float>();
    private final Map<Setting, Float> sliderAnimations = new IdentityHashMap<Setting, Float>();

    private Category selectedCategory;
    private Module selectedModule;
    private Module bindingModule;
    private Setting draggingSetting;
    private Integer windowX;
    private Integer windowY;
    private boolean draggingWindow;
    private int dragOffsetX;
    private int dragOffsetY;
    private float openProgress;
    private float moduleScroll;
    private float moduleScrollTarget;
    private float settingsScroll;
    private float settingsScrollTarget;
    private long lastFrameTime;

    public ModernClickGuiScreen(ModuleManager moduleManager) {
        this.moduleManager = moduleManager;
        this.selectedCategory = Category.COMBAT;
        for (Category category : Category.values()) {
            categoryAnimations.put(category, Float.valueOf(0.0F));
        }
    }

    @Override
    public void initGui() {
        openProgress = 0.0F;
        moduleScroll = 0.0F;
        moduleScrollTarget = 0.0F;
        settingsScroll = 0.0F;
        settingsScrollTarget = 0.0F;
        bindingModule = null;
        draggingSetting = null;
        draggingWindow = false;
        lastFrameTime = 0L;
        ensureSelection();
        initializeSnowflakes();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        float delta = getDeltaSeconds();
        openProgress = animate(openProgress, 1.0F, delta * 8.0F);
        updateSnowflakes(delta);

        if (draggingWindow) {
            setWindowPosition(mouseX - dragOffsetX, mouseY - dragOffsetY);
        }

        Layout layout = createLayout();
        int accent = ClickGuiModule.getAccentColor();

        drawBackdrop(layout);
        drawWindowShadow(layout);
        drawWindow(layout, accent);
        beginScissor(layout.windowBounds);
        drawSnowflakes(layout.windowBounds, 0.55F);
        endScissor();
        drawHeader(layout, mouseX, mouseY, accent);
        drawModulePane(layout, mouseX, mouseY, accent, delta);
        drawSettingsPane(layout, mouseX, mouseY, accent, delta);
        beginScissor(layout.windowBounds);
        drawSnowflakes(layout.windowBounds, 0.16F);
        endScissor();

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        Layout layout = createLayout();
        if (!layout.windowBounds.contains(mouseX, mouseY)) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }

        if (mouseButton == 0 && getDragBounds(layout).contains(mouseX, mouseY)) {
            draggingWindow = true;
            dragOffsetX = mouseX - layout.windowX;
            dragOffsetY = mouseY - layout.windowY;
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }

        if (handleCategoryClick(layout, mouseX, mouseY)
            || handleModuleClick(layout, mouseX, mouseY, mouseButton)
            || handleHeaderToggleClick(layout, mouseX, mouseY, mouseButton)
            || handleSettingClick(layout, mouseX, mouseY, mouseButton)) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }

        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        draggingWindow = false;
        if (draggingSetting instanceof NumberSetting) {
            ((NumberSetting) draggingSetting).setValue(((NumberSetting) draggingSetting).getValue(), true);
            normalizeNumberRanges(selectedModule);
        } else if (draggingSetting instanceof DecimalSetting) {
            ((DecimalSetting) draggingSetting).setValue(((DecimalSetting) draggingSetting).getValue(), true);
        }
        draggingSetting = null;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (bindingModule != null) {
            if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_BACK || keyCode == Keyboard.KEY_DELETE) {
                if (bindingModule.canBeUnbound()) {
                    bindingModule.setKeyCode(Keyboard.KEY_NONE);
                }
            } else {
                bindingModule.setKeyCode(keyCode);
            }

            bindingModule = null;
            return;
        }

        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }

        Layout layout = createLayout();
        int mouseX = Mouse.getEventX() * this.width / this.mc.displayWidth;
        int mouseY = this.height - Mouse.getEventY() * this.height / this.mc.displayHeight - 1;
        float amount = wheel > 0 ? -34.0F : 34.0F;

        if (layout.moduleScrollBounds.contains(mouseX, mouseY)) {
            moduleScrollTarget += amount;
        } else if (layout.settingsScrollBounds.contains(mouseX, mouseY)) {
            settingsScrollTarget += amount;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        draggingWindow = false;
        draggingSetting = null;
        bindingModule = null;
    }

    private boolean handleCategoryClick(Layout layout, int mouseX, int mouseY) {
        int index = 0;
        for (Category category : Category.values()) {
            Bounds bounds = getCategoryBounds(layout, index);
            if (bounds.contains(mouseX, mouseY)) {
                selectedCategory = category;
                selectedModule = null;
                moduleScroll = 0.0F;
                moduleScrollTarget = 0.0F;
                settingsScroll = 0.0F;
                settingsScrollTarget = 0.0F;
                draggingSetting = null;
                bindingModule = null;
                ensureSelection();
                return true;
            }
            index++;
        }
        return false;
    }

    private boolean handleModuleClick(Layout layout, int mouseX, int mouseY, int mouseButton) {
        if (mouseButton != 0) {
            return false;
        }

        List<Module> modules = moduleManager.getModules(selectedCategory);
        int rowY = layout.moduleContentTop - Math.round(moduleScroll);
        for (Module module : modules) {
            Bounds rowBounds = new Bounds(layout.modulePaneX + 6, rowY, layout.modulePaneX + layout.modulePaneWidth - 6, rowY + MODULE_ROW_HEIGHT);
            if (rowBounds.contains(mouseX, mouseY) && layout.moduleScrollBounds.contains(mouseX, mouseY)) {
                selectedModule = module;
                settingsScroll = 0.0F;
                settingsScrollTarget = 0.0F;
                draggingSetting = null;
                bindingModule = null;
                return true;
            }
            rowY += MODULE_ROW_HEIGHT + MODULE_ROW_GAP;
        }
        return false;
    }

    private boolean handleHeaderToggleClick(Layout layout, int mouseX, int mouseY, int mouseButton) {
        if (selectedModule == null || mouseButton != 0) {
            return false;
        }

        Bounds toggleBounds = getHeaderToggleBounds(layout);
        if (!toggleBounds.contains(mouseX, mouseY)) {
            return false;
        }

        selectedModule.toggle();
        return true;
    }

    private boolean handleSettingClick(Layout layout, int mouseX, int mouseY, int mouseButton) {
        if (selectedModule == null || !layout.settingsScrollBounds.contains(mouseX, mouseY)) {
            return false;
        }

        int rowY = layout.settingsContentTop - Math.round(settingsScroll);
        List<Setting> visibleSettings = getVisibleSettings(selectedModule);
        for (Setting setting : visibleSettings) {
            int rowHeight = getSettingHeight(setting);
            Bounds rowBounds = new Bounds(layout.settingsPaneX + 10, rowY, layout.settingsPaneRight - 10, rowY + rowHeight);
            if (rowBounds.contains(mouseX, mouseY)) {
                if (setting instanceof BooleanSetting && mouseButton == 0) {
                    ((BooleanSetting) setting).toggle();
                    return true;
                }

                if (setting instanceof EnumSetting && (mouseButton == 0 || mouseButton == 1)) {
                    EnumSetting<?> enumSetting = (EnumSetting<?>) setting;
                    if (mouseButton == 0) {
                        enumSetting.cycleForward();
                    } else {
                        enumSetting.cycleBackward();
                    }
                    ensureSelection();
                    return true;
                }

                if (setting instanceof ActionSetting && mouseButton == 0) {
                    ((ActionSetting) setting).run();
                    ensureSelection();
                    return true;
                }

                if ((setting instanceof NumberSetting || setting instanceof DecimalSetting) && mouseButton == 0) {
                    Bounds sliderBounds = getSliderBounds(rowBounds);
                    if (sliderBounds.contains(mouseX, mouseY) || rowBounds.contains(mouseX, mouseY)) {
                        draggingSetting = setting;
                        applySliderValue(setting, mouseX, sliderBounds, false);
                        return true;
                    }
                }

                return false;
            }
            rowY += rowHeight + SETTING_ROW_GAP;
        }

        if (!selectedModule.showsKeybindSetting()) {
            return false;
        }

        Bounds keybindBounds = new Bounds(layout.settingsPaneX + 10, rowY, layout.settingsPaneRight - 10, rowY + 38);
        if (!keybindBounds.contains(mouseX, mouseY)) {
            return false;
        }

        if (mouseButton == 0) {
            bindingModule = selectedModule;
            return true;
        }

        if (mouseButton == 1 && selectedModule.canBeUnbound()) {
            selectedModule.setKeyCode(Keyboard.KEY_NONE);
            bindingModule = null;
            return true;
        }

        return false;
    }

    private void drawBackdrop(Layout layout) {
        drawRoundedRect(layout.windowX - 14, layout.windowY - 14, layout.windowRight + 14, layout.windowBottom + 14, WINDOW_RADIUS + 6.0F, 0x12040912);
    }

    private void drawWindowShadow(Layout layout) {
        drawRoundedRect(layout.windowX - 6, layout.windowY - 6, layout.windowRight + 6, layout.windowBottom + 6, WINDOW_RADIUS + 4.0F, 0x22010206);
        drawRoundedRect(layout.windowX - 2, layout.windowY - 2, layout.windowRight + 2, layout.windowBottom + 2, WINDOW_RADIUS + 2.0F, 0x2A08111F);
    }

    private void drawWindow(Layout layout, int accent) {
        drawRoundedRect(layout.windowX, layout.windowY, layout.windowRight, layout.windowBottom, WINDOW_RADIUS + 1.0F, 0x382F5C9C);
        drawRoundedRect(layout.windowX + 1, layout.windowY + 1, layout.windowRight - 1, layout.windowBottom - 1, WINDOW_RADIUS, 0xEE132139);
        drawRoundedRect(layout.windowX + 1, layout.windowY + 1, layout.windowRight - 1, layout.windowY + HEADER_HEIGHT + 10, WINDOW_RADIUS, 0xF0182944);
        drawRoundedRect(layout.windowX + 1, layout.windowY + HEADER_HEIGHT - 10, layout.windowRight - 1, layout.windowBottom - 1, WINDOW_RADIUS, 0xEA141E31);
        Gui.drawRect(layout.windowX, layout.windowY + HEADER_HEIGHT, layout.windowRight, layout.windowY + HEADER_HEIGHT + 1, withAlpha(accent, 180));
        Gui.drawRect(layout.modulePaneX + layout.modulePaneWidth + 14, layout.windowY + HEADER_HEIGHT + 16, layout.modulePaneX + layout.modulePaneWidth + 15, layout.windowBottom - 16, 0x5A2E517D);
    }

    private void drawHeader(Layout layout, int mouseX, int mouseY, int accent) {
        this.fontRendererObj.drawStringWithShadow("LIONCLIENT", layout.windowX + 16, layout.windowY + 15, 0xFFF5F8FF);
        this.fontRendererObj.drawString("control center", layout.windowX + 16, layout.windowY + 27, 0xFF87A1C7);
        this.fontRendererObj.drawString("ESC to close", layout.windowRight - 70, layout.windowY + 16, 0xFF7991B5);

        int index = 0;
        for (Category category : Category.values()) {
            Bounds bounds = getCategoryBounds(layout, index);
            boolean hovered = bounds.contains(mouseX, mouseY);
            float animation = getAnimation(categoryAnimations, category, selectedCategory == category ? 1.0F : hovered ? 0.45F : 0.0F, 10.0F);
            int fill = withAlpha(mixColor(0x1A253A, accent, animation * 0.25F), 150 + (int) (55.0F * animation));
            drawRoundedRect(bounds.left, bounds.top, bounds.right, bounds.bottom, 5.0F, fill);
            if (selectedCategory == category) {
                drawRoundedRect(bounds.left, bounds.bottom - 3, bounds.right, bounds.bottom, 2.5F, withAlpha(accent, 220));
            }

            int labelColor = mixColor(0x7F95B8, 0xFFFFFF, selectedCategory == category ? 0.92F : hovered ? 0.45F : 0.12F);
            String label = category.name();
            int textX = bounds.left + (bounds.getWidth() - this.fontRendererObj.getStringWidth(label)) / 2;
            this.fontRendererObj.drawString(label, textX, bounds.top + 8, labelColor);
            index++;
        }
    }

    private void drawModulePane(Layout layout, int mouseX, int mouseY, int accent, float delta) {
        drawRoundedRect(layout.modulePaneX, layout.modulePaneY, layout.modulePaneX + layout.modulePaneWidth, layout.modulePaneBottom, 8.0F, 0xD213223A);
        drawRoundedOutline(layout.modulePaneX, layout.modulePaneY, layout.modulePaneX + layout.modulePaneWidth, layout.modulePaneBottom, 8.0F, 0x22345A8A);
        this.fontRendererObj.drawString("Modules", layout.modulePaneX + 10, layout.modulePaneY + 7, 0xFFE9F0FF);
        drawScaledText("Click to select", layout.modulePaneX + 10, layout.modulePaneY + 18, 0xFF7890B6, 0.75F);

        List<Module> modules = moduleManager.getModules(selectedCategory);
        float maxScroll = Math.max(0.0F, modules.size() * (MODULE_ROW_HEIGHT + MODULE_ROW_GAP) - MODULE_ROW_GAP - layout.moduleScrollBounds.getHeight());
        moduleScrollTarget = clamp(moduleScrollTarget, 0.0F, maxScroll);
        moduleScroll = animate(moduleScroll, moduleScrollTarget, delta * 14.0F);

        if (modules.isEmpty()) {
            drawCenteredString(this.fontRendererObj, "No modules", layout.modulePaneX + (layout.modulePaneWidth / 2), layout.modulePaneY + 48, 0xFF8AA2C7);
            return;
        }

        beginScissor(layout.moduleScrollBounds);
        int rowY = layout.moduleContentTop - Math.round(moduleScroll);
        for (Module module : modules) {
            Bounds rowBounds = new Bounds(layout.modulePaneX + 6, rowY, layout.modulePaneX + layout.modulePaneWidth - 6, rowY + MODULE_ROW_HEIGHT);
            if (rowBounds.bottom >= layout.moduleScrollBounds.top && rowBounds.top <= layout.moduleScrollBounds.bottom) {
                boolean hovered = rowBounds.contains(mouseX, mouseY) && layout.moduleScrollBounds.contains(mouseX, mouseY);
                float selectionAnimation = getAnimation(moduleAnimations, module, selectedModule == module ? 1.0F : hovered ? 0.45F : 0.0F, 13.0F);
                float toggleAnimation = getAnimation(moduleToggleAnimations, module, module.isEnabled() ? 1.0F : 0.0F, 11.0F);
                int rowColor = withAlpha(mixColor(0x172437, accent, selectionAnimation * 0.18F), 165 + (int) (45.0F * selectionAnimation));
                drawRoundedRect(rowBounds.left, rowBounds.top, rowBounds.right, rowBounds.bottom, 6.0F, rowColor);
                if (selectedModule == module) {
                    Gui.drawRect(rowBounds.left, rowBounds.top, rowBounds.left + 3, rowBounds.bottom, withAlpha(accent, 230));
                }

                int nameColor = mixColor(0xA8BCD9, 0xFFFFFF, Math.max(selectionAnimation * 0.6F, toggleAnimation * 0.75F));
                this.fontRendererObj.drawString(module.getName(), rowBounds.left + 10, rowBounds.top + 7, nameColor);
                drawModuleStateBubble(getModuleToggleBounds(rowBounds), toggleAnimation, accent);
            }
            rowY += MODULE_ROW_HEIGHT + MODULE_ROW_GAP;
        }
        endScissor();
    }

    private void drawSettingsPane(Layout layout, int mouseX, int mouseY, int accent, float delta) {
        drawRoundedRect(layout.settingsPaneX, layout.settingsPaneY, layout.settingsPaneRight, layout.settingsPaneBottom, 8.0F, 0xD0152339);
        drawRoundedOutline(layout.settingsPaneX, layout.settingsPaneY, layout.settingsPaneRight, layout.settingsPaneBottom, 8.0F, 0x22345A8A);

        if (selectedModule == null) {
            drawEmptySettingsState(layout);
            return;
        }

        this.fontRendererObj.drawStringWithShadow(selectedModule.getName(), layout.settingsPaneX + 16, layout.settingsPaneY + 10, 0xFFF7FAFF);
        this.fontRendererObj.drawString(selectedModule.getDescription(), layout.settingsPaneX + 16, layout.settingsPaneY + 22, 0xFF88A1C6);
        drawScaledText(selectedCategory.name(), layout.settingsPaneX + 16, layout.settingsPaneY + 35, 0xFF6482AE, 0.8F);
        drawHeaderToggle(getHeaderToggleBounds(layout), selectedModule.isEnabled(), accent);

        List<Setting> visibleSettings = getVisibleSettings(selectedModule);
        float contentHeight = 0.0F;
        for (Setting setting : visibleSettings) {
            contentHeight += getSettingHeight(setting) + SETTING_ROW_GAP;
        }
        if (selectedModule.showsKeybindSetting()) {
            contentHeight += 38 + SETTING_ROW_GAP;
        }

        float maxScroll = Math.max(0.0F, contentHeight - layout.settingsScrollBounds.getHeight());
        settingsScrollTarget = clamp(settingsScrollTarget, 0.0F, maxScroll);
        settingsScroll = animate(settingsScroll, settingsScrollTarget, delta * 14.0F);

        beginScissor(layout.settingsScrollBounds);
        int rowY = layout.settingsContentTop - Math.round(settingsScroll);
        for (Setting setting : visibleSettings) {
            int rowHeight = getSettingHeight(setting);
            Bounds rowBounds = new Bounds(layout.settingsPaneX + 10, rowY, layout.settingsPaneRight - 10, rowY + rowHeight);
            if (rowBounds.bottom >= layout.settingsScrollBounds.top && rowBounds.top <= layout.settingsScrollBounds.bottom) {
                drawSettingCard(rowBounds, setting, mouseX, mouseY, accent);
            }
            rowY += rowHeight + SETTING_ROW_GAP;
        }

        if (selectedModule.showsKeybindSetting()) {
            Bounds rowBounds = new Bounds(layout.settingsPaneX + 10, rowY, layout.settingsPaneRight - 10, rowY + 38);
            if (rowBounds.bottom >= layout.settingsScrollBounds.top && rowBounds.top <= layout.settingsScrollBounds.bottom) {
                drawKeybindCard(rowBounds, selectedModule, accent);
            }
        }
        endScissor();
    }

    private void drawEmptySettingsState(Layout layout) {
        this.fontRendererObj.drawStringWithShadow("Select a module", layout.settingsPaneX + 16, layout.settingsPaneY + 18, 0xFFF7FAFF);
        this.fontRendererObj.drawString("Pick something on the left to edit its settings.", layout.settingsPaneX + 16, layout.settingsPaneY + 32, 0xFF88A1C6);
    }

    private void drawSettingCard(Bounds rowBounds, Setting setting, int mouseX, int mouseY, int accent) {
        boolean hovered = rowBounds.contains(mouseX, mouseY);
        drawRoundedRect(rowBounds.left, rowBounds.top, rowBounds.right, rowBounds.bottom, 6.0F, hovered ? 0xD8172740 : 0xC6132139);
        drawRoundedOutline(rowBounds.left, rowBounds.top, rowBounds.right, rowBounds.bottom, 6.0F, hovered ? 0x33598AC8 : 0x1F345A8A);
        this.fontRendererObj.drawString(setting.getName(), rowBounds.left + 12, rowBounds.top + 10, 0xFFF1F6FF);

        if (setting instanceof BooleanSetting) {
            float progress = getAnimation(booleanAnimations, setting, ((BooleanSetting) setting).isEnabled() ? 1.0F : 0.0F, 14.0F);
            drawBooleanControl(new Bounds(rowBounds.right - 28, rowBounds.top + 11, rowBounds.right - 12, rowBounds.top + 27), progress, hovered, accent);
            this.fontRendererObj.drawString(((BooleanSetting) setting).isEnabled() ? "Enabled" : "Disabled", rowBounds.left + 12, rowBounds.top + 22, 0xFF83A0C8);
            return;
        }

        if (setting instanceof NumberSetting || setting instanceof DecimalSetting) {
            Bounds sliderBounds = getSliderBounds(rowBounds);
            if (draggingSetting == setting) {
                applySliderValue(setting, mouseX, sliderBounds, false);
            }

            float target = getSliderTarget(setting);
            float sliderProgress = getAnimation(sliderAnimations, setting, target, 14.0F);
            String valueText = setting.getValueText();
            this.fontRendererObj.drawString(valueText, rowBounds.right - 12 - this.fontRendererObj.getStringWidth(valueText), rowBounds.top + 10, 0xFF9DBCF5);
            Gui.drawRect(sliderBounds.left, sliderBounds.top, sliderBounds.right, sliderBounds.bottom, 0xFF0E1625);
            Gui.drawRect(sliderBounds.left, sliderBounds.top, sliderBounds.left + Math.round(sliderBounds.getWidth() * sliderProgress), sliderBounds.bottom, withAlpha(accent, 205));
            int knobX = sliderBounds.left + Math.round(sliderBounds.getWidth() * sliderProgress);
            Gui.drawRect(knobX - 2, sliderBounds.top - 2, knobX + 2, sliderBounds.bottom + 2, 0xFFF1F6FF);
            drawScaledText("drag to adjust", rowBounds.left + 12, rowBounds.top + 20, 0xFF7590B6, SLIDER_HINT_SCALE);
            return;
        }

        if (setting instanceof EnumSetting) {
            String valueText = setting.getValueText();
            int chipWidth = Math.max(64, this.fontRendererObj.getStringWidth(valueText) + 22);
            Bounds chipBounds = new Bounds(rowBounds.right - chipWidth - 12, rowBounds.top + 8, rowBounds.right - 12, rowBounds.bottom - 8);
            drawRoundedRect(chipBounds.left, chipBounds.top, chipBounds.right, chipBounds.bottom, 4.0F, 0xD41A2B48);
            drawRoundedOutline(chipBounds.left, chipBounds.top, chipBounds.right, chipBounds.bottom, 4.0F, 0x33598AC8);
            this.fontRendererObj.drawString("<", chipBounds.left + 8, chipBounds.top + 6, 0xFF8AA7D5);
            this.fontRendererObj.drawString(">", chipBounds.right - 12, chipBounds.top + 6, 0xFF8AA7D5);
            this.fontRendererObj.drawString(valueText, chipBounds.left + (chipBounds.getWidth() - this.fontRendererObj.getStringWidth(valueText)) / 2, chipBounds.top + 6, 0xFFF3F7FF);
            this.fontRendererObj.drawString("LMB/RMB cycle", rowBounds.left + 12, rowBounds.top + 22, 0xFF83A0C8);
            return;
        }

        if (setting instanceof ActionSetting) {
            String valueText = setting.getValueText();
            int chipWidth = Math.max(72, this.fontRendererObj.getStringWidth(valueText) + 26);
            Bounds chipBounds = new Bounds(rowBounds.right - chipWidth - 12, rowBounds.top + 8, rowBounds.right - 12, rowBounds.bottom - 8);
            drawRoundedRect(chipBounds.left, chipBounds.top, chipBounds.right, chipBounds.bottom, 4.0F, withAlpha(accent, 210));
            drawRoundedOutline(chipBounds.left, chipBounds.top, chipBounds.right, chipBounds.bottom, 4.0F, withAlpha(accent, 220));
            this.fontRendererObj.drawString(valueText, chipBounds.left + (chipBounds.getWidth() - this.fontRendererObj.getStringWidth(valueText)) / 2, chipBounds.top + 6, 0xFFF8FBFF);
            this.fontRendererObj.drawString("Click to run", rowBounds.left + 12, rowBounds.top + 22, 0xFF83A0C8);
            return;
        }

        String valueText = setting.getValueText();
        this.fontRendererObj.drawString(valueText, rowBounds.right - 12 - this.fontRendererObj.getStringWidth(valueText), rowBounds.top + 10, 0xFF9DBCF5);
    }

    private void drawKeybindCard(Bounds rowBounds, Module module, int accent) {
        drawRoundedRect(rowBounds.left, rowBounds.top, rowBounds.right, rowBounds.bottom, 6.0F, 0xC8132139);
        drawRoundedOutline(rowBounds.left, rowBounds.top, rowBounds.right, rowBounds.bottom, 6.0F, 0x1F345A8A);
        this.fontRendererObj.drawString("Keybind", rowBounds.left + 12, rowBounds.top + 10, 0xFFF1F6FF);
        String valueText = bindingModule == module ? "Press key..." : getKeybindText(module);
        int chipWidth = Math.max(92, this.fontRendererObj.getStringWidth(valueText) + 24);
        Bounds chipBounds = new Bounds(rowBounds.right - chipWidth - 12, rowBounds.top + 8, rowBounds.right - 12, rowBounds.bottom - 8);
        int fill = bindingModule == module ? withAlpha(accent, 180) : withAlpha(0x1B2D49, 145);
        int outline = bindingModule == module ? withAlpha(accent, 235) : withAlpha(accent, 125);
        drawRoundedRect(chipBounds.left, chipBounds.top, chipBounds.right, chipBounds.bottom, 4.0F, fill);
        drawRoundedOutline(chipBounds.left, chipBounds.top, chipBounds.right, chipBounds.bottom, 4.0F, outline);
        this.fontRendererObj.drawString(valueText, chipBounds.left + (chipBounds.getWidth() - this.fontRendererObj.getStringWidth(valueText)) / 2, chipBounds.top + 6, 0xFFF7FBFF);
    }

    private void drawModuleStateBubble(Bounds bounds, float progress, int accent) {
        int centerX = bounds.left + (bounds.getWidth() / 2);
        int centerY = bounds.top + (bounds.getHeight() / 2);
        drawFilledCircle(centerX, centerY, 5.5F, 0xFF10192A);
        drawFilledCircle(centerX, centerY, 4.0F + (progress * 1.4F), withAlpha(mixColor(0x29405F, accent, progress), 110 + (int) (110.0F * progress)));
        drawCircleOutline(centerX, centerY, 5.5F, withAlpha(accent, 95 + (int) (95.0F * progress)));
    }

    private void drawBooleanControl(Bounds bounds, float progress, boolean hovered, int accent) {
        drawRoundedRect(bounds.left, bounds.top, bounds.right, bounds.bottom, 4.0F, hovered ? 0xFF18263A : 0xFF111B2A);
        drawRoundedOutline(bounds.left, bounds.top, bounds.right, bounds.bottom, 4.0F, withAlpha(accent, 90 + (int) (95.0F * progress)));
        if (progress > 0.02F) {
            drawRoundedRect(bounds.left + 1, bounds.top + 1, bounds.right - 1, bounds.bottom - 1, 3.0F, withAlpha(accent, 90 + (int) (75.0F * progress)));
            drawCheckmark(bounds.left + 4, bounds.top + 3, withAlpha(0xFFFFFF, (int) (255.0F * progress)), progress);
        }
    }

    private void drawHeaderToggle(Bounds bounds, boolean enabled, int accent) {
        drawRoundedRect(bounds.left, bounds.top, bounds.right, bounds.bottom, 4.5F, enabled ? withAlpha(accent, 165) : 0xAA1A2B48);
        drawRoundedOutline(bounds.left, bounds.top, bounds.right, bounds.bottom, 4.5F, enabled ? withAlpha(accent, 235) : 0x33598AC8);
        String label = enabled ? "Enabled" : "Disabled";
        this.fontRendererObj.drawString(label, bounds.left + (bounds.getWidth() - this.fontRendererObj.getStringWidth(label)) / 2, bounds.top + 7, 0xFFF7FBFF);
    }

    private void applySliderValue(Setting setting, int mouseX, Bounds sliderBounds, boolean save) {
        float progress = clamp((mouseX - sliderBounds.left) / (float) sliderBounds.getWidth(), 0.0F, 1.0F);
        if (setting instanceof NumberSetting) {
            NumberSetting numberSetting = (NumberSetting) setting;
            int range = numberSetting.getMax() - numberSetting.getMin();
            int steps = Math.round((range * progress) / Math.max(1, numberSetting.getStep()));
            int value = numberSetting.getMin() + (steps * numberSetting.getStep());
            numberSetting.setValue(value, save);
            normalizeNumberRanges(selectedModule);
            return;
        }

        if (setting instanceof DecimalSetting) {
            DecimalSetting decimalSetting = (DecimalSetting) setting;
            double range = decimalSetting.getMax() - decimalSetting.getMin();
            double stepped = Math.round((range * progress) / decimalSetting.getStep()) * decimalSetting.getStep();
            decimalSetting.setValue(decimalSetting.getMin() + stepped, save);
        }
    }

    private float getSliderTarget(Setting setting) {
        if (setting instanceof NumberSetting) {
            NumberSetting numberSetting = (NumberSetting) setting;
            if (numberSetting.getMax() == numberSetting.getMin()) {
                return 0.0F;
            }
            return (numberSetting.getValue() - numberSetting.getMin()) / (float) (numberSetting.getMax() - numberSetting.getMin());
        }

        if (setting instanceof DecimalSetting) {
            DecimalSetting decimalSetting = (DecimalSetting) setting;
            if (decimalSetting.getMax() == decimalSetting.getMin()) {
                return 0.0F;
            }
            return (float) ((decimalSetting.getValue() - decimalSetting.getMin()) / (decimalSetting.getMax() - decimalSetting.getMin()));
        }

        return 0.0F;
    }

    private void normalizeNumberRanges(Module module) {
        if (module == null) {
            return;
        }

        NumberSetting min = null;
        NumberSetting max = null;
        for (Setting setting : module.getSettings()) {
            if (!(setting instanceof NumberSetting)) {
                continue;
            }

            if ("Min CPS".equals(setting.getName())) {
                min = (NumberSetting) setting;
            } else if ("Max CPS".equals(setting.getName())) {
                max = (NumberSetting) setting;
            }
        }

        if (min != null && max != null && max.getValue() < min.getValue()) {
            max.setValue(min.getValue(), false);
        }
    }

    private List<Setting> getVisibleSettings(Module module) {
        List<Setting> visible = new ArrayList<Setting>();
        for (Setting setting : module.getSettings()) {
            if (setting.isVisible()) {
                visible.add(setting);
            }
        }
        return visible;
    }

    private int getSettingHeight(Setting setting) {
        if (setting instanceof NumberSetting || setting instanceof DecimalSetting) {
            return 40;
        }
        return 34;
    }

    private void ensureSelection() {
        if (selectedCategory == null || moduleManager.getModules(selectedCategory).isEmpty()) {
            selectedCategory = findFirstCategory();
        }

        List<Module> modules = moduleManager.getModules(selectedCategory);
        if (modules.isEmpty()) {
            selectedModule = null;
            return;
        }

        if (selectedModule == null || selectedModule.getCategory() != selectedCategory || !modules.contains(selectedModule)) {
            selectedModule = modules.get(0);
        }
    }

    private Category findFirstCategory() {
        for (Category category : Category.values()) {
            if (!moduleManager.getModules(category).isEmpty()) {
                return category;
            }
        }
        return Category.COMBAT;
    }

    private Layout createLayout() {
        int panelWidth = getPanelWidth();
        int panelHeight = getPanelHeight();
        if (windowX == null || windowY == null) {
            windowX = Integer.valueOf((this.width - panelWidth) / 2);
            windowY = Integer.valueOf((this.height - panelHeight) / 2);
        }

        setWindowPosition(windowX.intValue(), windowY.intValue());
        int layoutWindowX = windowX.intValue();
        int baseWindowY = windowY.intValue();
        int layoutWindowY = baseWindowY + Math.round((1.0F - easeOut(openProgress)) * 18.0F);
        int windowRight = layoutWindowX + panelWidth;
        int windowBottom = layoutWindowY + panelHeight;

        int modulePaneX = layoutWindowX + INNER_PADDING;
        int modulePaneY = layoutWindowY + HEADER_HEIGHT + INNER_PADDING;
        int modulePaneWidth = 156;
        int modulePaneBottom = windowBottom - INNER_PADDING;

        int settingsPaneX = modulePaneX + modulePaneWidth + 18;
        int settingsPaneY = modulePaneY;
        int settingsPaneRight = windowRight - INNER_PADDING;
        int settingsPaneBottom = modulePaneBottom;

        return new Layout(
            layoutWindowX,
            layoutWindowY,
            windowRight,
            windowBottom,
            modulePaneX,
            modulePaneY,
            modulePaneWidth,
            modulePaneBottom,
            modulePaneY + MODULE_SECTION_TITLE_HEIGHT + 14,
            new Bounds(modulePaneX + 2, modulePaneY + 28, modulePaneX + modulePaneWidth - 2, modulePaneBottom - 6),
            settingsPaneX,
            settingsPaneY,
            settingsPaneRight,
            settingsPaneBottom,
            settingsPaneY + SETTINGS_HEADER_HEIGHT + 8,
            new Bounds(settingsPaneX + 2, settingsPaneY + SETTINGS_HEADER_HEIGHT + 2, settingsPaneRight - 2, settingsPaneBottom - 6)
        );
    }

    private int getPanelWidth() {
        return Math.min(720, this.width - PANEL_MARGIN * 2);
    }

    private int getPanelHeight() {
        return Math.min(395, this.height - PANEL_MARGIN * 2);
    }

    private void setWindowPosition(int nextX, int nextY) {
        int minX = 8;
        int minY = 8;
        int maxX = Math.max(minX, this.width - getPanelWidth() - 8);
        int maxY = Math.max(minY, this.height - getPanelHeight() - 8);
        windowX = Integer.valueOf(Math.max(minX, Math.min(maxX, nextX)));
        windowY = Integer.valueOf(Math.max(minY, Math.min(maxY, nextY)));
    }

    private Bounds getCategoryBounds(Layout layout, int index) {
        int tabWidth = 74;
        int gap = 6;
        int totalWidth = (Category.values().length * tabWidth) + ((Category.values().length - 1) * gap);
        int startX = layout.windowX + (layout.getWidth() - totalWidth) / 2;
        int left = startX + index * (tabWidth + gap);
        return new Bounds(left, layout.windowY + 12, left + tabWidth, layout.windowY + 31);
    }

    private Bounds getModuleToggleBounds(Bounds rowBounds) {
        return new Bounds(rowBounds.right - 18, rowBounds.top + 7, rowBounds.right - 4, rowBounds.top + 21);
    }

    private Bounds getHeaderToggleBounds(Layout layout) {
        return new Bounds(layout.settingsPaneRight - 102, layout.settingsPaneY + 12, layout.settingsPaneRight - 14, layout.settingsPaneY + 30);
    }

    private Bounds getDragBounds(Layout layout) {
        return new Bounds(layout.windowX + 8, layout.windowY + 8, layout.windowX + 124, layout.windowY + HEADER_HEIGHT - 12);
    }

    private Bounds getSliderBounds(Bounds rowBounds) {
        return new Bounds(rowBounds.left + 12, rowBounds.bottom - 11, rowBounds.right - 12, rowBounds.bottom - 7);
    }

    private void drawOutline(int left, int top, int right, int bottom, int color) {
        Gui.drawRect(left, top, right, top + 1, color);
        Gui.drawRect(left, bottom - 1, right, bottom, color);
        Gui.drawRect(left, top, left + 1, bottom, color);
        Gui.drawRect(right - 1, top, right, bottom, color);
    }

    private void drawRoundedOutline(float left, float top, float right, float bottom, float radius, int color) {
        if (((color >>> 24) & 255) == 0 || right <= left || bottom <= top) {
            return;
        }

        float actualRadius = Math.min(radius, Math.min((right - left) / 2.0F, (bottom - top) / 2.0F));
        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        applyColor(color);
        GL11.glLineWidth(1.0F);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        addArcVertices(right - actualRadius, top + actualRadius, -90.0F, 0.0F, actualRadius);
        addArcVertices(right - actualRadius, bottom - actualRadius, 0.0F, 90.0F, actualRadius);
        addArcVertices(left + actualRadius, bottom - actualRadius, 90.0F, 180.0F, actualRadius);
        addArcVertices(left + actualRadius, top + actualRadius, 180.0F, 270.0F, actualRadius);
        GL11.glEnd();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    private void drawRoundedRect(float left, float top, float right, float bottom, float radius, int color) {
        if (((color >>> 24) & 255) == 0 || right <= left || bottom <= top) {
            return;
        }

        int leftInt = Math.round(left);
        int topInt = Math.round(top);
        int rightInt = Math.round(right);
        int bottomInt = Math.round(bottom);
        int actualRadius = Math.round(Math.min(radius, Math.min((rightInt - leftInt) / 2.0F, (bottomInt - topInt) / 2.0F)));
        if (actualRadius <= 1) {
            Gui.drawRect(leftInt, topInt, rightInt, bottomInt, color);
            return;
        }

        Gui.drawRect(leftInt + actualRadius, topInt, rightInt - actualRadius, bottomInt, color);
        Gui.drawRect(leftInt, topInt + actualRadius, rightInt, bottomInt - actualRadius, color);

        for (int offset = 0; offset < actualRadius; offset++) {
            double distance = actualRadius - offset - 0.5D;
            int inset = (int) Math.ceil(actualRadius - Math.sqrt((actualRadius * actualRadius) - (distance * distance)));
            Gui.drawRect(leftInt + inset, topInt + offset, rightInt - inset, topInt + offset + 1, color);
            Gui.drawRect(leftInt + inset, bottomInt - offset - 1, rightInt - inset, bottomInt - offset, color);
        }
    }

    private void addArcVertices(float centerX, float centerY, float startAngle, float endAngle, float radius) {
        for (int i = 0; i <= 8; i++) {
            double angle = Math.toRadians(startAngle + ((endAngle - startAngle) * i / 8.0D));
            GL11.glVertex2d(centerX + Math.cos(angle) * radius, centerY + Math.sin(angle) * radius);
        }
    }

    private void applyColor(int color) {
        float alpha = ((color >>> 24) & 255) / 255.0F;
        float red = ((color >>> 16) & 255) / 255.0F;
        float green = ((color >>> 8) & 255) / 255.0F;
        float blue = (color & 255) / 255.0F;
        GL11.glColor4f(red, green, blue, alpha);
    }

    private void drawCheckmark(int x, int y, int color, float progress) {
        int firstLength = Math.max(1, Math.round(3.0F * progress));
        int secondLength = Math.max(0, Math.round(4.0F * Math.max(0.0F, progress - 0.25F) / 0.75F));

        for (int i = 0; i < firstLength; i++) {
            Gui.drawRect(x + i, y + 3 - i, x + i + 1, y + 4 - i, color);
        }

        for (int i = 0; i < secondLength; i++) {
            Gui.drawRect(x + 2 + i, y + i, x + 3 + i, y + i + 1, color);
        }
    }

    private void drawScaledText(String text, int x, int y, int color, float scale) {
        GlStateManager.pushMatrix();
        GlStateManager.scale(scale, scale, 1.0F);
        this.fontRendererObj.drawString(text, Math.round(x / scale), Math.round(y / scale), color);
        GlStateManager.popMatrix();
    }

    private void drawFilledCircle(float centerX, float centerY, float radius, int color) {
        if (((color >>> 24) & 255) == 0 || radius <= 0.0F) {
            return;
        }

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        applyColor(color);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f(centerX, centerY);
        for (int i = 0; i <= 24; i++) {
            double angle = Math.PI * 2.0D * i / 24.0D;
            GL11.glVertex2d(centerX + (Math.cos(angle) * radius), centerY + (Math.sin(angle) * radius));
        }
        GL11.glEnd();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    private void drawCircleOutline(float centerX, float centerY, float radius, int color) {
        if (((color >>> 24) & 255) == 0 || radius <= 0.0F) {
            return;
        }

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0);
        applyColor(color);
        GL11.glLineWidth(1.0F);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        for (int i = 0; i < 24; i++) {
            double angle = Math.PI * 2.0D * i / 24.0D;
            GL11.glVertex2d(centerX + (Math.cos(angle) * radius), centerY + (Math.sin(angle) * radius));
        }
        GL11.glEnd();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    private boolean isInsideRoundedRect(float x, float y, Bounds bounds, float radius) {
        float left = bounds.left;
        float top = bounds.top;
        float right = bounds.right;
        float bottom = bounds.bottom;

        if (x < left || x > right || y < top || y > bottom) {
            return false;
        }

        float actualRadius = Math.min(radius, Math.min((right - left) / 2.0F, (bottom - top) / 2.0F));
        if ((x >= left + actualRadius && x <= right - actualRadius) || (y >= top + actualRadius && y <= bottom - actualRadius)) {
            return true;
        }

        float nearestX = x < left + actualRadius ? left + actualRadius : right - actualRadius;
        float nearestY = y < top + actualRadius ? top + actualRadius : bottom - actualRadius;
        float dx = x - nearestX;
        float dy = y - nearestY;
        return (dx * dx) + (dy * dy) <= actualRadius * actualRadius;
    }

    private void beginScissor(Bounds bounds) {
        ScaledResolution resolution = new ScaledResolution(this.mc);
        int scaleFactor = resolution.getScaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(
            bounds.left * scaleFactor,
            this.mc.displayHeight - (bounds.bottom * scaleFactor),
            bounds.getWidth() * scaleFactor,
            bounds.getHeight() * scaleFactor
        );
    }

    private void endScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private void initializeSnowflakes() {
        int desiredCount = Math.max(DEFAULT_SNOWFLAKE_COUNT, (this.width * this.height) / 9500);
        snowflakes.clear();
        for (int i = 0; i < desiredCount; i++) {
            snowflakes.add(createSnowflake(true));
        }
    }

    private void updateSnowflakes(float delta) {
        if (snowflakes.isEmpty()) {
            initializeSnowflakes();
        }

        for (int i = 0; i < snowflakes.size(); i++) {
            Snowflake flake = snowflakes.get(i);
            flake.age += delta;
            flake.y += flake.speed * delta;
            flake.x += Math.sin(flake.age * flake.swingSpeed) * flake.swingAmount * delta;

            if (flake.y > this.height + 12 || flake.x < -20 || flake.x > this.width + 20) {
                snowflakes.set(i, createSnowflake(false));
            }
        }
    }

    private void drawSnowflakes(Bounds bounds, float alphaScale) {
        for (Snowflake flake : snowflakes) {
            float centerX = flake.x + (flake.size / 2.0F);
            float centerY = flake.y + (flake.size / 2.0F);
            if (!isInsideRoundedRect(centerX, centerY, bounds, WINDOW_RADIUS - 1.0F)) {
                continue;
            }
            int alpha = (int) (flake.alpha * alphaScale);
            int color = withAlpha(0xDCEAFF, alpha);
            drawFilledCircle(centerX, centerY, flake.size / 2.0F, color);
        }
    }

    private Snowflake createSnowflake(boolean randomY) {
        float startY = randomY ? random.nextFloat() * Math.max(1, this.height) : -8.0F - random.nextFloat() * 18.0F;
        return new Snowflake(
            random.nextFloat() * Math.max(1, this.width),
            startY,
            4 + random.nextInt(5),
            26.0F + random.nextFloat() * 38.0F,
            8.0F + random.nextFloat() * 16.0F,
            1.5F + random.nextFloat() * 2.5F,
            110 + random.nextInt(90)
        );
    }

    private float getDeltaSeconds() {
        long now = System.currentTimeMillis();
        if (lastFrameTime == 0L) {
            lastFrameTime = now;
            return 0.016F;
        }

        float delta = (now - lastFrameTime) / 1000.0F;
        lastFrameTime = now;
        return clamp(delta, 0.0F, 0.05F);
    }

    private String getKeybindText(Module module) {
        if (module.getKeyCode() == Keyboard.KEY_NONE) {
            return "NONE";
        }

        String name = Keyboard.getKeyName(module.getKeyCode());
        return name == null ? "UNKNOWN" : name.toUpperCase();
    }

    private static float animate(float current, float target, float speed) {
        return current + ((target - current) * clamp(speed, 0.0F, 1.0F));
    }

    private static float easeOut(float value) {
        float inverse = 1.0F - clamp(value, 0.0F, 1.0F);
        return 1.0F - inverse * inverse * inverse;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int withAlpha(int color, int alpha) {
        return ((alpha & 255) << 24) | (color & 0x00FFFFFF);
    }

    private static int mixColor(int start, int end, float progress) {
        float amount = clamp(progress, 0.0F, 1.0F);
        int startA = (start >>> 24) & 255;
        int startR = (start >>> 16) & 255;
        int startG = (start >>> 8) & 255;
        int startB = start & 255;
        int endA = (end >>> 24) & 255;
        int endR = (end >>> 16) & 255;
        int endG = (end >>> 8) & 255;
        int endB = end & 255;
        int alpha = Math.round(startA + ((endA - startA) * amount));
        int red = Math.round(startR + ((endR - startR) * amount));
        int green = Math.round(startG + ((endG - startG) * amount));
        int blue = Math.round(startB + ((endB - startB) * amount));
        return (alpha << 24) | (red << 16) | (green << 8) | blue;
    }

    private static <T> float getAnimation(Map<T, Float> map, T key, float target, float speed) {
        Float current = map.get(key);
        float value = animate(current == null ? 0.0F : current.floatValue(), target, speed * 0.016F);
        map.put(key, Float.valueOf(value));
        return value;
    }

    private static final class Layout {
        private final int windowX;
        private final int windowY;
        private final int windowRight;
        private final int windowBottom;
        private final Bounds windowBounds;
        private final int modulePaneX;
        private final int modulePaneY;
        private final int modulePaneWidth;
        private final int modulePaneBottom;
        private final int moduleContentTop;
        private final Bounds moduleScrollBounds;
        private final int settingsPaneX;
        private final int settingsPaneY;
        private final int settingsPaneRight;
        private final int settingsPaneBottom;
        private final int settingsContentTop;
        private final Bounds settingsScrollBounds;

        private Layout(
            int windowX,
            int windowY,
            int windowRight,
            int windowBottom,
            int modulePaneX,
            int modulePaneY,
            int modulePaneWidth,
            int modulePaneBottom,
            int moduleContentTop,
            Bounds moduleScrollBounds,
            int settingsPaneX,
            int settingsPaneY,
            int settingsPaneRight,
            int settingsPaneBottom,
            int settingsContentTop,
            Bounds settingsScrollBounds
        ) {
            this.windowX = windowX;
            this.windowY = windowY;
            this.windowRight = windowRight;
            this.windowBottom = windowBottom;
            this.windowBounds = new Bounds(windowX, windowY, windowRight, windowBottom);
            this.modulePaneX = modulePaneX;
            this.modulePaneY = modulePaneY;
            this.modulePaneWidth = modulePaneWidth;
            this.modulePaneBottom = modulePaneBottom;
            this.moduleContentTop = moduleContentTop;
            this.moduleScrollBounds = moduleScrollBounds;
            this.settingsPaneX = settingsPaneX;
            this.settingsPaneY = settingsPaneY;
            this.settingsPaneRight = settingsPaneRight;
            this.settingsPaneBottom = settingsPaneBottom;
            this.settingsContentTop = settingsContentTop;
            this.settingsScrollBounds = settingsScrollBounds;
        }

        private int getWidth() {
            return windowRight - windowX;
        }
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

        private int getWidth() {
            return right - left;
        }

        private int getHeight() {
            return bottom - top;
        }
    }

    private static final class Snowflake {
        private float x;
        private float y;
        private final int size;
        private final float speed;
        private final float swingAmount;
        private final float swingSpeed;
        private final int alpha;
        private float age;

        private Snowflake(float x, float y, int size, float speed, float swingAmount, float swingSpeed, int alpha) {
            this.x = x;
            this.y = y;
            this.size = size;
            this.speed = speed;
            this.swingAmount = swingAmount;
            this.swingSpeed = swingSpeed;
            this.alpha = alpha;
            this.age = 0.0F;
        }
    }
}
