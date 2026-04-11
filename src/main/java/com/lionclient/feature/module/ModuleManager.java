package com.lionclient.feature.module;

import com.lionclient.feature.module.impl.AutoClickerModule;
import com.lionclient.feature.module.impl.ClickRecorderModule;
import com.lionclient.feature.module.impl.SprintModule;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraftforge.client.event.MouseEvent;

public final class ModuleManager {
    private final List<Module> modules = new ArrayList<Module>();
    private final Map<Category, List<Module>> modulesByCategory = new EnumMap<Category, List<Module>>(Category.class);

    public ModuleManager() {
        for (Category category : Category.values()) {
            modulesByCategory.put(category, new ArrayList<Module>());
        }

        register(new SprintModule());
        register(new AutoClickerModule());
        register(new ClickRecorderModule());
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
}
