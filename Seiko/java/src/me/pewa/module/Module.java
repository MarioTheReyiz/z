package me.pewa.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import me.pewa.util.MappingUtils;
import me.pewa.setting.OptionBase;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import me.pewa.util.Logger;

public abstract class Module {
    protected String name;
    protected String description;
    protected Category category;
    protected int keyBind;
    protected boolean enabled = false;
    private final List<OptionBase<?>> options = new ArrayList<OptionBase<?>>();
    
    public Module(String name, String description) {
        this(name, description, Category.MISC, 0);
    }

    public Module(String name, String description, Category category, int keyBind) {
        this.name = name;
        this.description = description;
        this.category = category == null ? Category.MISC : category;
        this.keyBind = keyBind;
    }
    
    public abstract void onEnable();
    public abstract void onDisable();
    public abstract void onUpdate();
    
    public void toggle() {
        if (enabled) {
            disable();
        } else {
            enable();
        }
    }
    
    public void enable() {
        if (!enabled) {
            try {
                onEnable();
                enabled = true;
            } catch (Throwable t) {
                enabled = false;
                Logger.warn("Module enable failed for " + name + " - " + String.valueOf(t.getMessage()));
            }
        }
    }
    
    public void disable() {
        if (enabled) {
            try {
                onDisable();
            } catch (Throwable t) {
                Logger.warn("Module disable failed for " + name + " - " + String.valueOf(t.getMessage()));
            } finally {
                enabled = false;
            }
        }
    }
    
    public String getName() {
        return name;
    }
    
    public String getDescription() {
        return description;
    }
    
    public boolean isEnabled() {
        return enabled;
    }

    public Category getCategory() {
        return category;
    }

    public int getKeyBind() {
        return keyBind;
    }

    public void setKeyBind(int keyBind) {
        this.keyBind = keyBind;
    }

    public List<OptionBase<?>> getOptions() {
        return Collections.unmodifiableList(options);
    }

    public OptionBase<?> getOption(String name) {
        if (name == null) {
            return null;
        }
        for (OptionBase<?> option : options) {
            if (option.getName().equalsIgnoreCase(name)) {
                return option;
            }
        }
        return null;
    }

    protected void addOption(OptionBase<?> option) {
        if (option != null) {
            options.add(option);
        }
    }

    protected void addOptions(OptionBase<?>... options) {
        if (options == null) {
            return;
        }
        for (OptionBase<?> option : options) {
            addOption(option);
        }
    }
    
    // ========================================
    // HELPER METHODS FOR MODULES
    // ========================================
    
    /**
     * Get Minecraft instance
     */
    protected Object getMinecraft() {
        try {
            Class<?> mc = MappingUtils.get("Minecraft");
            if (mc == null) return null;
            
            Method getInstance = MappingUtils.getMethod("Minecraft.getInstance");
            if (getInstance != null) {
                getInstance.setAccessible(true);
                return getInstance.invoke(null);
            }

            Field theMinecraft = MappingUtils.getField("Minecraft.theMinecraft");
            if (theMinecraft == null) return null;

            theMinecraft.setAccessible(true);
            return theMinecraft.get(null);
        } catch (Throwable e) {
            return null;
        }
    }
    
    /**
     * Get the player
     */
    protected Object getThePlayer() {
        try {
            Object mc = getMinecraft();
            if (mc == null) return null;
            
            Method getPlayer = MappingUtils.getMethod("Minecraft.getThePlayer");
            if (getPlayer != null) {
                getPlayer.setAccessible(true);
                return getPlayer.invoke(mc);
            }

            Field thePlayer = MappingUtils.getField("Minecraft.thePlayer");
            if (thePlayer == null) return null;

            thePlayer.setAccessible(true);
            return thePlayer.get(mc);
        } catch (Throwable e) {
            return null;
        }
    }
    
    /**
     * Get the world
     */
    protected Object getTheWorld() {
        try {
            Object mc = getMinecraft();
            if (mc == null) return null;
            
            Method getWorld = MappingUtils.getMethod("Minecraft.getTheWorld");
            if (getWorld != null) {
                getWorld.setAccessible(true);
                return getWorld.invoke(mc);
            }

            Object player = getThePlayer();
            if (player == null) return null;

            Field worldObj = MappingUtils.getField("EntityPlayerSP.worldObj");
            if (worldObj == null) return null;

            worldObj.setAccessible(true);
            return worldObj.get(player);
        } catch (Throwable e) {
            return null;
        }
    }
}

