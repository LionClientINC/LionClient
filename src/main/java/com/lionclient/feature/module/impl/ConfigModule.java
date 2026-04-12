package com.lionclient.feature.module.impl;

import com.lionclient.config.ConfigManager;
import com.lionclient.feature.module.Category;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.setting.ActionSetting;
import java.util.List;
import org.lwjgl.input.Keyboard;

public final class ConfigModule extends Module {
    private final ConfigManager configManager;

    public ConfigModule(ConfigManager configManager) {
        super("Config", "Manage saved client configs.", Category.CLIENT, Keyboard.KEY_NONE);
        this.configManager = configManager;
        rebuildSettings();
    }

    @Override
    public void toggle() {
    }

    public void rebuildSettings() {
        clearSettings();
        addSetting(new ActionSetting("New Config", new Runnable() {
            @Override
            public void run() {
                configManager.createNextConfig();
                rebuildSettings();
            }
        }, new ActionSetting.ValueProvider() {
            @Override
            public String get() {
                return "CREATE";
            }
        }));
        addSetting(new ActionSetting("Delete Config", new Runnable() {
            @Override
            public void run() {
                configManager.deleteCurrentConfig();
                rebuildSettings();
            }
        }, new ActionSetting.ValueProvider() {
            @Override
            public String get() {
                return "DELETE";
            }
        }));
        addSetting(new ActionSetting("Open Folder", new Runnable() {
            @Override
            public void run() {
                configManager.openFolder();
            }
        }, new ActionSetting.ValueProvider() {
            @Override
            public String get() {
                return "OPEN";
            }
        }));

        List<String> configs = configManager.listConfigs();
        for (final String name : configs) {
            addSetting(new ActionSetting(name, new Runnable() {
                @Override
                public void run() {
                    configManager.load(name);
                    rebuildSettings();
                }
            }, new ActionSetting.ValueProvider() {
                @Override
                public String get() {
                    return name.equalsIgnoreCase(configManager.getCurrentConfigName()) ? "ACTIVE" : "LOAD";
                }
            }));
        }
    }
}
