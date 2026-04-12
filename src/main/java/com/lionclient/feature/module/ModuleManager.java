package com.lionclient.feature.module;

import com.lionclient.config.ConfigManager;
import com.lionclient.feature.module.impl.AutoClickerModule;
import com.lionclient.feature.module.impl.ClickGuiModule;
import com.lionclient.feature.module.impl.ClickRecorderModule;
import com.lionclient.feature.module.impl.ConfigModule;
import com.lionclient.feature.module.impl.HudModule;
import com.lionclient.feature.module.impl.PlayerEspModule;
import com.lionclient.feature.module.impl.SprintModule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public final class ModuleManager {
    private final List<Module> modules = new ArrayList<Module>();
    private final Map<Category, List<Module>> modulesByCategory = new EnumMap<Category, List<Module>>(Category.class);
    private final ConfigManager configManager;
    private final ConfigModule configModule;

    public ModuleManager() {
        for (Category category : Category.values()) {
            modulesByCategory.put(category, new ArrayList<Module>());
        }

        register(new SprintModule());
        register(new AutoClickerModule());
        register(new ClickRecorderModule());
        register(new ClickGuiModule());
        register(new PlayerEspModule());
        register(new HudModule());
        configManager = new ConfigManager(this);
        configModule = new ConfigModule(configManager);
        register(configModule);
        configManager.initialize();
    }

    private void register(Module module) {
        modules.add(module);
        modulesByCategory.get(module.getCategory()).add(module);
    }

    public List<Module> getModules() {
        return Collections.unmodifiableList(modules);
    }

    public List<Module> getModules(Category category) {
        return Collections.unmodifiableList(modulesByCategory.get(category));
    }

    public void onClientTick() {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onClientTick();
            }
        }
    }

    public void onMouseEvent(MouseEvent event) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onMouseEvent(event);
            }
        }
    }

    public void onRenderTick(TickEvent.RenderTickEvent event) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onRenderTick(event);
            }
        }
    }

    public void onRenderWorld(RenderWorldLastEvent event) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onRenderWorld(event);
            }
        }
    }

    public void onRenderOverlay(RenderGameOverlayEvent.Text event) {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.onRenderOverlay(event);
            }
        }
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public void refreshConfigModule() {
        configModule.rebuildSettings();
    }
}
