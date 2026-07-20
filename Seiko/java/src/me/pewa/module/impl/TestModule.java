package me.pewa.module.impl;

import java.awt.Color;
import me.pewa.module.Category;
import me.pewa.module.Module;
import me.pewa.setting.BooleanOption;
import me.pewa.setting.ColorOption;
import me.pewa.setting.NumberOption;
import me.pewa.setting.StringOption;
import me.pewa.setting.TextOption;
import me.pewa.util.ChatUtils;
import me.pewa.util.Logger;

/**
 * Test Module - Example module using AutoMapper
 */
public class TestModule extends Module {
    
    public TestModule() {
        super("Test", "Test module using AutoMapper", Category.MISC, 0);
        addOptions(
                new BooleanOption("Chat Messages", true, this).setGroup("General"),
                new NumberOption("Scan Radius", 24.0D, 4.0D, 96.0D, 1.0D, this).setGroup("General"),
                new StringOption("Mode", "Auto", this, "Auto", "Passive", "Verbose").setGroup("General"),
                new ColorOption("Debug Color", new Color(16, 185, 129, 255), this).setGroup("Visual"),
                new TextOption("Note", "AutoMapper smoke", this).setGroup("Visual"));
    }
    
    @Override
    public void onEnable() {
        Logger.info("TestModule enabled");
        ChatUtils.addChatMessage("§a[Test] §fModule enabled!");
        
        // Test AutoMapper
        Object player = getThePlayer();
        if (player != null) {
            Logger.info("Player found: " + player.getClass().getName());
            ChatUtils.addChatMessage("§a[Test] §fPlayer: " + player.getClass().getSimpleName());
        } else {
            Logger.warn("Player not found!");
        }
    }
    
    @Override
    public void onDisable() {
        Logger.info("TestModule disabled");
        ChatUtils.addChatMessage("§c[Test] §fModule disabled!");
    }
    
    @Override
    public void onUpdate() {
        // Update logic here
    }
}
