package me.pewa.module;

import me.pewa.event.EventBus;
import me.pewa.module.impl.AimBotModule;
import me.pewa.module.impl.ClickGuiModule;
import me.pewa.module.impl.FlyModule;
import me.pewa.module.impl.KillAuraModule;
import me.pewa.module.impl.ReachModule;
import me.pewa.module.impl.SpotifyWidget;
import me.pewa.module.impl.TestModule;
import me.pewa.util.Logger;
import java.util.*;
import org.lwjgl.input.Keyboard;

public class ModuleManager {
    private List<Module> modules = new ArrayList<>();
    private Map<Module, Boolean> keyStates = new IdentityHashMap<Module, Boolean>();
    
    public void loadModules() {
        Logger.info("ModuleManager: Loading modules...");
        modules.clear();
        keyStates.clear();
        
        // Add modules here
        addModule(new ClickGuiModule());
        addModule(new SpotifyWidget());
        // Enable Spotify widget by default so it appears on first run
        try {
            SpotifyWidget sw = getModule(SpotifyWidget.class);
            if (sw != null && !sw.isEnabled()) sw.enable();
        } catch (Throwable ignored) {}
        addModule(new KillAuraModule());
        addModule(new ReachModule());
        addModule(new AimBotModule());
        addModule(new FlyModule());
        addModule(new TestModule());
        
        Logger.info("ModuleManager: Loaded " + modules.size() + " modules");
    }
    
    public void registerEventListeners(EventBus eventBus) {
        Logger.info("ModuleManager: Registering event listeners...");
        // Event listeners sonra eklenecek
    }

    public void handleKeyBinds() {
        try {
            if (!Keyboard.isCreated()) {
                return;
            }

            for (Module module : modules) {
                if (module instanceof ClickGuiModule) {
                    continue;
                }

                int key = module.getKeyBind();
                if (key <= 0) {
                    keyStates.put(module, Boolean.FALSE);
                    continue;
                }

                boolean down = Keyboard.isKeyDown(key);
                boolean wasDown = Boolean.TRUE.equals(keyStates.get(module));
                if (down && !wasDown) {
                    Logger.info("ModuleManager: Toggling " + module.getName() + " with key " + Keyboard.getKeyName(key));
                    module.toggle();
                }
                keyStates.put(module, Boolean.valueOf(down));
            }
        } catch (Throwable t) {
            Logger.warn("ModuleManager: Keybind handling failed - " + String.valueOf(t.getMessage()));
        }
    }

    public void updateModules() {
        for (Module module : new ArrayList<Module>(modules)) {
            if (!module.isEnabled()) {
                continue;
            }

            try {
                module.onUpdate();
            } catch (Throwable t) {
                Logger.warn("ModuleManager: Update failed for " + module.getName() + " - " + String.valueOf(t.getMessage()));
            }
        }
    }
    
    public void disableAllModules() {
        for (Module module : modules) {
            if (module.isEnabled()) {
                module.disable();
            }
        }
    }
    
    public void addModule(Module module) {
        modules.add(module);
        Logger.info("ModuleManager: Added module - " + module.getName());
    }
    
    public Module getModule(String name) {
        for (Module module : modules) {
            if (module.getName().equalsIgnoreCase(name)) {
                return module;
            }
        }
        return null;
    }

    public <T extends Module> T getModule(Class<T> moduleClass) {
        for (Module module : modules) {
            if (moduleClass.isInstance(module)) {
                return moduleClass.cast(module);
            }
        }
        return null;
    }
    
    public List<Module> getModules() {
        return new ArrayList<>(modules);
    }
}

