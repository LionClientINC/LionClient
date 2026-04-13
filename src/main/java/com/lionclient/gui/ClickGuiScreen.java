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
import java.util.List;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

public final class ClickGuiScreen extends GuiScreen {
    private final List<CategoryPanel> panels = new ArrayList<CategoryPanel>();

    public ClickGuiScreen(ModuleManager moduleManager) {
        int x = 20;
        for (Category category : Category.values()) {
            panels.add(new CategoryPanel(category, x, 30, moduleManager.getModules(category)));
            x += 115;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int accent = ClickGuiModule.getAccentColor();
        drawCenteredString(this.fontRendererObj, "LionClient", this.width / 2, 10, accent);

        for (CategoryPanel panel : panels) {
            panel.draw(mouseX, mouseY, fontRendererObj, this.width);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        for (CategoryPanel panel : panels) {
            panel.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        for (CategoryPanel panel : panels) {
            if (panel.handleKeyTyped(keyCode)) {
                return;
            }
        }

        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        for (CategoryPanel panel : panels) {
            panel.mouseReleased();
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int wheel = org.lwjgl.input.Mouse.getEventDWheel();
        if (wheel == 0) {
            return;
        }

        int amount = wheel > 0 ? 12 : -12;
        for (CategoryPanel panel : panels) {
            panel.offset(amount);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private static final class CategoryPanel {
        private static final int WIDTH = 105;
        private static final int HEADER_HEIGHT = 16;
        private static final int ROW_HEIGHT = 14;
        private static final long DESCRIPTION_HOVER_DELAY_MS = 2000L;
        private static final String KEYBIND_LABEL = "Keybind";

        private final Category category;
        private final List<Module> modules;
        private Module expandedModule;
        private Module bindingModule;
        private Module hoveredModule;
        private long hoveredSince;
        private int x;
        private int y;
        private boolean dragging;
        private int dragOffsetX;
        private int dragOffsetY;

        private CategoryPanel(Category category, int x, int y, List<Module> modules) {
            this.category = category;
            this.x = x;
            this.y = y;
            this.modules = modules;
        }

        private void draw(int mouseX, int mouseY, net.minecraft.client.gui.FontRenderer fontRenderer, int screenWidth) {
            int accent = ClickGuiModule.getAccentColor();
            if (dragging) {
                x = mouseX - dragOffsetX;
                y = mouseY - dragOffsetY;
            }

            Gui.drawRect(x, y, x + WIDTH, y + HEADER_HEIGHT, 0xFF000000 | accent);
            Gui.drawRect(x, y + HEADER_HEIGHT, x + WIDTH, y + getContentHeight(), 0xB0101018);
            fontRenderer.drawStringWithShadow(category.name(), x + 4, y + 4, 0xFFFFFFFF);

            int rowY = y + HEADER_HEIGHT;
            Module currentlyHoveredModule = null;
            int hoveredRowY = 0;
            for (Module module : modules) {
                int color = module.isEnabled() ? (0xFF000000 | accent) : 0xFF8A8F9E;
                Gui.drawRect(x + 2, rowY + 1, x + WIDTH - 2, rowY + ROW_HEIGHT - 1, 0x80262B3E);
                fontRenderer.drawString(module.getName(), x + 6, rowY + 3, color);
                fontRenderer.drawString("...", x + WIDTH - 16, rowY + 3, 0xFF000000 | accent);

                if (isHovered(mouseX, mouseY, x, rowY, WIDTH, ROW_HEIGHT)) {
                    currentlyHoveredModule = module;
                    hoveredRowY = rowY;
                }
                rowY += ROW_HEIGHT;

                if (expandedModule == module) {
                    for (Setting setting : module.getSettings()) {
                        Gui.drawRect(x + 4, rowY, x + WIDTH - 4, rowY + ROW_HEIGHT, 0x9040485C);
                        fontRenderer.drawString(setting.getName(), x + 6, rowY + 3, 0xFFE8EAF1);
                        fontRenderer.drawString(setting.getValueText(), x + WIDTH - 6 - fontRenderer.getStringWidth(setting.getValueText()), rowY + 3, 0xFF000000 | accent);
                        rowY += ROW_HEIGHT;
                    }

                    if (module.showsKeybindSetting()) {
                        Gui.drawRect(x + 4, rowY, x + WIDTH - 4, rowY + ROW_HEIGHT, 0x9040485C);
                        fontRenderer.drawString(KEYBIND_LABEL, x + 6, rowY + 3, 0xFFE8EAF1);
                        String keybindText = bindingModule == module ? "Press key..." : getKeybindText(module);
                        fontRenderer.drawString(keybindText, x + WIDTH - 6 - fontRenderer.getStringWidth(keybindText), rowY + 3, 0xFF000000 | accent);
                        rowY += ROW_HEIGHT;
                    }
                }
            }

            updateHoveredModule(currentlyHoveredModule);
            if (shouldShowDescription(currentlyHoveredModule)) {
                drawModuleDescription(currentlyHoveredModule, hoveredRowY, fontRenderer, screenWidth);
            }
        }

        private void mouseClicked(int mouseX, int mouseY, int mouseButton) {
            if (isHovered(mouseX, mouseY, x, y, WIDTH, HEADER_HEIGHT) && mouseButton == 0) {
                dragging = true;
                dragOffsetX = mouseX - x;
                dragOffsetY = mouseY - y;
                return;
            }

            int rowY = y + HEADER_HEIGHT;
            for (Module module : modules) {
                if (isHovered(mouseX, mouseY, x, rowY, WIDTH, ROW_HEIGHT)) {
                    if (mouseButton == 0) {
                        module.toggle();
                        return;
                    }
                    if (mouseButton == 1) {
                        expandedModule = expandedModule == module ? null : module;
                        if (expandedModule != module) {
                            bindingModule = null;
                        }
                        return;
                    }
                }
                rowY += ROW_HEIGHT;

                if (expandedModule == module) {
                    for (Setting setting : module.getSettings()) {
                        if (isHovered(mouseX, mouseY, x + 4, rowY, WIDTH - 8, ROW_HEIGHT)) {
                            handleSettingClick(setting, mouseButton, module);
                            return;
                        }
                        rowY += ROW_HEIGHT;
                    }

                    if (module.showsKeybindSetting() && isHovered(mouseX, mouseY, x + 4, rowY, WIDTH - 8, ROW_HEIGHT)) {
                        handleKeybindClick(module, mouseButton);
                        return;
                    }
                    if (module.showsKeybindSetting()) {
                        rowY += ROW_HEIGHT;
                    }
                }
            }
        }

        private void mouseReleased() {
            dragging = false;
        }

        private void offset(int amount) {
            y += amount;
        }

        private int getContentHeight() {
            int rows = modules.size();
            if (expandedModule != null) {
                rows += expandedModule.getSettings().size();
                if (expandedModule.showsKeybindSetting()) {
                    rows++;
                }
            }
            return HEADER_HEIGHT + (rows * ROW_HEIGHT);
        }

        private void handleSettingClick(Setting setting, int mouseButton, Module module) {
            if (setting instanceof ActionSetting && mouseButton == 0) {
                ((ActionSetting) setting).run();
                return;
            }

            if (setting instanceof BooleanSetting && mouseButton == 0) {
                ((BooleanSetting) setting).toggle();
                return;
            }

            if (setting instanceof NumberSetting) {
                NumberSetting number = (NumberSetting) setting;
                if (mouseButton == 0) {
                    number.increment();
                } else if (mouseButton == 1) {
                    number.decrement();
                }

                enforceNumberBounds(module);
                return;
            }

            if (setting instanceof DecimalSetting) {
                DecimalSetting decimal = (DecimalSetting) setting;
                if (mouseButton == 0) {
                    decimal.increment();
                } else if (mouseButton == 1) {
                    decimal.decrement();
                }
                return;
            }

            if (setting instanceof EnumSetting && (mouseButton == 0 || mouseButton == 1)) {
                EnumSetting<?> enumSetting = (EnumSetting<?>) setting;
                if (mouseButton == 0) {
                    enumSetting.cycleForward();
                } else {
                    enumSetting.cycleBackward();
                }
            }
        }

        private void enforceNumberBounds(Module module) {
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
                max.setValue(min.getValue());
            }
        }

        private void handleKeybindClick(Module module, int mouseButton) {
            if (mouseButton == 0) {
                bindingModule = module;
                return;
            }

            if (mouseButton == 1 && module.canBeUnbound()) {
                module.setKeyCode(Keyboard.KEY_NONE);
                bindingModule = null;
            }
        }

        private boolean handleKeyTyped(int keyCode) {
            if (bindingModule == null) {
                return false;
            }

            if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_BACK || keyCode == Keyboard.KEY_DELETE) {
                if (bindingModule.canBeUnbound()) {
                    bindingModule.setKeyCode(Keyboard.KEY_NONE);
                }
            } else {
                bindingModule.setKeyCode(keyCode);
            }

            bindingModule = null;
            return true;
        }

        private String getKeybindText(Module module) {
            if (module.getKeyCode() == Keyboard.KEY_NONE) {
                return "NONE";
            }

            String name = Keyboard.getKeyName(module.getKeyCode());
            return name == null ? "UNKNOWN" : name.toUpperCase();
        }

        private void updateHoveredModule(Module module) {
            long now = System.currentTimeMillis();
            if (module != hoveredModule) {
                hoveredModule = module;
                hoveredSince = module == null ? 0L : now;
            }
        }

        private boolean shouldShowDescription(Module module) {
            return module != null
                && module.getDescription() != null
                && !module.getDescription().isEmpty()
                && System.currentTimeMillis() - hoveredSince >= DESCRIPTION_HOVER_DELAY_MS;
        }

        private void drawModuleDescription(Module module, int rowY, net.minecraft.client.gui.FontRenderer fontRenderer, int screenWidth) {
            String description = module.getDescription();
            int padding = 4;
            int tooltipWidth = fontRenderer.getStringWidth(description) + (padding * 2);
            int tooltipX = x + (WIDTH - tooltipWidth) / 2;
            int tooltipY = rowY - ROW_HEIGHT - 4;

            if (tooltipX < 4) {
                tooltipX = 4;
            }

            int maxX = screenWidth - tooltipWidth - 4;
            if (tooltipX > maxX) {
                tooltipX = maxX;
            }

            if (tooltipY < 4) {
                tooltipY = rowY + 2;
            }

            Gui.drawRect(tooltipX, tooltipY, tooltipX + tooltipWidth, tooltipY + ROW_HEIGHT, 0xE0101018);
            Gui.drawRect(tooltipX, tooltipY, tooltipX + tooltipWidth, tooltipY + 1, 0xFF000000 | ClickGuiModule.getAccentColor());
            fontRenderer.drawStringWithShadow(description, tooltipX + padding, tooltipY + 3, 0xFFFFFFFF);
        }

        private boolean isHovered(int mouseX, int mouseY, int rectX, int rectY, int width, int height) {
            return mouseX >= rectX && mouseX <= rectX + width && mouseY >= rectY && mouseY <= rectY + height;
        }
    }
}
