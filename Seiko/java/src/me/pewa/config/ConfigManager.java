package me.pewa.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import me.pewa.module.Module;
import me.pewa.module.ModuleManager;
import me.pewa.module.impl.ClickGuiModule;
import me.pewa.setting.OptionBase;
import me.pewa.util.Logger;

public final class ConfigManager {
    private static final ConfigManager INSTANCE = new ConfigManager();
    private static final Type CONFIG_TYPE = new TypeToken<Map<String, Object>>() { }.getType();

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final File directory = new File("C:\\pewa\\configs");
    private String currentConfig = "default";

    private ConfigManager() {
    }

    public static ConfigManager getInstance() {
        return INSTANCE;
    }

    public String getCurrentConfig() {
        return currentConfig;
    }

    public List<String> getConfigList() {
        ensureDirectory();
        List<String> configs = new ArrayList<String>();
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (file.isFile() && name.toLowerCase().endsWith(".json")) {
                    configs.add(name.substring(0, name.length() - 5));
                }
            }
        }
        if (!configs.contains("default")) {
            configs.add("default");
        }
        Collections.sort(configs, String.CASE_INSENSITIVE_ORDER);
        return configs;
    }

    public void saveConfig(ModuleManager manager, String name) {
        if (manager == null) {
            return;
        }
        String safeName = sanitize(name);
        ensureDirectory();

        Map<String, Object> root = new HashMap<String, Object>();
        Map<String, Object> modules = new HashMap<String, Object>();
        for (Module module : manager.getModules()) {
            Map<String, Object> moduleData = new HashMap<String, Object>();
            moduleData.put("enabled", module.isEnabled());
            moduleData.put("keyBind", module.getKeyBind());

            Map<String, Object> options = new HashMap<String, Object>();
            for (OptionBase<?> option : module.getOptions()) {
                options.put(option.getName(), option.toConfigValue());
            }
            moduleData.put("options", options);
            modules.put(module.getName(), moduleData);
        }
        root.put("modules", modules);

        FileWriter writer = null;
        try {
            writer = new FileWriter(fileFor(safeName));
            gson.toJson(root, writer);
            currentConfig = safeName;
        } catch (Throwable t) {
            Logger.warn("Config save failed: " + t.getMessage());
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    public void loadConfig(ModuleManager manager, String name) {
        if (manager == null) {
            return;
        }
        String safeName = sanitize(name);
        File file = fileFor(safeName);
        if (!file.isFile()) {
            currentConfig = safeName;
            return;
        }

        FileReader reader = null;
        try {
            reader = new FileReader(file);
            Map<String, Object> root = gson.fromJson(reader, CONFIG_TYPE);
            Object modulesObj = root == null ? null : root.get("modules");
            if (!(modulesObj instanceof Map)) {
                return;
            }

            Map<String, Object> modules = (Map<String, Object>) modulesObj;
            for (Module module : manager.getModules()) {
                Object dataObj = modules.get(module.getName());
                if (!(dataObj instanceof Map)) {
                    continue;
                }
                Map<String, Object> moduleData = (Map<String, Object>) dataObj;

                Object key = moduleData.get("keyBind");
                if (key instanceof Number) {
                    module.setKeyBind(((Number) key).intValue());
                }

                Object optionsObj = moduleData.get("options");
                if (optionsObj instanceof Map) {
                    Map<String, Object> options = (Map<String, Object>) optionsObj;
                    for (OptionBase<?> option : module.getOptions()) {
                        if (options.containsKey(option.getName())) {
                            option.fromConfigValue(options.get(option.getName()));
                        }
                    }
                }

                Object enabled = moduleData.get("enabled");
                if (enabled instanceof Boolean && !(module instanceof ClickGuiModule)) {
                    boolean shouldEnable = (Boolean) enabled;
                    if (shouldEnable && !module.isEnabled()) {
                        module.enable();
                    } else if (!shouldEnable && module.isEnabled()) {
                        module.disable();
                    }
                }
            }
            currentConfig = safeName;
        } catch (Throwable t) {
            Logger.warn("Config load failed: " + t.getMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public boolean deleteConfig(String name) {
        String safeName = sanitize(name);
        if ("default".equalsIgnoreCase(safeName)) {
            return false;
        }
        File file = fileFor(safeName);
        return file.isFile() && file.delete();
    }

    private void ensureDirectory() {
        if (!directory.exists()) {
            directory.mkdirs();
        }
    }

    private File fileFor(String name) {
        ensureDirectory();
        return new File(directory, sanitize(name) + ".json");
    }

    private String sanitize(String name) {
        String safe = name == null ? "" : name.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
        return safe.length() == 0 ? "default" : safe;
    }
}
