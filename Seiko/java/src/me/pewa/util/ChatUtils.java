package me.pewa.util;

import java.lang.reflect.Method;
import java.lang.reflect.Field;

public class ChatUtils {
    
    public static void addChatMessage(String message) {
        try {
            Object player = getThePlayer();
            if (player == null) {
                Logger.warn("ChatUtils: Player is null");
                return;
            }
            
            Class<?> chatComponentTextClass = MappingUtils.get("ChatComponentText");
            if (chatComponentTextClass == null) {
                Logger.warn("ChatUtils: ChatComponentText class not found");
                return;
            }
            
            Object chatComponent = chatComponentTextClass.getConstructor(String.class).newInstance(message);
            
            Method addChatMethod = MappingUtils.getMethod("EntityPlayerSP.addChatMessage");
            if (addChatMethod != null) {
                addChatMethod.setAccessible(true);
                addChatMethod.invoke(player, chatComponent);
            } else {
                Logger.warn("ChatUtils: addChatMessage method not found");
            }
        } catch (Throwable e) {
            Logger.error("ChatUtils error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static Object getThePlayer() {
        try {
            Class<?> minecraftClass = MappingUtils.get("Minecraft");
            if (minecraftClass == null) {
                Logger.warn("ChatUtils: Minecraft class not found");
                return null;
            }
            
            Object minecraft = getMinecraftInstance();
            
            if (minecraft == null) {
                Logger.warn("ChatUtils: Minecraft instance is null");
                return null;
            }
            
            Method getPlayerMethod = MappingUtils.getMethod("Minecraft.getThePlayer");
            if (getPlayerMethod != null) {
                getPlayerMethod.setAccessible(true);
                return getPlayerMethod.invoke(minecraft);
            }

            Field thePlayerField = MappingUtils.getField("Minecraft.thePlayer");
            if (thePlayerField != null) {
                thePlayerField.setAccessible(true);
                return thePlayerField.get(minecraft);
            }

            Logger.warn("ChatUtils: getThePlayer mapping not found");
            return null;
        } catch (Throwable e) {
            Logger.error("ChatUtils.getThePlayer error: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    private static Object getMinecraftInstance() throws Exception {
        Method getInstanceMethod = MappingUtils.getMethod("Minecraft.getInstance");
        if (getInstanceMethod != null) {
            getInstanceMethod.setAccessible(true);
            return getInstanceMethod.invoke(null);
        }

        Field theMinecraftField = MappingUtils.getField("Minecraft.theMinecraft");
        if (theMinecraftField != null) {
            theMinecraftField.setAccessible(true);
            return theMinecraftField.get(null);
        }

        Logger.warn("ChatUtils: Minecraft instance mapping not found");
        return null;
    }
}
