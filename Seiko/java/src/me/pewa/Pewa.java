package me.pewa;

import me.pewa.event.EventBus;
import me.pewa.module.ModuleManager;
import me.pewa.packethook.PacketHook;
import me.pewa.util.HardcodedUtils;
import me.pewa.util.Logger;

/**
 * Pewa - Minecraft 1.8.9 Cheat Client
 * Main entry point for the cheat
 */
public class Pewa {
    private static Pewa instance;
    private EventBus eventBus;
    private ModuleManager moduleManager;
    private boolean initialized = false;

    private Pewa() {
        this.eventBus = new EventBus();
        this.moduleManager = new ModuleManager();
    }

    /**
     * Initialize Pewa cheat
     * Called from C++ loader
     */
    public static void initialize() {
        Logger.info("Initializing Pewa cheat...");
        
        if (instance == null) {
            instance = new Pewa();
        }
        
        instance.init();
    }

    private void init() {
        try {
            Logger.info("Loading modules...");
            moduleManager.loadModules();
            
            Logger.info("Registering event listeners...");
            moduleManager.registerEventListeners(eventBus);

            Logger.info("Starting packet hook...");
            PacketHook.get().init();
            
            Logger.info("Starting event loop...");
            initialized = true;
            startEventLoop();
            Logger.info("Pewa initialized successfully!");
        } catch (Throwable e) {
            initialized = false;
            Logger.error("Failed to initialize Pewa: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void startEventLoop() {
        // Event loop akan berjalan di background thread
        Thread eventThread = new Thread(() -> {
            while (initialized) {
                try {
                    moduleManager.handleKeyBinds();
                    moduleManager.updateModules();
                    // Update BotTracker for all entities every tick
                    me.pewa.util.BotTracker.observeAll();
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Throwable t) {
                    Logger.warn("Pewa event loop error: " + String.valueOf(t.getMessage()));
                }
            }
        });
        
        eventThread.setName("Pewa-EventLoop");
        eventThread.setDaemon(true);
        eventThread.start();
    }

    public static Pewa getInstance() {
        if (instance == null) {
            instance = new Pewa();
        }
        return instance;
    }

    public EventBus getEventBus() {
        return eventBus;
    }

    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void shutdown() {
        Logger.info("Shutting down Pewa...");
        initialized = false;
        PacketHook.get().shutdown();
        moduleManager.disableAllModules();
    }
}
