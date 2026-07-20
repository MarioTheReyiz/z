package me.pewa;

import java.io.FileWriter;
import java.io.IOException;
import java.util.List;
import me.pewa.loader.CraftRiseTransformerClassLoader;
import me.pewa.loader.GuiIngameHookTransformer;
import me.pewa.mapping.MinecraftMapper;
import me.pewa.util.ChatUtils;
import me.pewa.util.HardcodedUtils;
import me.pewa.util.Logger;
import me.pewa.util.MappingUtils;

public class Main {
    
    /**
     * C++ loader tarafından çağrılır
     * @param classes Yüklü tüm Java sınıfları
     */
    public static void StartClient(List<Class<?>> classes) {
        try {
            System.out.println("[PEWA] StartClient called from C++");
            System.out.println("[PEWA] Classes count: " + classes.size());
            
            Logger.info("StartClient called from C++");
            Logger.info("Loaded classes count: " + classes.size());
            
            // Debug: txt dosyası oluştur
            System.out.println("[PEWA] Creating jar_loaded.txt...");
            java.io.File pewaDir = new java.io.File("C:\\pewa");
            if (!pewaDir.exists()) {
                pewaDir.mkdirs();
                System.out.println("[PEWA] Created C:\\pewa directory");
            }
            
            FileWriter writer = new FileWriter("C:\\pewa\\jar_loaded.txt");
            writer.write("JAR LOADED SUCCESSFULLY!\n");
            writer.write("Timestamp: " + System.currentTimeMillis() + "\n");
            writer.write("Classes loaded: " + classes.size() + "\n");
            writer.close();
            System.out.println("[PEWA] jar_loaded.txt created");

            Logger.info("Registering Java GuiIngame HUD hook...");
            GuiIngameHookTransformer.register();

            Logger.info("Installing CraftRise transformer classloader...");
            CraftRiseTransformerClassLoader.install(classes);
            
            // AutoMapper ile sınıfları tara ve map et
            Logger.info("Starting AutoMapper scan...");
            MinecraftMapper.scanAndMap(classes);
            Logger.info("AutoMapper scan complete");
            Logger.info("Mapping stats: " + MappingUtils.getStats());

            // Eksik mapping'leri hardcoded olarak tamamla
            Logger.info("Applying hardcoded mappings...");
            HardcodedUtils.apply();
            Logger.info("Mapping stats after hardcoded: " + MappingUtils.getStats());
            
            System.out.println("[+] JAR loaded - mappings created");
            
            // Pewa'yı başlat
            Logger.info("Initializing Pewa...");
            Pewa.initialize();
            
            // Test ChatUtils
            Logger.info("Testing ChatUtils...");
            ChatUtils.addChatMessage("§a[Pewa] §fCheat loaded successfully!");
            ChatUtils.addChatMessage("§a[Pewa] §f" + MappingUtils.getStats());
            
            Logger.info("StartClient completed");
            System.out.println("[PEWA] StartClient completed successfully");
            
        } catch (IOException e) {
            System.err.println("[PEWA ERROR] IO Error: " + e.getMessage());
            Logger.error("IO Error: " + e.getMessage());
            e.printStackTrace();
        } catch (Throwable e) {
            System.err.println("[PEWA ERROR] Error in StartClient: " + e.getMessage());
            Logger.error("Error in StartClient: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public static void main(String[] args) {
        Logger.info("Main method called (not used by C++ loader)");
    }
}
