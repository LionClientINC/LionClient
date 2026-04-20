package com.lionclient.gui;

import com.lionclient.LionClient;
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
    private static final int SETTINGS_HEADER_HEIGHT = 58;
    private static final int SETTING_ROW_GAP = 6;
    private static final int DEFAULT_SNOWFLAKE_COUNT = 110;
    private static final float WINDOW_RADIUS = 11.0F;
    private static final int ENUM_OPTION_HEIGHT = 20;
    private static final int CONTROL_WIDTH = 240;
    private static final int CONTROL_HEIGHT = 28;
    private static final int CONTROL_PADDING = 12;
    private static final int SLIDER_TRACK_HEIGHT = 6;
    private static final int CHECKBOX_SIZE = 14;
    private static final int SWITCH_WIDTH = 28;
    private static final int SWITCH_HEIGHT = 16;
    private static final int BIND_BUTTON_WIDTH = 100;
    private static final int CATEGORY_MODULE_SLIDE_DISTANCE = 24;
    private static final int CATEGORY_SETTINGS_SLIDE_DISTANCE = 34;
    private static final float SMALL_LABEL_SCALE = 0.75F;
    private static final int SURFACE_WINDOW_BORDER = 0x394652;
    private static final int SURFACE_WINDOW = 0x1C252D;
    private static final int SURFACE_WINDOW_HEADER = 0x222C36;
    private static final int SURFACE_WINDOW_BODY = 0x171F27;
    private static final int SURFACE_PANEL = 0x1F2933;
    private static final int SURFACE_PANEL_OUTLINE = 0x43505D;
    private static final int SURFACE_ROW = 0x242E38;
    private static final int SURFACE_INPUT = 0x10171E;
    private static final int SURFACE_CHIP = 0x27313B;
    private static final int TEXT_PRIMARY = 0xFFF1F4F8;
    private static final int TEXT_SECONDARY = 0xFFACB7C2;
    private static final int TEXT_MUTED = 0xFF7E8995;
    private static final int TEXT_DISABLED = 0xFF76818D;
    private static final int SNOWFLAKE_COLOR = 0xEEF2F5;
    private static final int CONTROL_BACKGROUND = 0x1C1F27;
    private static final int CONTROL_BORDER = 0x2A2D35;
    private static final int CONTROL_BORDER_LIGHT = 0x3A3D45;
    private static final int SWITCH_OFF_TRACK = 0x2A2D35;
    private static final int SWITCH_OFF_THUMB = 0x8C9098;
    private static final int SMALL_LABEL_COLOR = 0xFF8A919B;
    private static final int CHECKBOX_LABEL_COLOR = 0xFFC8CDD4;

    private final ModuleManager moduleManager;
    private final Random random = new Random();
    private final List<Snowflake> snowflakes = new ArrayList<Snowflake>();
    private final EnumMap<Category, Float> categoryAnimations = new EnumMap<Category, Float>(Category.class);
    private final Map<Module, Float> moduleAnimations = new HashMap<Module, Float>();
    private final Map<Module, Float> moduleToggleAnimations = new HashMap<Module, Float>();
    private final Map<Setting, Float> booleanAnimations = new IdentityHashMap<Setting, Float>();
    private final Map<Setting, Float> sliderAnimations = new IdentityHashMap<Setting, Float>();

    private Category selectedCategory;
    private Category previousCategory;
    private Module selectedModule;
    private Module previousModule;
    private Module bindingModule;
    private EnumSetting<?> expandedEnumSetting;
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
    private float previousModuleScroll;
    private float previousSettingsScroll;
    private float categoryTransitionProgress = 1.0F;
    private int categoryTransitionDirection = 1;
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
        expandedEnumSetting = null;
        draggingSetting = null;
        draggingWindow = false;
        clearCategoryTransition();
        lastFrameTime = 0L;
        ensureSelection();
        initializeSnowflakes();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        float delta = getDeltaSeconds();
        openProgress = animate(openProgress, 1.0F, delta * 8.0F);
        updateCategoryTransition(delta);
        updateSnowflakes(delta);

        if (draggingWindow) {
            setWindowPosition(mouseX - dragOffsetX, mouseY - dragOffsetY);
        }

        Layout layout = createLayout();
        int accent = ClickGuiModule.getModernAccentColor();

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

        if (handleCategoryClick(layout, mouseX, mouseY)) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }

        if (isCategoryTransitionActive()) {
            super.mouseClicked(mouseX, mouseY, mouseButton);
            return;
        }

        if (handleModuleClick(layout, mouseX, mouseY, mouseButton)
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
        if (wheel == 0 || isCategoryTransitionActive()) {
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
        expandedEnumSetting = null;
    }

    private boolean handleCategoryClick(Layout layout, int mouseX, int mouseY) {
        int index = 0;
        for (Category category : Category.values()) {
            Bounds bounds = getCategoryBounds(layout, index);
            if (bounds.contains(mouseX, mouseY)) {
                if (category == selectedCategory) {
                    return true;
                }

                Category oldCategory = selectedCategory;
                Module oldModule = selectedModule;
                float oldModuleScroll = moduleScroll;
                float oldSettingsScroll = settingsScroll;
                selectedCategory = category;
                selectedModule = null;
                moduleScroll = 0.0F;
                moduleScrollTarget = 0.0F;
                settingsScroll = 0.0F;
                settingsScrollTarget = 0.0F;
                draggingSetting = null;
                bindingModule = null;
                expandedEnumSetting = null;
                ensureSelection();
                beginCategoryTransition(oldCategory, oldModule, oldModuleScroll, oldSettingsScroll, category);
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
                expandedEnumSetting = null;
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

        expandedEnumSetting = null;
        selectedModule.toggle();
        return true;
    }

    private boolean handleSettingClick(Layout layout, int mouseX, int mouseY, int mouseButton) {
        if (selectedModule == null || !layout.settingsScrollBounds.contains(mouseX, mouseY)) {
            expandedEnumSetting = null;
            return false;
        }

        List<Setting> visibleSettings = getVisibleSettings(selectedModule);
        if (handleExpandedEnumClick(layout, visibleSettings, mouseX, mouseY, mouseButton)) {
            return true;
        }

        int rowY = layout.settingsContentTop - Math.round(settingsScroll);
        for (Setting setting : visibleSettings) {
            int rowHeight = getSettingHeight(setting);
            Bounds rowBounds = new Bounds(layout.settingsPaneX + 10, rowY, layout.settingsPaneRight - 10, rowY + rowHeight);
            if (rowBounds.contains(mouseX, mouseY)) {
                if (setting instanceof BooleanSetting && mouseButton == 0) {
                    expandedEnumSetting = null;
                    ((BooleanSetting) setting).toggle();
                    return true;
                }

                if (setting instanceof EnumSetting && mouseButton == 0) {
                    expandedEnumSetting = expandedEnumSetting == setting ? null : (EnumSetting<?>) setting;
                    return true;
                }

                if (setting instanceof ActionSetting && mouseButton == 0) {
                    expandedEnumSetting = null;
                    ((ActionSetting) setting).run();
                    ensureSelection();
                    return true;
                }

                if ((setting instanceof NumberSetting || setting instanceof DecimalSetting) && mouseButton == 0) {
                    Bounds sliderBounds = getSliderBounds(rowBounds);
                    if (sliderBounds.contains(mouseX, mouseY) || rowBounds.contains(mouseX, mouseY)) {
                        expandedEnumSetting = null;
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
            expandedEnumSetting = null;
            return false;
        }

        Bounds keybindBounds = new Bounds(layout.settingsPaneX + 10, rowY, layout.settingsPaneRight - 10, rowY + 38);
        if (!keybindBounds.contains(mouseX, mouseY)) {
            expandedEnumSetting = null;
            return false;
        }

        if (mouseButton == 0) {
            expandedEnumSetting = null;
            bindingModule = selectedModule;
            return true;
        }

        if (mouseButton == 1 && selectedModule.canBeUnbound()) {
            selectedModule.setKeyCode(Keyboard.KEY_NONE);
            bindingModule = null;
            expandedEnumSetting = null;
            return true;
        }

        expandedEnumSetting = null;
        return false;
    }

    private void drawBackdrop(Layout layout) {
        drawRoundedRect(layout.windowX - 14, layout.windowY - 14, layout.windowRight + 14, layout.windowBottom + 14, WINDOW_RADIUS + 6.0F, withAlpha(0x070B10, 18));
    }

    private void drawWindowShadow(Layout layout) {
        drawRoundedRect(layout.windowX - 6, layout.windowY - 6, layout.windowRight + 6, layout.windowBottom + 6, WINDOW_RADIUS + 4.0F, withAlpha(0x040608, 34));
        drawRoundedRect(layout.windowX - 2, layout.windowY - 2, layout.windowRight + 2, layout.windowBottom + 2, WINDOW_RADIUS + 2.0F, withAlpha(0x0C1117, 42));
    }

    private void drawWindow(Layout layout, int accent) {
        drawRoundedRect(layout.windowX, layout.windowY, layout.windowRight, layout.windowBottom, WINDOW_RADIUS + 1.0F, withAlpha(SURFACE_WINDOW_BORDER, 56));
        drawRoundedRect(layout.windowX + 1, layout.windowY + 1, layout.windowRight - 1, layout.windowBottom - 1, WINDOW_RADIUS, withAlpha(SURFACE_WINDOW, 238));
        drawRoundedRect(layout.windowX + 1, layout.windowY + 1, layout.windowRight - 1, layout.windowY + HEADER_HEIGHT + 10, WINDOW_RADIUS, withAlpha(SURFACE_WINDOW_HEADER, 244));
        drawRoundedRect(layout.windowX + 1, layout.windowY + HEADER_HEIGHT - 10, layout.windowRight - 1, layout.windowBottom - 1, WINDOW_RADIUS, withAlpha(SURFACE_WINDOW_BODY, 236));
        Gui.drawRect(layout.windowX, layout.windowY + HEADER_HEIGHT, layout.windowRight, layout.windowY + HEADER_HEIGHT + 1, withAlpha(accent, 190));
        Gui.drawRect(
            layout.modulePaneX + layout.modulePaneWidth + 14,
            layout.windowY + HEADER_HEIGHT + 16,
            layout.modulePaneX + layout.modulePaneWidth + 15,
            layout.windowBottom - 16,
            withAlpha(SURFACE_PANEL_OUTLINE, 120)
        );
    }

    private void drawHeader(Layout layout, int mouseX, int mouseY, int accent) {
        this.fontRendererObj.drawStringWithShadow("LionClient", layout.windowX + 16, layout.windowY + 15, TEXT_PRIMARY);

        int index = 0;
        for (Category category : Category.values()) {
            Bounds bounds = getCategoryBounds(layout, index);
            boolean hovered = bounds.contains(mouseX, mouseY);
            float animation = getAnimation(categoryAnimations, category, selectedCategory == category ? 1.0F : hovered ? 0.45F : 0.0F, 10.0F);
            float fillMix = selectedCategory == category ? 0.22F : animation * 0.14F;
            int fill = withAlpha(mixColor(SURFACE_PANEL, accent, fillMix), selectedCategory == category ? 198 : 154 + (int) (48.0F * animation));
            drawRoundedRect(bounds.left, bounds.top, bounds.right, bounds.bottom, 5.0F, fill);
            int outline = selectedCategory == category ? withAlpha(accent, 220) : withAlpha(SURFACE_PANEL_OUTLINE, hovered ? 120 : 92);
            drawRoundedOutline(bounds.left, bounds.top, bounds.right, bounds.bottom, 5.0F, outline);

            int labelColor = selectedCategory == category ? accent : mixColor(TEXT_MUTED, TEXT_PRIMARY, hovered ? 0.35F : 0.08F);
            String label = category.name();
            int textX = bounds.left + (bounds.getWidth() - this.fontRendererObj.getStringWidth(label)) / 2;
            int textY = bounds.top + (bounds.getHeight() - this.fontRendererObj.FONT_HEIGHT) / 2;
            this.fontRendererObj.drawString(label, textX, textY, labelColor);
            index++;
        }
    }

    private void drawModulePane(Layout layout, int mouseX, int mouseY, int accent, float delta) {
        drawRoundedRect(layout.modulePaneX, layout.modulePaneY, layout.modulePaneX + layout.modulePaneWidth, layout.modulePaneBottom, 8.0F, withAlpha(SURFACE_PANEL, 210));
        drawRoundedOutline(layout.modulePaneX, layout.modulePaneY, layout.modulePaneX + layout.modulePaneWidth, layout.modulePaneBottom, 8.0F, withAlpha(SURFACE_PANEL_OUTLINE, 125));

        List<Module> modules = moduleManager.getModules(selectedCategory);
        float maxScroll = Math.max(0.0F, modules.size() * (MODULE_ROW_HEIGHT + MODULE_ROW_GAP) - MODULE_ROW_GAP - layout.moduleScrollBounds.getHeight());
        moduleScrollTarget = clamp(moduleScrollTarget, 0.0F, maxScroll);
        moduleScroll = animate(moduleScroll, moduleScrollTarget, delta * 14.0F);

        beginScissor(layout.moduleScrollBounds);
        if (isCategoryTransitionActive()) {
            float transition = easeOut(categoryTransitionProgress);
            float outgoingProgress = clamp(transition / 0.42F, 0.0F, 1.0F);
            float incomingProgress = clamp((transition - 0.18F) / 0.62F, 0.0F, 1.0F);
            int previousOffset = Math.round(-categoryTransitionDirection * CATEGORY_MODULE_SLIDE_DISTANCE * outgoingProgress);
            int currentOffset = Math.round(categoryTransitionDirection * CATEGORY_MODULE_SLIDE_DISTANCE * (1.0F - incomingProgress));
            drawModuleList(layout, previousCategory, previousModuleScroll, mouseX, mouseY, accent, previousOffset, false, 1.0F - outgoingProgress);
            drawModuleList(layout, selectedCategory, moduleScroll, mouseX, mouseY, accent, currentOffset, false, incomingProgress);
        } else {
            drawModuleList(layout, selectedCategory, moduleScroll, mouseX, mouseY, accent, 0, true, 1.0F);
        }
        endScissor();
    }

    private void drawSettingsPane(Layout layout, int mouseX, int mouseY, int accent, float delta) {
        drawRoundedRect(layout.settingsPaneX, layout.settingsPaneY, layout.settingsPaneRight, layout.settingsPaneBottom, 8.0F, withAlpha(SURFACE_PANEL, 210));
        drawRoundedOutline(layout.settingsPaneX, layout.settingsPaneY, layout.settingsPaneRight, layout.settingsPaneBottom, 8.0F, withAlpha(SURFACE_PANEL_OUTLINE, 125));

        if (selectedModule != null) {
            List<Setting> visibleSettings = getVisibleSettings(selectedModule);
            float contentHeight = 0.0F;
            for (Setting setting : visibleSettings) {
                contentHeight += getSettingHeight(setting) + SETTING_ROW_GAP;
            }
            if (selectedModule.showsKeybindSetting()) {
                contentHeight += 46 + SETTING_ROW_GAP;
            }

            float maxScroll = Math.max(0.0F, contentHeight - layout.settingsScrollBounds.getHeight());
            settingsScrollTarget = clamp(settingsScrollTarget, 0.0F, maxScroll);
            settingsScroll = animate(settingsScroll, settingsScrollTarget, delta * 14.0F);
        } else {
            settingsScrollTarget = 0.0F;
            settingsScroll = animate(settingsScroll, 0.0F, delta * 14.0F);
        }

        if (isCategoryTransitionActive()) {
            float transition = easeOut(categoryTransitionProgress);
            float outgoingProgress = clamp(transition / 0.42F, 0.0F, 1.0F);
            float incomingProgress = clamp((transition - 0.18F) / 0.62F, 0.0F, 1.0F);
            int previousOffset = Math.round(-categoryTransitionDirection * CATEGORY_SETTINGS_SLIDE_DISTANCE * outgoingProgress);
            int currentOffset = Math.round(categoryTransitionDirection * CATEGORY_SETTINGS_SLIDE_DISTANCE * (1.0F - incomingProgress));
            drawSettingsPaneContent(layout, previousModule, mouseX, mouseY, accent, previousSettingsScroll, previousOffset, false, 1.0F - outgoingProgress);
            drawSettingsPaneContent(layout, selectedModule, mouseX, mouseY, accent, settingsScroll, currentOffset, false, incomingProgress);
            return;
        }

        drawSettingsPaneContent(layout, selectedModule, mouseX, mouseY, accent, settingsScroll, 0, true, 1.0F);
    }

    private void drawEmptySettingsState(Layout layout) {
        drawEmptySettingsState(layout, 1.0F);
    }

    private void drawEmptySettingsState(Layout layout, float alphaScale) {
        this.fontRendererObj.drawStringWithShadow("Select a module", layout.settingsPaneX + 16, layout.settingsPaneY + 18, scaleAlpha(TEXT_PRIMARY, alphaScale));
        this.fontRendererObj.drawString("Pick something on the left to edit its settings.", layout.settingsPaneX + 16, layout.settingsPaneY + 32, scaleAlpha(TEXT_SECONDARY, alphaScale));
    }

    private void drawModuleList(Layout layout, Category category, float scroll, int mouseX, int mouseY, int accent, int xOffset, boolean interactive, float alphaScale) {
        if (category == null || alphaScale <= 0.01F) {
            return;
        }

        GlStateManager.pushMatrix();
        GlStateManager.translate(xOffset, 0.0F, 0.0F);

        List<Module> modules = moduleManager.getModules(category);
        if (modules.isEmpty()) {
            drawCenteredString(this.fontRendererObj, "No modules", layout.modulePaneX + (layout.modulePaneWidth / 2), layout.modulePaneY + 48, scaleAlpha(TEXT_SECONDARY, alphaScale));
            GlStateManager.popMatrix();
            return;
        }

        int rowY = layout.moduleContentTop - Math.round(scroll);
        for (Module module : modules) {
            Bounds rowBounds = new Bounds(layout.modulePaneX + 6, rowY, layout.modulePaneX + layout.modulePaneWidth - 6, rowY + MODULE_ROW_HEIGHT);
            if (rowBounds.bottom >= layout.moduleScrollBounds.top && rowBounds.top <= layout.moduleScrollBounds.bottom) {
                boolean hovered = interactive && rowBounds.contains(mouseX, mouseY) && layout.moduleScrollBounds.contains(mouseX, mouseY);
                float selectionAnimation = getAnimation(moduleAnimations, module, hovered ? 0.45F : 0.0F, 13.0F);
                float toggleAnimation = getAnimation(moduleToggleAnimations, module, module.isEnabled() ? 1.0F : 0.0F, 11.0F);
                int baseRowColor = module.isEnabled()
                    ? mixColor(SURFACE_ROW, accent, 0.22F + (toggleAnimation * 0.26F))
                    : mixColor(SURFACE_ROW, SURFACE_PANEL_OUTLINE, hovered ? 0.30F : 0.08F);
                int rowColor = withAlpha(baseRowColor, module.isEnabled() ? 205 + (int) (30.0F * selectionAnimation) : 172 + (int) (35.0F * selectionAnimation));
                drawRoundedRect(rowBounds.left, rowBounds.top, rowBounds.right, rowBounds.bottom, 6.0F, scaleAlpha(rowColor, alphaScale));
                int outlineColor = module.isEnabled()
                    ? withAlpha(accent, 105 + (int) (60.0F * toggleAnimation))
                    : withAlpha(SURFACE_PANEL_OUTLINE, hovered ? 140 : 96);
                drawRoundedOutline(rowBounds.left, rowBounds.top, rowBounds.right, rowBounds.bottom, 6.0F, scaleAlpha(outlineColor, alphaScale));
                int nameColor = module.isEnabled()
                    ? mixColor(accent, TEXT_PRIMARY, 0.16F + (selectionAnimation * 0.18F))
                    : mixColor(TEXT_DISABLED, TEXT_PRIMARY, selectionAnimation * 0.18F);
                this.fontRendererObj.drawString(module.getName(), rowBounds.left + 10, rowBounds.top + 7, scaleAlpha(nameColor, alphaScale));
            }
            rowY += MODULE_ROW_HEIGHT + MODULE_ROW_GAP;
        }

        GlStateManager.popMatrix();
    }

    private void drawSettingsPaneContent(Layout layout, Module module, int mouseX, int mouseY, int accent, float scroll, int xOffset, boolean interactive, float alphaScale) {
        if (alphaScale <= 0.01F) {
            return;
        }

        Bounds paneBounds = new Bounds(layout.settingsPaneX + 2, layout.settingsPaneY + 2, layout.settingsPaneRight - 2, layout.settingsPaneBottom - 2);

        beginScissor(paneBounds);
        GlStateManager.pushMatrix();
        GlStateManager.translate(xOffset, 0.0F, 0.0F);
        if (module == null) {
            drawEmptySettingsState(layout, alphaScale);
            GlStateManager.popMatrix();
            endScissor();
            return;
        }

        this.fontRendererObj.drawStringWithShadow(module.getName(), layout.settingsPaneX + 16, layout.settingsPaneY + 10, scaleAlpha(TEXT_PRIMARY, alphaScale));
        this.fontRendererObj.drawString(module.getDescription(), layout.settingsPaneX + 16, layout.settingsPaneY + 22, scaleAlpha(TEXT_SECONDARY, alphaScale));
        float headerToggleProgress = getAnimation(moduleToggleAnimations, module, module.isEnabled() ? 1.0F : 0.0F, 14.0F);
        drawHeaderToggle(getHeaderToggleBounds(layout), module.isEnabled(), headerToggleProgress, accent, alphaScale);
        GlStateManager.popMatrix();
        endScissor();

        List<Setting> visibleSettings = getVisibleSettings(module);
        beginScissor(layout.settingsScrollBounds);
        GlStateManager.pushMatrix();
        GlStateManager.translate(xOffset, 0.0F, 0.0F);
        int drawMouseX = interactive ? mouseX : Integer.MIN_VALUE;
        int drawMouseY = interactive ? mouseY : Integer.MIN_VALUE;
        int rowY = layout.settingsContentTop - Math.round(scroll);
        for (Setting setting : visibleSettings) {
            int rowHeight = getSettingHeight(setting);
            Bounds rowBounds = new Bounds(layout.settingsPaneX + 10, rowY, layout.settingsPaneRight - 10, rowY + rowHeight);
            if (rowBounds.bottom >= layout.settingsScrollBounds.top && rowBounds.top <= layout.settingsScrollBounds.bottom) {
                drawSettingCard(rowBounds, setting, drawMouseX, drawMouseY, accent, alphaScale);
            }
            rowY += rowHeight + SETTING_ROW_GAP;
        }

        if (module.showsKeybindSetting()) {
            Bounds rowBounds = new Bounds(layout.settingsPaneX + 10, rowY, layout.settingsPaneRight - 10, rowY + 46);
            if (rowBounds.bottom >= layout.settingsScrollBounds.top && rowBounds.top <= layout.settingsScrollBounds.bottom) {
                drawKeybindCard(rowBounds, module, accent, alphaScale);
            }
        }
        if (interactive) {
            drawExpandedEnumPopup(layout, visibleSettings, mouseX, mouseY, accent);
        }
        GlStateManager.popMatrix();
        endScissor();
    }

    private void drawSettingCard(Bounds rowBounds, Setting setting, int mouseX, int mouseY, int accent, float alphaScale) {
        boolean hovered = rowBounds.contains(mouseX, mouseY);
        drawRoundedRect(rowBounds.left, rowBounds.top, rowBounds.right, rowBounds.bottom, 6.0F, scaleAlpha(hovered ? withAlpha(mixColor(SURFACE_ROW, SURFACE_PANEL_OUTLINE, 0.18F), 216) : withAlpha(SURFACE_ROW, 198), alphaScale));
        drawRoundedOutline(rowBounds.left, rowBounds.top, rowBounds.right, rowBounds.bottom, 6.0F, scaleAlpha(hovered ? withAlpha(accent, 120) : withAlpha(SURFACE_PANEL_OUTLINE, 110), alphaScale));

        if (setting instanceof BooleanSetting) {
            float progress = getAnimation(booleanAnimations, setting, ((BooleanSetting) setting).isEnabled() ? 1.0F : 0.0F, 14.0F);
            drawBooleanControl(getBooleanControlBounds(rowBounds), progress, hovered, accent, alphaScale);
            Bounds checkboxBounds = getBooleanControlBounds(rowBounds);
            this.fontRendererObj.drawString(setting.getName(), checkboxBounds.right + 8, rowBounds.top + (rowBounds.getHeight() - this.fontRendererObj.FONT_HEIGHT) / 2, scaleAlpha(CHECKBOX_LABEL_COLOR, alphaScale));
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
            this.fontRendererObj.drawString(setting.getName(), rowBounds.left + CONTROL_PADDING, rowBounds.top + 8, scaleAlpha(TEXT_PRIMARY, alphaScale));
            this.fontRendererObj.drawString(valueText, rowBounds.right - CONTROL_PADDING - this.fontRendererObj.getStringWidth(valueText), rowBounds.top + 8, scaleAlpha(accent, alphaScale));
            Gui.drawRect(sliderBounds.left, sliderBounds.top, sliderBounds.right, sliderBounds.bottom, scaleAlpha(0xFF000000 | CONTROL_BORDER, alphaScale));
            Gui.drawRect(sliderBounds.left, sliderBounds.top, sliderBounds.left + Math.round(sliderBounds.getWidth() * sliderProgress), sliderBounds.bottom, scaleAlpha(withAlpha(accent, 205), alphaScale));
            return;
        }

        if (setting instanceof EnumSetting) {
            EnumSetting<?> enumSetting = (EnumSetting<?>) setting;
            Bounds chipBounds = getEnumChipBounds(rowBounds, enumSetting);
            drawScaledText(setting.getName(), rowBounds.left + CONTROL_PADDING, rowBounds.top + 6, scaleAlpha(SMALL_LABEL_COLOR, alphaScale), SMALL_LABEL_SCALE);
            Gui.drawRect(chipBounds.left, chipBounds.top, chipBounds.right, chipBounds.bottom, scaleAlpha(0xFF000000 | CONTROL_BACKGROUND, alphaScale));
            drawRoundedOutline(
                chipBounds.left,
                chipBounds.top,
                chipBounds.right,
                chipBounds.bottom,
                4.0F,
                scaleAlpha(expandedEnumSetting == setting ? withAlpha(accent, 180) : 0xFF000000 | CONTROL_BORDER, alphaScale)
            );
            this.fontRendererObj.drawString(enumSetting.getValueText(), chipBounds.left + 8, chipBounds.top + (chipBounds.getHeight() - this.fontRendererObj.FONT_HEIGHT) / 2, scaleAlpha(TEXT_PRIMARY, alphaScale));
            this.fontRendererObj.drawString("≡", chipBounds.right - 12 - this.fontRendererObj.getStringWidth("≡"), chipBounds.top + (chipBounds.getHeight() - this.fontRendererObj.FONT_HEIGHT) / 2, TEXT_SECONDARY);
            return;
        }

        if (setting instanceof ActionSetting) {
            String valueText = setting.getValueText();
            drawScaledText(setting.getName(), rowBounds.left + CONTROL_PADDING, rowBounds.top + 6, scaleAlpha(SMALL_LABEL_COLOR, alphaScale), SMALL_LABEL_SCALE);
            Bounds chipBounds = getActionButtonBounds(rowBounds, valueText);
            Gui.drawRect(chipBounds.left, chipBounds.top, chipBounds.right, chipBounds.bottom, scaleAlpha(0xFF000000 | CONTROL_BACKGROUND, alphaScale));
            drawRoundedOutline(chipBounds.left, chipBounds.top, chipBounds.right, chipBounds.bottom, 4.0F, scaleAlpha(0xFF000000 | CONTROL_BORDER_LIGHT, alphaScale));
            this.fontRendererObj.drawString(valueText, chipBounds.left + (chipBounds.getWidth() - this.fontRendererObj.getStringWidth(valueText)) / 2, chipBounds.top + (chipBounds.getHeight() - this.fontRendererObj.FONT_HEIGHT) / 2, scaleAlpha(TEXT_PRIMARY, alphaScale));
            return;
        }

        String valueText = setting.getValueText();
        this.fontRendererObj.drawString(setting.getName(), rowBounds.left + CONTROL_PADDING, rowBounds.top + 10, scaleAlpha(TEXT_PRIMARY, alphaScale));
        this.fontRendererObj.drawString(valueText, rowBounds.right - CONTROL_PADDING - this.fontRendererObj.getStringWidth(valueText), rowBounds.top + 10, scaleAlpha(accent, alphaScale));
    }

    private void drawKeybindCard(Bounds rowBounds, Module module, int accent, float alphaScale) {
        drawRoundedRect(rowBounds.left, rowBounds.top, rowBounds.right, rowBounds.bottom, 6.0F, scaleAlpha(withAlpha(SURFACE_ROW, 200), alphaScale));
        drawRoundedOutline(rowBounds.left, rowBounds.top, rowBounds.right, rowBounds.bottom, 6.0F, scaleAlpha(withAlpha(SURFACE_PANEL_OUTLINE, 110), alphaScale));
        drawScaledText("Keybind", rowBounds.left + CONTROL_PADDING, rowBounds.top + 6, scaleAlpha(SMALL_LABEL_COLOR, alphaScale), SMALL_LABEL_SCALE);
        String valueText = bindingModule == module ? "Bind: ..." : "Bind: " + getKeybindText(module);
        Bounds chipBounds = getBindButtonBounds(rowBounds, valueText);
        Gui.drawRect(chipBounds.left, chipBounds.top, chipBounds.right, chipBounds.bottom, scaleAlpha(0xFF000000 | CONTROL_BACKGROUND, alphaScale));
        drawRoundedOutline(
            chipBounds.left,
            chipBounds.top,
            chipBounds.right,
            chipBounds.bottom,
            4.0F,
            scaleAlpha(bindingModule == module ? withAlpha(accent, 220) : 0xFF000000 | CONTROL_BORDER_LIGHT, alphaScale)
        );
        this.fontRendererObj.drawString(valueText, chipBounds.left + 8, chipBounds.top + (chipBounds.getHeight() - this.fontRendererObj.FONT_HEIGHT) / 2, scaleAlpha(TEXT_PRIMARY, alphaScale));
    }

    private void drawBooleanControl(Bounds bounds, float progress, boolean hovered, int accent, float alphaScale) {
        int fill = 0xFF000000 | mixColor(CONTROL_BACKGROUND, accent, progress);
        Gui.drawRect(bounds.left, bounds.top, bounds.right, bounds.bottom, scaleAlpha(fill, alphaScale));
        drawOutline(bounds.left, bounds.top, bounds.right, bounds.bottom, scaleAlpha(0xFF000000 | CONTROL_BORDER_LIGHT, alphaScale));
        if (progress > 0.02F) {
            drawCheckboxCheck(bounds, 0xFFFFFFFF, progress, alphaScale);
        }
    }

    private void drawCheckboxCheck(Bounds bounds, int color, float progress, float alphaScale) {
        int left = bounds.left + 3;
        int top = bounds.top + 3;
        int animatedColor = scaleAlpha(withAlpha(color, Math.max(32, Math.round(255.0F * progress))), alphaScale);

        if (progress >= 0.15F) {
            Gui.drawRect(left, top + 4, left + 2, top + 6, animatedColor);
        }
        if (progress >= 0.32F) {
            Gui.drawRect(left + 2, top + 6, left + 4, top + 8, animatedColor);
        }
        if (progress >= 0.5F) {
            Gui.drawRect(left + 4, top + 4, left + 6, top + 6, animatedColor);
        }
        if (progress >= 0.68F) {
            Gui.drawRect(left + 6, top + 2, left + 8, top + 4, animatedColor);
        }
        if (progress >= 0.84F) {
            Gui.drawRect(left + 8, top, left + 10, top + 2, animatedColor);
        }
    }

    private void drawHeaderToggle(Bounds bounds, boolean enabled, float progress, int accent, float alphaScale) {
        String label = enabled ? "Enabled" : "Disabled";
        int trackTop = bounds.top + (bounds.getHeight() - SWITCH_HEIGHT) / 2;
        int trackRight = bounds.left + SWITCH_WIDTH;
        int trackColor = 0xFF000000 | mixColor(SWITCH_OFF_TRACK, accent, progress);
        Gui.drawRect(bounds.left, trackTop, trackRight, trackTop + SWITCH_HEIGHT, scaleAlpha(trackColor, alphaScale));
        drawOutline(bounds.left, trackTop, trackRight, trackTop + SWITCH_HEIGHT, scaleAlpha(0xFF000000 | mixColor(CONTROL_BORDER, accent, progress * 0.7F), alphaScale));

        int thumbSize = Math.max(4, SWITCH_HEIGHT - 2);
        int thumbTravel = Math.max(0, SWITCH_WIDTH - thumbSize - 2);
        int thumbLeft = bounds.left + 1 + Math.round(thumbTravel * progress);
        int thumbColor = 0xFF000000 | mixColor(SWITCH_OFF_THUMB, 0xF5F8FF, progress);
        Gui.drawRect(thumbLeft, trackTop + 1, thumbLeft + thumbSize, trackTop + 1 + thumbSize, scaleAlpha(thumbColor, alphaScale));

        this.fontRendererObj.drawString(label, trackRight + 8, bounds.top + (bounds.getHeight() - this.fontRendererObj.FONT_HEIGHT) / 2, scaleAlpha(TEXT_SECONDARY, alphaScale));
    }

    private void refreshClickGuiStyleIfNeeded(Setting setting) {
        if (selectedModule != ClickGuiModule.getInstance() || !"Style".equals(setting.getName())) {
            return;
        }

        LionClient client = LionClient.getInstance();
        if (client != null) {
            client.refreshClickGuiStyle();
        }
    }

    private boolean handleExpandedEnumClick(Layout layout, List<Setting> visibleSettings, int mouseX, int mouseY, int mouseButton) {
        if (expandedEnumSetting == null) {
            return false;
        }

        int rowY = layout.settingsContentTop - Math.round(settingsScroll);
        for (Setting setting : visibleSettings) {
            int rowHeight = getSettingHeight(setting);
            Bounds rowBounds = new Bounds(layout.settingsPaneX + 10, rowY, layout.settingsPaneRight - 10, rowY + rowHeight);
            if (setting == expandedEnumSetting && setting instanceof EnumSetting) {
                EnumSetting<?> enumSetting = (EnumSetting<?>) setting;
                Bounds popupBounds = getEnumPopupBounds(layout, getEnumChipBounds(rowBounds, enumSetting), enumSetting);
                if (!popupBounds.contains(mouseX, mouseY)) {
                    return false;
                }

                if (mouseButton == 0) {
                    Object[] values = enumSetting.getValues();
                    for (int i = 0; i < values.length; i++) {
                        if (getEnumOptionBounds(popupBounds, i).contains(mouseX, mouseY) && enumSetting.setIndex(i)) {
                            expandedEnumSetting = null;
                            ensureSelection();
                            refreshClickGuiStyleIfNeeded(setting);
                            return true;
                        }
                    }
                }

                return true;
            }
            rowY += rowHeight + SETTING_ROW_GAP;
        }

        expandedEnumSetting = null;
        return false;
    }

    private void drawExpandedEnumPopup(Layout layout, List<Setting> visibleSettings, int mouseX, int mouseY, int accent) {
        if (expandedEnumSetting == null) {
            return;
        }

        int rowY = layout.settingsContentTop - Math.round(settingsScroll);
        for (Setting setting : visibleSettings) {
            int rowHeight = getSettingHeight(setting);
            Bounds rowBounds = new Bounds(layout.settingsPaneX + 10, rowY, layout.settingsPaneRight - 10, rowY + rowHeight);
            if (setting == expandedEnumSetting && setting instanceof EnumSetting) {
                EnumSetting<?> enumSetting = (EnumSetting<?>) setting;
                Bounds popupBounds = getEnumPopupBounds(layout, getEnumChipBounds(rowBounds, enumSetting), enumSetting);
                Gui.drawRect(popupBounds.left, popupBounds.top, popupBounds.right, popupBounds.bottom, 0xFF000000 | CONTROL_BACKGROUND);
                drawRoundedOutline(popupBounds.left, popupBounds.top, popupBounds.right, popupBounds.bottom, 4.0F, 0xFF000000 | CONTROL_BORDER);

                Object[] values = enumSetting.getValues();
                for (int i = 0; i < values.length; i++) {
                    Bounds optionBounds = getEnumOptionBounds(popupBounds, i);
                    boolean hovered = optionBounds.contains(mouseX, mouseY);
                    boolean selected = values[i] == enumSetting.getValue();
                    int fill = selected ? withAlpha(accent, 205) : hovered ? withAlpha(SURFACE_PANEL_OUTLINE, 180) : withAlpha(SURFACE_CHIP, 0);
                    if ((fill >>> 24) != 0) {
                        Gui.drawRect(optionBounds.left, optionBounds.top, optionBounds.right, optionBounds.bottom, fill);
                    }
                    this.fontRendererObj.drawString(values[i].toString(), optionBounds.left + 8, optionBounds.top + (optionBounds.getHeight() - this.fontRendererObj.FONT_HEIGHT) / 2, selected ? TEXT_PRIMARY : hovered ? TEXT_PRIMARY : TEXT_SECONDARY);
                }
                return;
            }
            rowY += rowHeight + SETTING_ROW_GAP;
        }

        expandedEnumSetting = null;
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
        if (setting instanceof EnumSetting || setting instanceof ActionSetting) {
            return 44;
        }
        if (setting instanceof NumberSetting || setting instanceof DecimalSetting) {
            return 44;
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
            modulePaneY + 8,
            new Bounds(modulePaneX + 2, modulePaneY + 6, modulePaneX + modulePaneWidth - 2, modulePaneBottom - 6),
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

    private Bounds getHeaderToggleBounds(Layout layout) {
        return new Bounds(layout.settingsPaneRight - 102, layout.settingsPaneY + 34, layout.settingsPaneRight - 14, layout.settingsPaneY + 58);
    }

    private Bounds getDragBounds(Layout layout) {
        return new Bounds(layout.windowX + 8, layout.windowY + 8, layout.windowX + 124, layout.windowY + HEADER_HEIGHT - 12);
    }

    private Bounds getBooleanControlBounds(Bounds rowBounds) {
        int top = rowBounds.top + (rowBounds.getHeight() - CHECKBOX_SIZE) / 2;
        return new Bounds(rowBounds.left + CONTROL_PADDING, top, rowBounds.left + CONTROL_PADDING + CHECKBOX_SIZE, top + CHECKBOX_SIZE);
    }

    private Bounds getEnumChipBounds(Bounds rowBounds, EnumSetting<?> setting) {
        int chipWidth = Math.min(CONTROL_WIDTH, rowBounds.getWidth() - (CONTROL_PADDING * 2));
        int top = rowBounds.top + 14;
        return new Bounds(rowBounds.left + CONTROL_PADDING, top, rowBounds.left + CONTROL_PADDING + chipWidth, top + CONTROL_HEIGHT);
    }

    private Bounds getEnumPopupBounds(Layout layout, Bounds chipBounds, EnumSetting<?> setting) {
        int popupHeight = setting.getValues().length * ENUM_OPTION_HEIGHT;
        int popupTop = chipBounds.bottom + 4;
        if (popupTop + popupHeight > layout.settingsScrollBounds.bottom) {
            popupTop = chipBounds.top - 4 - popupHeight;
        }
        popupTop = Math.max(layout.settingsScrollBounds.top, popupTop);
        return new Bounds(chipBounds.left, popupTop, chipBounds.right, popupTop + popupHeight);
    }

    private Bounds getEnumOptionBounds(Bounds popupBounds, int index) {
        int top = popupBounds.top + (index * ENUM_OPTION_HEIGHT);
        return new Bounds(popupBounds.left, top, popupBounds.right, top + ENUM_OPTION_HEIGHT);
    }

    private Bounds getActionButtonBounds(Bounds rowBounds, String valueText) {
        int buttonWidth = Math.max(BIND_BUTTON_WIDTH, this.fontRendererObj.getStringWidth(valueText) + 20);
        int top = rowBounds.top + 14;
        return new Bounds(rowBounds.left + CONTROL_PADDING, top, rowBounds.left + CONTROL_PADDING + buttonWidth, top + CONTROL_HEIGHT);
    }

    private Bounds getBindButtonBounds(Bounds rowBounds, String valueText) {
        int buttonWidth = Math.max(BIND_BUTTON_WIDTH, this.fontRendererObj.getStringWidth(valueText) + 20);
        int top = rowBounds.top + 14;
        return new Bounds(rowBounds.left + CONTROL_PADDING, top, rowBounds.left + CONTROL_PADDING + buttonWidth, top + CONTROL_HEIGHT);
    }

    private Bounds getSliderBounds(Bounds rowBounds) {
        int sliderWidth = Math.min(CONTROL_WIDTH, rowBounds.getWidth() - (CONTROL_PADDING * 2));
        int top = rowBounds.top + 27;
        return new Bounds(rowBounds.left + CONTROL_PADDING, top, rowBounds.left + CONTROL_PADDING + sliderWidth, top + SLIDER_TRACK_HEIGHT);
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
        drawOutline(Math.round(left), Math.round(top), Math.round(right), Math.round(bottom), color);
    }

    private void drawRoundedRect(float left, float top, float right, float bottom, float radius, int color) {
        if (((color >>> 24) & 255) == 0 || right <= left || bottom <= top) {
            return;
        }
        Gui.drawRect(Math.round(left), Math.round(top), Math.round(right), Math.round(bottom), color);
    }

    private void drawScaledText(String text, int x, int y, int color, float scale) {
        GlStateManager.pushMatrix();
        GlStateManager.scale(scale, scale, 1.0F);
        this.fontRendererObj.drawString(text, Math.round(x / scale), Math.round(y / scale), color);
        GlStateManager.popMatrix();
    }

    private boolean isInsideRoundedRect(float x, float y, Bounds bounds, float radius) {
        return bounds.contains(Math.round(x), Math.round(y));
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
            if (!isInsideRoundedRect(flake.x, flake.y, bounds, WINDOW_RADIUS - 1.0F)) {
                continue;
            }
            int alpha = (int) (flake.alpha * alphaScale);
            int color = withAlpha(SNOWFLAKE_COLOR, alpha);
            Gui.drawRect(Math.round(flake.x), Math.round(flake.y), Math.round(flake.x) + flake.size, Math.round(flake.y) + flake.size, color);
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

    private void updateCategoryTransition(float delta) {
        if (previousCategory == null) {
            categoryTransitionProgress = 1.0F;
            return;
        }

        categoryTransitionProgress = animate(categoryTransitionProgress, 1.0F, delta * 10.0F);
        if (categoryTransitionProgress >= 0.995F) {
            clearCategoryTransition();
        }
    }

    private void beginCategoryTransition(Category fromCategory, Module fromModule, float fromModuleScroll, float fromSettingsScroll, Category toCategory) {
        if (fromCategory == null || fromCategory == toCategory) {
            clearCategoryTransition();
            return;
        }

        previousCategory = fromCategory;
        previousModule = fromModule;
        previousModuleScroll = fromModuleScroll;
        previousSettingsScroll = fromSettingsScroll;
        categoryTransitionProgress = 0.0F;
        int direction = Integer.compare(toCategory.ordinal(), fromCategory.ordinal());
        categoryTransitionDirection = direction == 0 ? 1 : direction;
    }

    private void clearCategoryTransition() {
        previousCategory = null;
        previousModule = null;
        previousModuleScroll = 0.0F;
        previousSettingsScroll = 0.0F;
        categoryTransitionProgress = 1.0F;
        categoryTransitionDirection = 1;
    }

    private boolean isCategoryTransitionActive() {
        return previousCategory != null && categoryTransitionProgress < 0.999F;
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

    private static int scaleAlpha(int color, float alphaScale) {
        int baseAlpha = (color >>> 24) & 255;
        if (baseAlpha == 0) {
            baseAlpha = 255;
        }
        return withAlpha(color, Math.round(baseAlpha * clamp(alphaScale, 0.0F, 1.0F)));
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
