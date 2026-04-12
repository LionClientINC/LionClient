package com.lionclient.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lionclient.feature.module.Module;
import com.lionclient.feature.module.ModuleManager;
import com.lionclient.feature.module.impl.ClickPatternStore;
import com.lionclient.feature.setting.ActionSetting;
import com.lionclient.feature.setting.BooleanSetting;
import com.lionclient.feature.setting.DecimalSetting;
import com.lionclient.feature.setting.EnumSetting;
import com.lionclient.feature.setting.NumberSetting;
import com.lionclient.feature.setting.Setting;
import java.awt.Desktop;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;

public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static ConfigManager instance;
    private static boolean suppressSave;

    private final ModuleManager moduleManager;
    private final File configDirectory;
    private final File currentConfigFile;
    private String currentConfigName;

    public ConfigManager(ModuleManager moduleManager) {
        this.moduleManager = moduleManager;
        File base = new File(Minecraft.getMinecraft().mcDataDir, "lionclient");
        this.configDirectory = new File(base, "configs");
        this.currentConfigFile = new File(base, "current-config.txt");
        instance = this;
    }

    public static void saveActiveConfig() {
        if (instance != null && !suppressSave) {
            instance.saveCurrent();
            instance.moduleManager.refreshConfigModule();
        }
    }

    public void initialize() {
        ensureDirectory();
        currentConfigName = readCurrentConfigName();
        if (currentConfigName == null || !getConfigFile(currentConfigName).isFile()) {
            currentConfigName = listConfigs().isEmpty() ? "default" : listConfigs().get(0);
        }
        if (!getConfigFile(currentConfigName).isFile()) {
            saveAs(currentConfigName);
        }
        persistCurrentConfigName();
        applyConfig(currentConfigName);
    }

    public List<String> listConfigs() {
        ensureDirectory();
        File[] files = configDirectory.listFiles();
        List<String> names = new ArrayList<String>();
        if (files == null) {
            return names;
        }

        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".json")) {
                names.add(file.getName().substring(0, file.getName().length() - 5));
            }
        }

        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public String getCurrentConfigName() {
        return currentConfigName;
    }

    public void saveCurrent() {
        if (currentConfigName == null) {
            currentConfigName = "default";
        }
        saveAs(currentConfigName);
    }

    public void createNextConfig() {
        saveCurrent();
        String name = nextConfigName();
        saveAs(name);
        currentConfigName = name;
        persistCurrentConfigName();
    }

    public void deleteCurrentConfig() {
        List<String> configs = listConfigs();
        if (currentConfigName == null || configs.isEmpty()) {
            return;
        }

        File current = getConfigFile(currentConfigName);
        if (current.isFile() && !current.delete()) {
            return;
        }

        List<String> remaining = listConfigs();
        if (remaining.isEmpty()) {
            currentConfigName = "default";
            saveAs(currentConfigName);
        } else {
            currentConfigName = remaining.get(0);
            applyConfig(currentConfigName);
        }
        persistCurrentConfigName();
    }

    public void load(String name) {
        saveCurrent();
        applyConfig(name);
    }

    private void applyConfig(String name) {
        File file = getConfigFile(name);
        if (!file.isFile()) {
            return;
        }

        try {
            suppressSave = true;
            String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            JsonObject root = new JsonParser().parse(raw).getAsJsonObject();
            JsonObject modules = root.has("modules") ? root.getAsJsonObject("modules") : new JsonObject();
            applyRecordedPattern(root);

            for (Module module : moduleManager.getModules()) {
                JsonObject moduleJson = modules.has(module.getName()) ? modules.getAsJsonObject(module.getName()) : null;
                if (moduleJson == null) {
                    continue;
                }

                if (moduleJson.has("enabled")) {
                    module.setEnabled(moduleJson.get("enabled").getAsBoolean());
                }

                JsonObject settingsJson = moduleJson.has("settings") ? moduleJson.getAsJsonObject("settings") : null;
                if (settingsJson == null) {
                    continue;
                }

                for (Setting setting : module.getSettings()) {
                    if (!settingsJson.has(setting.getName())) {
                        continue;
                    }

                    JsonElement value = settingsJson.get(setting.getName());
                    if (setting instanceof BooleanSetting) {
                        ((BooleanSetting) setting).setEnabled(value.getAsBoolean());
                    } else if (setting instanceof DecimalSetting) {
                        ((DecimalSetting) setting).setValue(value.getAsDouble());
                    } else if (setting instanceof NumberSetting) {
                        ((NumberSetting) setting).setValue(value.getAsInt());
                    } else if (setting instanceof EnumSetting) {
                        ((EnumSetting<?>) setting).setValueByName(value.getAsString());
                    }
                }
            }

            currentConfigName = name;
            persistCurrentConfigName();
            moduleManager.refreshConfigModule();
        } catch (Exception ignored) {
        } finally {
            suppressSave = false;
        }
    }

    public void openFolder() {
        ensureDirectory();
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(configDirectory);
            }
        } catch (IOException ignored) {
        }
    }

    private void saveAs(String name) {
        ensureDirectory();
        File file = getConfigFile(name);
        JsonObject root = new JsonObject();
        JsonObject modulesJson = new JsonObject();
        root.add("clickPattern", serializeRecordedPattern());

        for (Module module : moduleManager.getModules()) {
            JsonObject moduleJson = new JsonObject();
            moduleJson.addProperty("enabled", module.isEnabled());

            JsonObject settingsJson = new JsonObject();
            for (Setting setting : module.getSettings()) {
                if (setting instanceof ActionSetting) {
                    continue;
                }
                if (setting instanceof BooleanSetting) {
                    settingsJson.addProperty(setting.getName(), ((BooleanSetting) setting).isEnabled());
                } else if (setting instanceof DecimalSetting) {
                    settingsJson.addProperty(setting.getName(), ((DecimalSetting) setting).getValue());
                } else if (setting instanceof NumberSetting) {
                    settingsJson.addProperty(setting.getName(), ((NumberSetting) setting).getValue());
                } else if (setting instanceof EnumSetting) {
                    settingsJson.addProperty(setting.getName(), ((EnumSetting<?>) setting).getValue().name());
                }
            }

            moduleJson.add("settings", settingsJson);
            modulesJson.add(module.getName(), moduleJson);
        }

        root.add("modules", modulesJson);
        try {
            Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
            try {
                GSON.toJson(root, writer);
            } finally {
                writer.close();
            }
        } catch (IOException ignored) {
        }
    }

    private JsonObject serializeRecordedPattern() {
        JsonObject patternJson = new JsonObject();
        List<Integer> delays = ClickPatternStore.getDelays();
        for (int i = 0; i < delays.size(); i++) {
            patternJson.addProperty(Integer.toString(i), delays.get(i).intValue());
        }
        return patternJson;
    }

    private void applyRecordedPattern(JsonObject root) {
        ClickPatternStore.clear();
        if (!root.has("clickPattern")) {
            return;
        }

        JsonObject patternJson = root.getAsJsonObject("clickPattern");
        int index = 0;
        while (patternJson.has(Integer.toString(index))) {
            ClickPatternStore.addDelay(patternJson.get(Integer.toString(index)).getAsInt());
            index++;
        }
    }

    private File getConfigFile(String name) {
        return new File(configDirectory, sanitize(name) + ".json");
    }

    private void ensureDirectory() {
        if (!configDirectory.isDirectory()) {
            configDirectory.mkdirs();
        }
        File parent = currentConfigFile.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
    }

    private String readCurrentConfigName() {
        if (!currentConfigFile.isFile()) {
            return null;
        }

        try {
            return sanitize(new String(Files.readAllBytes(currentConfigFile.toPath()), StandardCharsets.UTF_8).trim());
        } catch (IOException ignored) {
            return null;
        }
    }

    private void persistCurrentConfigName() {
        try {
            Files.write(currentConfigFile.toPath(), currentConfigName.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private String nextConfigName() {
        List<String> configs = listConfigs();
        int index = 1;
        while (configs.contains("config-" + index)) {
            index++;
        }
        return "config-" + index;
    }

    private String sanitize(String input) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                builder.append(c);
            }
        }
        return builder.length() == 0 ? "default" : builder.toString();
    }
}
