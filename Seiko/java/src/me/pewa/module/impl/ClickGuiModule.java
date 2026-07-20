package me.pewa.module.impl;

import java.awt.Color;
import me.pewa.module.Category;
import me.pewa.module.Module;
import me.pewa.setting.BooleanOption;
import me.pewa.setting.ColorOption;
import me.pewa.setting.NumberOption;
import me.pewa.setting.StringOption;
import me.pewa.ui.ClickGui;
import org.lwjgl.input.Keyboard;

public class ClickGuiModule extends Module {
    public ClickGuiModule() {
        super("ClickGUI", "Interactive module menu", Category.RENDER, Keyboard.KEY_RSHIFT);
        addOptions(
                new NumberOption("UI Scale", 1.5D, 1.0D, 2.0D, 0.05D, this).setGroup("Visual"),
                new NumberOption("Overlay Alpha", 0.58D, 0.20D, 0.85D, 0.01D, this).setGroup("Visual"),
                new BooleanOption("Wave Background", true, this).setGroup("Visual"),
                new NumberOption("Wave Alpha", 0.36D, 0.05D, 0.65D, 0.01D, this).setGroup("Visual")
                        .setDependency("Wave Background:true"),
                new BooleanOption("Soft Shadows", true, this).setGroup("Visual"),
                new BooleanOption("Background Blur", false, this).setGroup("Visual"),
                new NumberOption("Blur Radius", 12.0D, 2.0D, 24.0D, 1.0D, this).setGroup("Visual")
                        .setDependency("Background Blur:true"),
                new StringOption("Panel Style", "Glass", this, "Glass", "Solid", "Compact").setGroup("Panels"),
                new ColorOption("Accent", new Color(228, 228, 231, 255), this).setGroup("Panels"));
    }

    @Override
    public void onEnable() {
        ClickGui.open();
    }

    @Override
    public void onDisable() {
        ClickGui.close();
    }

    @Override
    public void onUpdate() {
    }
}
