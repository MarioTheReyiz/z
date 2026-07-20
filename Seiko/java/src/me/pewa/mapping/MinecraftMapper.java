package me.pewa.mapping;

import me.pewa.loader.ClassByteStore;
import me.pewa.util.Logger;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import java.nio.ByteBuffer;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.lang.invoke.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import com.google.common.collect.*;
import com.google.gson.*;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import sun.misc.Unsafe;
import static org.objectweb.asm.Opcodes.*;

public class MinecraftMapper {

    // Helper cache for classes
    private static List<Class<?>> cachedClasses = null;
    
    public static List<Class<?>> getClasses() {
        if (cachedClasses == null) {
            cachedClasses = loadGameClasses(Collections.emptyList());
        }
        return cachedClasses;
    }

    public static void scanAndMap(List<Class<?>> classes) {
        AutoMapper.clear();
        cachedClasses = loadGameClasses(classes);
        syncAutoMapperClassCache();
        Logger.info("MinecraftMapper input classes: " + (classes != null ? classes.size() : 0));
        Logger.info("MinecraftMapper game classes: " + cachedClasses.size());
        try {
            Logger.info("Starting ASM startMappings...");
            startMappings();
            Logger.info("ASM startMappings complete!");
        } catch (Throwable e) {
            Logger.error("Error in ASM startMappings: " + e.getMessage());
            e.printStackTrace();
        }

        runMappingStep("mapEssentialClientMappings(pre)", () -> mapEssentialClientMappings());

        try {
            Logger.info("Starting full best-effort automapper pass...");
            runFullAutomapperPass();
            Logger.info("Full best-effort automapper pass complete!");
        } catch (Throwable e) {
            Logger.error("Error in full automapper pass: " + e.getMessage());
            e.printStackTrace();
        }

        runMappingStep("mapEssentialClientMappings(post)", () -> mapEssentialClientMappings());

        try {
            AutoMapper.exportToFile("C:\\pewa\\mappings.txt");
        } catch (Throwable e) {
            Logger.error("Error exporting mappings: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void syncAutoMapperClassCache() {
        AutoMapper.cachedClasses.clear();
        if (cachedClasses != null) {
            AutoMapper.cachedClasses.addAll(cachedClasses);
        }
    }

    private static List<Class<?>> loadGameClasses(List<Class<?>> fallbackClasses) {
        LinkedHashMap<String, Class<?>> found = new LinkedHashMap<>();

        for (Class<?> clazz : ClassByteStore.snapshot()) {
            addGameClass(found, clazz);
        }

        try {
            Field customClassesField = ClassLoader.class.getDeclaredField("customClasses");
            customClassesField.setAccessible(true);
            Object customClasses = customClassesField.get(null);

            if (customClasses instanceof Collection) {
                Collection<?> snapshot;
                synchronized (customClasses) {
                    snapshot = new ArrayList<>((Collection<?>) customClasses);
                }

                for (Object item : snapshot) {
                    if (item instanceof Class<?>) {
                        addGameClass(found, (Class<?>) item);
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        if (fallbackClasses != null) {
            for (Class<?> clazz : fallbackClasses) {
                addGameClass(found, clazz);
            }
        }

        return new ArrayList<>(found.values());
    }

    private static void addGameClass(Map<String, Class<?>> classes, Class<?> clazz) {
        if (clazz == null) {
            return;
        }

        String name = clazz.getName();
        if (isGameRuntimeClassName(name)) {
            classes.putIfAbsent(name, clazz);
        }
    }

    private static boolean isGameRuntimeClass(Class<?> clazz) {
        return clazz != null
                && !clazz.isPrimitive()
                && !clazz.isArray()
                && isGameRuntimeClassName(clazz.getName());
    }

    private static boolean isGameRuntimeClassName(String className) {
        return className != null
                && (className.startsWith("craftrise.")
                || className.startsWith("crsecond.")
                || className.startsWith("cr.")
                || className.startsWith("com.craftrise."));
    }

    private static boolean isEntityPlayerSPHierarchy(Class<?> cls) {
        Class<?> current = cls;
        for (int i = 0; i < 4; i++) {
            if (current == null) {
                return false;
            }
            current = current.getSuperclass();
            if (!isGameRuntimeClass(current)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlockPosCandidate(Class<?> clazz) {
        if (!isGameRuntimeClass(clazz) || clazz.isInterface() || clazz.isEnum()) {
            return false;
        }

        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            Class<?>[] params = constructor.getParameterTypes();
            if (params.length == 3
                    && params[0] == int.class
                    && params[1] == int.class
                    && params[2] == int.class) {
                return true;
            }
        }
        return false;
    }
    
    // Redirect put/get/etc to AutoMapper
    public static void put(String name, Class<?> clazz) {
        AutoMapper.put(name, clazz);
    }
    
    public static void putField(String fullName, Field field) {
        AutoMapper.putField(fullName, field);
    }
    
    public static void putMethod(String fullName, Method method) {
        AutoMapper.putMethod(fullName, method);
    }
    
    public static Class<?> get(String name) {
        return AutoMapper.get(name);
    }
    
    public static Field getField(String fullName) {
        return AutoMapper.getField(fullName);
    }
    
    public static Method getMethod(String fullName) {
        return AutoMapper.getMethod(fullName);
    }
    
    public static boolean contains(String name) {
        return AutoMapper.contains(name);
    }

    public static byte[] getClassBytes(Class<?> clazz) {
        return AutoMapper.getClassBytes(clazz);
    }

    private static boolean missingClassBytes(String context, Class<?> clazz, byte[] bytes) {
        if (clazz == null) {
            warnMappingSkip(context, "class is null");
            return true;
        }
        if (bytes == null || bytes.length == 0) {
            warnMappingSkip(context, "class bytes not found for " + clazz.getName());
            return true;
        }
        return false;
    }

    private static boolean missingClassBytes(String context, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            warnMappingSkip(context, "class bytes not found");
            return true;
        }
        return false;
    }

    public static void saveClass(Class<?> clazz, String name) {
        AutoMapper.saveClass(clazz, name);
    }

    private static final Random random = new Random();

    // Mock/Dummy helper fields/methods needed by mapping methods but not compiled otherwise
    public static Object getMinecraft() {
        try {
            Field theMinecraft = getField("Minecraft.theMinecraft");
            if (theMinecraft != null) {
                return theMinecraft.get(null);
            }
            Method getInstance = getMethod("Minecraft.getInstance");
            if (getInstance != null) {
                return getInstance.invoke(null);
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static Object getPlayer() {
        try {
            Object mc = getMinecraft();
            if (mc != null) {
                Method getThePlayer = getMethod("Minecraft.getThePlayer");
                if (getThePlayer != null) {
                    return getThePlayer.invoke(mc);
                }
                Field thePlayer = getField("Minecraft.thePlayer");
                if (thePlayer != null) {
                    return thePlayer.get(mc);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static Object getThePlayer() {
        return getPlayer();
    }

    public static Object getInventory() {
        try {
            Object player = getPlayer();
            if (player != null) {
                Field inventory = getField("EntityPlayer.inventory");
                if (inventory != null) {
                    return inventory.get(player);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static boolean attackEntity(Object entity) {
        ByteBuf packetBuffer = null;
        try {
            if (entity == null) {
                warnMappingSkip("attackEntity", "entity is null");
                return false;
            }

            int entityId = getEntityIdForAttackPacket(entity);
            if (entityId < 0) {
                warnMappingSkip("attackEntity", "entity id could not be resolved");
                return false;
            }

            Channel channel = getNetworkChannel();
            if (channel == null) {
                warnMappingSkip("attackEntity", "network channel is not available");
                return false;
            }
            if (!channel.isActive()) {
                warnMappingSkip("attackEntity", "network channel is not active");
                return false;
            }

            Class<?> actionClass = get("C02PacketUseEntityAction");
            if (actionClass == null) {
                warnMappingSkip("attackEntity", "C02PacketUseEntityAction is not mapped");
                return false;
            }

            Enum<?> attackEnum = getAttackActionEnum(actionClass);
            if (attackEnum == null) {
                warnMappingSkip("attackEntity", "ATTACK action enum is not available");
                return false;
            }

            float hitHeight = 1.1F + (float) (Math.random() * 0.65D);
            packetBuffer = Unpooled.buffer();
            packetBuffer.writeByte(2);
            writeVarInt(packetBuffer, entityId);
            writeEnumValueToByteBuf(packetBuffer, attackEnum);
            packetBuffer.writeFloat(hitHeight);

            logAttackEntityOut(entity, entityId, attackEnum, hitHeight, channel, packetBuffer);
            channel.writeAndFlush(packetBuffer);
            packetBuffer = null;
            return true;
        } catch (Throwable t) {
            warnMappingSkip("attackEntity", String.valueOf(t.getMessage()));
            return false;
        } finally {
            if (packetBuffer != null) {
                packetBuffer.release();
            }
        }
    }

    private static void logAttackEntityOut(Object entity, int entityId, Enum<?> actionEnum, float hitHeight, Channel channel, ByteBuf packetBuffer) {
        try {
            StringBuilder out = new StringBuilder();
            out.append("\n========== ATTACK ENTITY OUT ==========\n");
            out.append("source=MinecraftMapper.attackEntity\n");
            out.append("rawByteBuf=true\n");
            out.append("thread=").append(Thread.currentThread().getName()).append('\n');
            out.append("packetId=0x02\n");
            out.append("entityId=").append(entityId).append('\n');
            out.append("actionId=").append(actionEnum != null ? actionEnum.ordinal() : -1).append(" (ATTACK)\n");
            out.append("actionName=").append(actionEnum != null ? actionEnum.name() : "null").append('\n');
            out.append("hitHeight=").append(hitHeight).append('\n');
            out.append("bufferReaderIndex=").append(packetBuffer != null ? packetBuffer.readerIndex() : -1).append('\n');
            out.append("bufferWriterIndex=").append(packetBuffer != null ? packetBuffer.writerIndex() : -1).append('\n');
            out.append("bufferReadableBytes=").append(packetBuffer != null ? packetBuffer.readableBytes() : -1).append('\n');
            out.append("bufferCapacity=").append(packetBuffer != null ? packetBuffer.capacity() : -1).append('\n');
            out.append("packetBytes=").append(byteBufToHex(packetBuffer)).append('\n');
            out.append("targetClass=").append(entity != null ? entity.getClass().getName() : "null").append('\n');
            out.append("targetName=").append(safeEntityName(entity)).append('\n');
            out.append("targetHash=").append(entity != null ? System.identityHashCode(entity) : 0).append('\n');
            out.append("targetToString=").append(safeToString(entity)).append('\n');
            out.append("targetPos=").append(describeEntityPosition(entity)).append('\n');
            out.append("targetFields=").append(describeObjectFields(entity, 64)).append('\n');

            Object player = getThePlayer();
            out.append("playerClass=").append(player != null ? player.getClass().getName() : "null").append('\n');
            out.append("playerName=").append(safeEntityName(player)).append('\n');
            out.append("playerPos=").append(describeEntityPosition(player)).append('\n');
            out.append("distance=").append(describeDistance(player, entity)).append('\n');

            if (channel != null) {
                out.append("channelClass=").append(channel.getClass().getName()).append('\n');
                out.append("channelActive=").append(channel.isActive()).append('\n');
                out.append("channelOpen=").append(channel.isOpen()).append('\n');
                out.append("channelWritable=").append(channel.isWritable()).append('\n');
                out.append("channelLocal=").append(String.valueOf(channel.localAddress())).append('\n');
                out.append("channelRemote=").append(String.valueOf(channel.remoteAddress())).append('\n');
            } else {
                out.append("channel=null\n");
            }

            out.append("=======================================\n");
            System.out.println(out.toString());
        } catch (Throwable t) {
            System.out.println("[ATTACK ENTITY OUT] log failed: " + String.valueOf(t.getMessage()));
        }
    }

    private static String byteBufToHex(ByteBuf buffer) {
        if (buffer == null) {
            return "null";
        }

        StringBuilder hex = new StringBuilder();
        int readerIndex = buffer.readerIndex();
        int writerIndex = buffer.writerIndex();
        for (int i = readerIndex; i < writerIndex; i++) {
            if (hex.length() > 0) {
                hex.append(' ');
            }
            int value = buffer.getUnsignedByte(i);
            if (value < 0x10) {
                hex.append('0');
            }
            hex.append(Integer.toHexString(value).toUpperCase(Locale.ROOT));
        }
        return hex.toString();
    }

    private static String safeEntityName(Object entity) {
        if (entity == null) {
            return "null";
        }
        try {
            return getName(entity);
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static String safeToString(Object value) {
        try {
            return String.valueOf(value);
        } catch (Throwable ignored) {
            return "<toString failed>";
        }
    }

    private static String describeEntityPosition(Object entity) {
        if (entity == null) {
            return "null";
        }

        Double x = readMappedDouble(entity, "Entity.posX");
        Double y = readMappedDouble(entity, "Entity.posY");
        Double z = readMappedDouble(entity, "Entity.posZ");
        if (x == null || y == null || z == null) {
            return "unknown";
        }
        return "x=" + x + ", y=" + y + ", z=" + z;
    }

    private static String describeObjectFields(Object value, int maxFields) {
        if (value == null) {
            return "null";
        }

        StringBuilder fieldsOut = new StringBuilder("[");
        int count = 0;
        Class<?> current = value.getClass();
        while (current != null && current != Object.class && count < maxFields) {
            Field[] fields = current.getDeclaredFields();
            for (Field field : fields) {
                if (count >= maxFields) {
                    break;
                }
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }

                if (count > 0) {
                    fieldsOut.append(", ");
                }

                fieldsOut.append(current.getSimpleName()).append('.').append(field.getName()).append('=');
                try {
                    field.setAccessible(true);
                    fieldsOut.append(shortValue(field.get(value)));
                } catch (Throwable t) {
                    fieldsOut.append("<").append(t.getClass().getSimpleName()).append('>');
                }
                count++;
            }
            current = current.getSuperclass();
        }
        if (current != null && current != Object.class) {
            fieldsOut.append(", ...");
        }
        fieldsOut.append(']');
        return fieldsOut.toString();
    }

    private static String shortValue(Object value) {
        if (value == null) {
            return "null";
        }
        Class<?> valueClass = value.getClass();
        if (valueClass.isArray()) {
            return valueClass.getComponentType().getSimpleName() + "[" + Array.getLength(value) + "]";
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character || value instanceof Enum) {
            return String.valueOf(value);
        }
        if (value instanceof CharSequence) {
            return '"' + String.valueOf(value) + '"';
        }
        return valueClass.getName() + '@' + Integer.toHexString(System.identityHashCode(value));
    }

    private static String describeDistance(Object from, Object to) {
        Double fromX = readMappedDouble(from, "Entity.posX");
        Double fromY = readMappedDouble(from, "Entity.posY");
        Double fromZ = readMappedDouble(from, "Entity.posZ");
        Double toX = readMappedDouble(to, "Entity.posX");
        Double toY = readMappedDouble(to, "Entity.posY");
        Double toZ = readMappedDouble(to, "Entity.posZ");
        if (fromX == null || fromY == null || fromZ == null || toX == null || toY == null || toZ == null) {
            return "unknown";
        }

        double dx = fromX.doubleValue() - toX.doubleValue();
        double dy = fromY.doubleValue() - toY.doubleValue();
        double dz = fromZ.doubleValue() - toZ.doubleValue();
        return String.valueOf(Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    private static Double readMappedDouble(Object target, String mappingName) {
        if (target == null) {
            return null;
        }

        try {
            Field field = getField(mappingName);
            if (field == null) {
                return null;
            }
            field.setAccessible(true);
            Object value = field.get(target);
            return value instanceof Number ? Double.valueOf(((Number) value).doubleValue()) : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int getEntityIdForAttackPacket(Object entity) throws Exception {
        // 1) Önce field'dan dene (Entity.entityId — mappings.txt'te var)
        Field entityIdField = getField("Entity.entityId");
        if (entityIdField != null) {
            entityIdField.setAccessible(true);
            Object value = entityIdField.get(entity);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        }

        // 2) Metod varsa dene
        Method getEntityId = getMethod("Entity.getEntityId");
        if (getEntityId == null) {
            ensureC02EntityIdMappings();
            getEntityId = getMethod("Entity.getEntityId");
        }

        if (getEntityId != null) {
            getEntityId.setAccessible(true);
            Object value = getEntityId.invoke(entity);
            if (value instanceof Number) {
                return ((Number) value).intValue();
            }
        }

        // 3) hashCode fallback (son çare)
        int hc = System.identityHashCode(entity);
        if (hc > 0) {
            Logger.warn("[Mapper] Entity.getEntityId not mapped, using identityHashCode=" + hc);
            return hc;
        }

        throw new NoSuchMethodException("Entity.getEntityId");
    }

    private static void ensureC02EntityIdMappings() {
        if (getMethod("Entity.getEntityId") != null) {
            return;
        }

        try {
            Class<?> c02Class = get("C02PacketUseEntity");
            Class<?> entityClass = get("Entity");
            if (c02Class == null || entityClass == null) {
                return;
            }

            byte[] bytes = getClassBytes(c02Class);
            if (bytes == null || bytes.length == 0) {
                return;
            }

            ClassNode classNode = new ClassNode();
            new ClassReader(bytes).accept(classNode, 0);

            String c02Owner = org.objectweb.asm.Type.getInternalName(c02Class);
            String entityOwner = org.objectweb.asm.Type.getInternalName(entityClass);

            for (MethodNode methodNode : classNode.methods) {
                if (!"<init>".equals(methodNode.name)) {
                    continue;
                }

                MethodInsnNode getEntityIdCall = null;
                FieldInsnNode entityIdField = null;

                for (AbstractInsnNode insn : methodNode.instructions.toArray()) {
                    if (insn instanceof MethodInsnNode) {
                        MethodInsnNode methodInsn = (MethodInsnNode) insn;
                        if (entityOwner.equals(methodInsn.owner) && "()I".equals(methodInsn.desc)) {
                            getEntityIdCall = methodInsn;
                        }
                    } else if (insn instanceof FieldInsnNode) {
                        FieldInsnNode fieldInsn = (FieldInsnNode) insn;
                        if (getEntityIdCall != null
                                && c02Owner.equals(fieldInsn.owner)
                                && "I".equals(fieldInsn.desc)
                                && fieldInsn.getOpcode() == PUTFIELD) {
                            entityIdField = fieldInsn;
                            break;
                        }
                    }
                }

                if (getEntityIdCall == null) {
                    continue;
                }

                Method reflectedGetEntityId = entityClass.getDeclaredMethod(getEntityIdCall.name);
                reflectedGetEntityId.setAccessible(true);
                putMethod("Entity.getEntityId", reflectedGetEntityId);

                if (entityIdField != null) {
                    Field reflectedEntityIdField = c02Class.getDeclaredField(entityIdField.name);
                    reflectedEntityIdField.setAccessible(true);
                    putField("C02PacketUseEntity.entityId", reflectedEntityIdField);
                }
                return;
            }
        } catch (Throwable ignored) {
        }
    }

    public static Channel getNetworkChannel() throws Exception {
        Object netHandler = getNetHandlerPlayClient();
        if (netHandler == null) {
            return null;
        }

        Object networkManager = getNetworkManager(netHandler);
        if (networkManager == null) {
            return null;
        }

        Field channelField = getField("NetworkManager.channel");
        if (channelField == null) {
            channelField = getFieldByType(networkManager.getClass(), Channel.class);
        }
        if (channelField == null) {
            return null;
        }

        channelField.setAccessible(true);
        Object channel = channelField.get(networkManager);
        return channel instanceof Channel ? (Channel) channel : null;
    }

    private static Object getNetHandlerPlayClient() throws Exception {
        Object player = getThePlayer();
        if (player == null) {
            return null;
        }

        Field sendQueue = getField("EntityPlayerSP.sendQueue");
        if (sendQueue == null) {
            sendQueue = getFieldByType(player.getClass(), get("NetHandlerPlayClient"));
        }
        if (sendQueue == null) {
            return null;
        }

        sendQueue.setAccessible(true);
        return sendQueue.get(player);
    }

    private static Object getNetworkManager(Object netHandler) throws Exception {
        Field networkManagerField = getField("NetHandlerPlayClient.networkManager");
        if (networkManagerField == null) {
            networkManagerField = getFieldByType(netHandler.getClass(), get("NetworkManager"));
        }
        if (networkManagerField == null) {
            return null;
        }

        networkManagerField.setAccessible(true);
        return networkManagerField.get(netHandler);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static Enum<?> getAttackActionEnum(Class<?> actionClass) throws Exception {
        try {
            return Enum.valueOf((Class) actionClass, "ATTACK");
        } catch (IllegalArgumentException valueOfFailed) {
            Field attackField;
            try {
                attackField = actionClass.getField("ATTACK");
            } catch (NoSuchFieldException ignored) {
                attackField = actionClass.getDeclaredField("ATTACK");
            }
            attackField.setAccessible(true);
            Object value = attackField.get(null);
            return value instanceof Enum ? (Enum<?>) value : null;
        }
    }

    private static int getC02AttackActionId() {
        try {
            Class<?> actionClass = get("C02PacketUseEntityAction");
            if (actionClass == null) {
                return 1;
            }

            Field attackField;
            try {
                attackField = actionClass.getField("ATTACK");
            } catch (NoSuchFieldException ignored) {
                attackField = actionClass.getDeclaredField("ATTACK");
            }
            attackField.setAccessible(true);

            Object attack = attackField.get(null);
            if (attack instanceof Enum) {
                return ((Enum<?>) attack).ordinal();
            }
        } catch (Throwable ignored) {
        }
        return 1;
    }

    private static void writeEnumValueToByteBuf(ByteBuf buffer, Enum<?> value) {
        writeVarInt(buffer, value.ordinal());
    }

    private static void writeVarInt(ByteBuf buffer, int value) {
        while ((value & -128) != 0) {
            buffer.writeByte(value & 127 | 128);
            value >>>= 7;
        }
        buffer.writeByte(value);
    }

    public static void addChatMessage(String msg) {
        Logger.info("[CHAT MOCKED] " + msg);
    }

    private static void warnMappingSkip(String section, String reason) {
        Logger.warn("[Mapper] Skipping " + section + ": " + reason);
    }

    private static boolean missingClass(String section, String mappingName, Class<?> clazz) {
        if (clazz == null) {
            warnMappingSkip(section, mappingName + " is not mapped");
            return true;
        }
        return false;
    }

    private interface MappingStep {
        void run() throws Throwable;
    }

    private static void runMappingStep(String name, MappingStep step) {
        try {
            step.run();
        } catch (Throwable t) {
            Logger.warn("[Mapper] Step failed: " + name + " -> " + String.valueOf(t.getMessage()));
        }
    }

    private static void mapEssentialClientMappings() {
        ensureMinecraftClass();
        ensureMinecraftInstanceMappings();
        ensureEntityPlayerSPClass();
        ensurePlayerAccessMappings();
        ensureChatComponentMappings();
        ensureFontRendererMappings();
        ensureGuiInGameMapping();

        Logger.info("Essential mappings: Minecraft=" + mappedClassName("Minecraft")
                + ", EntityPlayerSP=" + mappedClassName("EntityPlayerSP")
                + ", FontRenderer=" + mappedClassName("FontRenderer")
                + ", ChatComponentText=" + mappedClassName("ChatComponentText"));
    }

    private static String mappedClassName(String name) {
        Class<?> clazz = get(name);
        return clazz == null ? "missing" : clazz.getName();
    }

    private static void ensureMinecraftClass() {
        if (get("Minecraft") != null) {
            return;
        }

        for (Class<?> clazz : getClasses()) {
            try {
                if (containsMinecraftFields(clazz)) {
                    put("Minecraft", clazz);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        for (Class<?> clazz : getClasses()) {
            if (!isConcreteGameClass(clazz)) {
                continue;
            }
            if (hasSelfSingletonField(clazz) && hasFieldType(clazz, "java.net.Proxy")) {
                put("Minecraft", clazz);
                return;
            }
        }
    }

    private static void ensureMinecraftInstanceMappings() {
        Class<?> minecraftClass = get("Minecraft");
        if (minecraftClass == null) {
            return;
        }

        if (getField("Minecraft.theMinecraft") == null) {
            Field singleton = findStaticFieldByType(minecraftClass, minecraftClass);
            if (singleton != null) {
                putField("Minecraft.theMinecraft", singleton);
            }
        }

        if (getMethod("Minecraft.getInstance") == null) {
            Method getInstance = findStaticNoArgMethodByReturnType(minecraftClass, minecraftClass);
            if (getInstance != null) {
                putMethod("Minecraft.getInstance", getInstance);
            }
        }
    }

    private static void ensureEntityPlayerSPClass() {
        if (get("EntityPlayerSP") != null) {
            return;
        }

        Class<?> minecraftClass = get("Minecraft");
        if (minecraftClass == null) {
            return;
        }

        for (Class<?> clazz : getClasses()) {
            try {
                if (isEntityPlayerSP(clazz)) {
                    mapEntityPlayerHierarchy(clazz);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }

        for (Class<?> clazz : getClasses()) {
            if (!isConcreteGameClass(clazz)
                    || !hasProtectedFieldByType(clazz, minecraftClass)
                    || !isEntityPlayerSPHierarchy(clazz)) {
                continue;
            }

            mapEntityPlayerHierarchy(clazz);
            for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                Class<?>[] params = constructor.getParameterTypes();
                if (params.length == 4 && params[0] == minecraftClass) {
                    if (isGameRuntimeClass(params[1])) {
                        put("World", params[1]);
                    }
                    if (isGameRuntimeClass(params[2])) {
                        put("NetHandlerPlayClient", params[2]);
                    }
                    break;
                }
            }
            return;
        }
    }

    private static void mapEntityPlayerHierarchy(Class<?> entityPlayerSPClass) {
        if (entityPlayerSPClass == null) {
            return;
        }
        put("EntityPlayerSP", entityPlayerSPClass);

        Class<?> abstractClientPlayerClass = entityPlayerSPClass.getSuperclass();
        Class<?> entityPlayerClass = abstractClientPlayerClass == null ? null : abstractClientPlayerClass.getSuperclass();
        Class<?> entityLivingBaseClass = entityPlayerClass == null ? null : entityPlayerClass.getSuperclass();
        Class<?> entityClass = entityLivingBaseClass == null ? null : entityLivingBaseClass.getSuperclass();

        if (isGameRuntimeClass(abstractClientPlayerClass)) {
            put("AbstractClientPlayer", abstractClientPlayerClass);
        }
        if (isGameRuntimeClass(entityPlayerClass)) {
            put("EntityPlayer", entityPlayerClass);
        }
        if (isGameRuntimeClass(entityLivingBaseClass)) {
            put("EntityLivingBase", entityLivingBaseClass);
        }
        if (isGameRuntimeClass(entityClass)) {
            put("Entity", entityClass);
        }
    }

    private static void ensurePlayerAccessMappings() {
        Class<?> minecraftClass = get("Minecraft");
        Class<?> entityPlayerSPClass = get("EntityPlayerSP");
        if (minecraftClass == null || entityPlayerSPClass == null) {
            return;
        }

        if (getMethod("Minecraft.getThePlayer") == null) {
            Method getter = findNoArgMethodByReturnType(minecraftClass, entityPlayerSPClass);
            if (getter != null) {
                putMethod("Minecraft.getThePlayer", getter);
            }
        }

        if (getField("Minecraft.thePlayer") == null) {
            Field playerField = findFieldByType(minecraftClass, entityPlayerSPClass, false);
            if (playerField != null) {
                putField("Minecraft.thePlayer", playerField);
            }
        }
    }

    private static void ensureChatComponentMappings() {
        if (get("IChatComponent") == null) {
            for (Class<?> clazz : getClasses()) {
                try {
                    if (isIChatComponent(clazz)) {
                        put("IChatComponent", clazz);
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        if (get("IChatComponent") != null && get("ChatComponentStyle") == null) {
            for (Class<?> clazz : getClasses()) {
                try {
                    if (isChatComponentStyle(clazz)) {
                        put("ChatComponentStyle", clazz);
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        if (get("ChatComponentStyle") != null && get("ChatComponentText") == null) {
            for (Class<?> clazz : getClasses()) {
                try {
                    if (isChatComponentText(clazz)) {
                        put("ChatComponentText", clazz);
                        break;
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        Class<?> entityPlayerSPClass = get("EntityPlayerSP");
        Class<?> chatComponentClass = get("IChatComponent");
        if (entityPlayerSPClass != null
                && chatComponentClass != null
                && getMethod("EntityPlayerSP.addChatMessage") == null) {
            Method method = findSingleArgMethod(entityPlayerSPClass, chatComponentClass);
            if (method != null) {
                putMethod("EntityPlayerSP.addChatMessage", method);
            }
        }
    }

    private static void ensureFontRendererMappings() {
        Class<?> fontRendererClass = get("FontRenderer");
        if (fontRendererClass == null) {
            fontRendererClass = findFontRendererCandidate();
            if (fontRendererClass != null) {
                put("FontRenderer", fontRendererClass);
            }
        }

        if (fontRendererClass == null) {
            return;
        }

        if (getMethod("FontRenderer.drawString") == null) {
            Method drawString = findDrawStringMethod(fontRendererClass);
            if (drawString != null) {
                putMethod("FontRenderer.drawString", drawString);
            }
        }

        Class<?> minecraftClass = get("Minecraft");
        if (minecraftClass == null) {
            return;
        }

        if (getMethod("Minecraft.getFontRendererObj") == null) {
            Method getter = findNoArgMethodByReturnType(minecraftClass, fontRendererClass);
            if (getter != null) {
                putMethod("Minecraft.getFontRendererObj", getter);
            }
        }

        if (getField("Minecraft.fontRendererObj") == null) {
            Field field = findFieldByType(minecraftClass, fontRendererClass, false);
            if (field != null) {
                putField("Minecraft.fontRendererObj", field);
            }
        }
    }

    private static void ensureGuiInGameMapping() {
        if (get("GuiInGame") != null || get("Minecraft") == null) {
            return;
        }

        Class<?> minecraftClass = get("Minecraft");
        for (Class<?> clazz : getClasses()) {
            if (!isConcreteGameClass(clazz)) {
                continue;
            }

            try {
                boolean hasMapField = false;
                boolean hasMinecraftField = false;
                for (Field field : clazz.getDeclaredFields()) {
                    int mods = field.getModifiers();
                    if (Modifier.isStatic(mods)
                            && Modifier.isFinal(mods)
                            && Map.class.isAssignableFrom(field.getType())) {
                        hasMapField = true;
                    }
                    if (!Modifier.isStatic(mods)
                            && Modifier.isFinal(mods)
                            && field.getType() == minecraftClass) {
                        hasMinecraftField = true;
                    }
                }

                if (!hasMapField || !hasMinecraftField) {
                    continue;
                }

                Method renderMethod = null;
                for (Method method : clazz.getDeclaredMethods()) {
                    Class<?>[] params = method.getParameterTypes();
                    if (Modifier.isPublic(method.getModifiers())
                            && method.getReturnType() == void.class
                            && params.length == 2
                            && params[1] == float.class) {
                        renderMethod = method;
                        break;
                    }
                }

                if (renderMethod != null) {
                    put("ScaledResolution", renderMethod.getParameterTypes()[0]);
                    put("GuiInGame", clazz);
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean isConcreteGameClass(Class<?> clazz) {
        return isGameRuntimeClass(clazz)
                && !clazz.isInterface()
                && !clazz.isEnum()
                && !clazz.isAnnotation()
                && !Modifier.isAbstract(clazz.getModifiers());
    }

    private static boolean hasSelfSingletonField(Class<?> clazz) {
        for (Field field : clazz.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (field.getType() == clazz && Modifier.isStatic(mods)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasFieldType(Class<?> clazz, String fieldTypeName) {
        for (Field field : clazz.getDeclaredFields()) {
            if (fieldTypeName.equals(field.getType().getName())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasProtectedFieldByType(Class<?> clazz, Class<?> type) {
        for (Field field : clazz.getDeclaredFields()) {
            if (Modifier.isProtected(field.getModifiers()) && field.getType() == type) {
                return true;
            }
        }
        return false;
    }

    private static Field findStaticFieldByType(Class<?> owner, Class<?> type) {
        Field fallback = null;
        for (Field field : owner.getDeclaredFields()) {
            int mods = field.getModifiers();
            if (field.getType() != type || !Modifier.isStatic(mods)) {
                continue;
            }
            if (Modifier.isPublic(mods)) {
                return field;
            }
            fallback = field;
        }
        return fallback;
    }

    private static Field findFieldByType(Class<?> owner, Class<?> type, boolean requireStatic) {
        for (Field field : owner.getDeclaredFields()) {
            if (field.getType() == type && Modifier.isStatic(field.getModifiers()) == requireStatic) {
                return field;
            }
        }
        return null;
    }

    private static Method findStaticNoArgMethodByReturnType(Class<?> owner, Class<?> returnType) {
        for (Method method : owner.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers())
                    && method.getParameterCount() == 0
                    && method.getReturnType() == returnType) {
                return method;
            }
        }
        return null;
    }

    private static Method findNoArgMethodByReturnType(Class<?> owner, Class<?> returnType) {
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getParameterCount() == 0 && method.getReturnType() == returnType) {
                return method;
            }
        }
        for (Method method : owner.getMethods()) {
            if (method.getParameterCount() == 0 && method.getReturnType() == returnType) {
                return method;
            }
        }
        return null;
    }

    private static Method findSingleArgMethod(Class<?> owner, Class<?> parameterType) {
        for (Method method : owner.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1 && params[0] == parameterType) {
                return method;
            }
        }
        for (Method method : owner.getMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1 && params[0] == parameterType) {
                return method;
            }
        }
        return null;
    }

    private static Class<?> findFontRendererCandidate() {
        Class<?> bestClass = null;
        int bestScore = -1;

        for (Class<?> clazz : getClasses()) {
            if (!isConcreteGameClass(clazz)) {
                continue;
            }

            int drawMethodCount = 0;
            for (Method method : clazz.getDeclaredMethods()) {
                if (isDrawStringMethod(method)) {
                    drawMethodCount++;
                }
            }

            if (drawMethodCount == 0) {
                continue;
            }

            int score = drawMethodCount * 10;
            if (clazz.getInterfaces().length > 0) {
                score += 2;
            }
            for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                Class<?>[] params = constructor.getParameterTypes();
                if (params.length >= 4 && params[params.length - 1] == boolean.class) {
                    score += 3;
                    break;
                }
            }

            if (score > bestScore) {
                bestScore = score;
                bestClass = clazz;
            }
        }

        return bestClass;
    }

    private static Method findDrawStringMethod(Class<?> fontRendererClass) {
        for (Method method : fontRendererClass.getDeclaredMethods()) {
            if (isDrawStringMethod(method)) {
                return method;
            }
        }
        return null;
    }

    private static boolean isDrawStringMethod(Method method) {
        if (Modifier.isStatic(method.getModifiers())) {
            return false;
        }
        Class<?>[] params = method.getParameterTypes();
        return params.length == 5
                && params[0] == String.class
                && params[1] == float.class
                && params[2] == float.class
                && params[3] == int.class
                && params[4] == boolean.class;
    }

    private static void runFullAutomapperPass() {
        runMappingStep("flag", () -> flag());
        runMappingStep("findMathHelper", () -> findMathHelper());
        runMappingStep("processPacketClass", () -> processPacketClass());
        runMappingStep("mapNetHandlerPlayClient", () -> mapNetHandlerPlayClient());
        runMappingStep("findS18", () -> findS18());
        runMappingStep("findOnGround(S18PacketEntityTeleport)", () -> findOnGround(getClassBytes(get("S18PacketEntityTeleport"))));
        runMappingStep("findS0BPacketAnimation", () -> findS0BPacketAnimation());
        runMappingStep("findS12PacketVelocity", () -> findS12PacketVelocity());
        runMappingStep("finds0cpacket", () -> finds0cpacket());
        runMappingStep("finds02", () -> finds02());
        runMappingStep("finds33packet", () -> finds33packet());
        runMappingStep("finds37andstatbase", () -> finds37andstatbase());
        runMappingStep("findS3FPacket", () -> findS3FPacket());
        runMappingStep("findTitlePacket", () -> findTitlePacket());
        runMappingStep("findWorldBorderPacket", () -> findWorldBorderPacket());
        runMappingStep("findC17Custom", () -> findC17Custom());
        runMappingStep("findResourcePackStatusPacket", () -> findResourcePackStatusPacket());
        runMappingStep("findClickWindowPacket", () -> findClickWindowPacket());
        runMappingStep("findC10PacketCreativeInventoryAction", () -> findC10PacketCreativeInventoryAction());
        runMappingStep("findUpdateSignPacket", () -> findUpdateSignPacket());
        runMappingStep("findClientStatusPacket", () -> findClientStatusPacket());
        runMappingStep("findInputPacket", () -> findInputPacket());
        runMappingStep("findHandshakePacket", () -> findHandshakePacket());
        runMappingStep("findEntityActionPacket", () -> findEntityActionPacket());
        runMappingStep("findUpdateScorePacket", () -> findUpdateScorePacket());
        runMappingStep("findc09packet", () -> findc09packet());
        runMappingStep("findC0DPacketCloseWindow", () -> findC0DPacketCloseWindow());
        runMappingStep("findPlayerPosLookPacket", () -> findPlayerPosLookPacket());
        runMappingStep("findS39PlayerCapabilities", () -> findS39PlayerCapabilities());
        runMappingStep("findC13PacketPlayerAbilities", () -> findC13PacketPlayerAbilities());
        runMappingStep("mapC13PacketPlayerAbilitiesFields", () -> mapC13PacketPlayerAbilitiesFields());
        runMappingStep("mapPlayerCapabilitiesFields", () -> mapPlayerCapabilitiesFields());
        runMappingStep("findPlayerCap", () -> findPlayerCap());

        runMappingStep("findInventoryPlayerClass", () -> findInventoryPlayerClass());
        runMappingStep("findCurrentItem", () -> putField("InventoryPlayer.currentItem", findCurrentItem(getClassBytes(get("InventoryPlayer")), get("InventoryPlayer"))));
        runMappingStep("findItemStackSize", () -> findItemStackSize());
        runMappingStep("findItemStackItemField", () -> findItemStackItemField());
        runMappingStep("findItemStackDisplayName", () -> findItemStackDisplayName());
        runMappingStep("findGetSubCompoundMethod", () -> findGetSubCompoundMethod());
        runMappingStep("findSetTagInfoMethod", () -> findSetTagInfoMethod());
        runMappingStep("processItemClass", () -> processItemClass());

        runMappingStep("findEntityList", () -> findEntityList());
        runMappingStep("findallmobs", () -> findallmobs());
        runMappingStep("findEntityFields", () -> findEntityFields());
        runMappingStep("findTicksExisted", () -> findTicksExisted());
        runMappingStep("findPrevRenderYawOffset", () -> findPrevRenderYawOffset());
        runMappingStep("mapGetHealth", () -> mapGetHealth());
        runMappingStep("mapMoveEntityWithHeadingAndParams", () -> mapMoveEntityWithHeadingAndParams());
        runMappingStep("mapPerformHurtAnimation", () -> mapPerformHurtAnimation());
        runMappingStep("findEntityBoat", () -> findEntityBoat());
        runMappingStep("findEntityThrowable", () -> findEntityThrowable());

        runMappingStep("findSkin", () -> findSkin());
        runMappingStep("findGameProfile", () -> findGameProfile());
        runMappingStep("analyzeNetworkPlayerInfoField", () -> {
            Field playerInfo = analyzeNetworkPlayerInfoField(get("AbstractClientPlayer"));
            putField("AbstractClientPlayer.playerInfo", playerInfo);
            if (playerInfo != null) {
                put("NetworkPlayerInfo", playerInfo.getType());
            }
        });
        runMappingStep("mapNetworkPlayerInfoAndPacketClasses", () -> mapNetworkPlayerInfoAndPacketClasses());

        runMappingStep("findAxisAligned", () -> findAxisAligned());
        runMappingStep("findRenderManager", () -> findRenderManager());
        runMappingStep("findRenderManagerFields", () -> findRenderManagerFields());
        runMappingStep("findRendererLivingEntity", () -> findRendererLivingEntity());
        runMappingStep("findItemRendererClass", () -> findItemRendererClass());
        runMappingStep("findEntityRenderer", () -> findEntityRenderer());
        runMappingStep("findEffectRenderer", () -> findEffectRenderer());
        runMappingStep("findGetRenderManager", () -> findGetRenderManager());
        runMappingStep("findRenderManagerHooks", () -> findRenderManagerHooks());
        runMappingStep("findTextureManagerClass", () -> findTextureManagerClass());
        runMappingStep("findModelManager", () -> findModelManager());
        runMappingStep("processModelBiped", () -> processModelBiped());
        runMappingStep("processModelBipedMethods", () -> processModelBipedMethods());
        runMappingStep("rectMethods", () -> rectMethods());
        runMappingStep("findTimerContainer", () -> findTimerContainer());
        runMappingStep("findTimerClass", () -> findTimerClass());
        runMappingStep("findglstatemanager", () -> findglstatemanager(20));

        runMappingStep("processGameSettingsClass", () -> processGameSettingsClass());
        runMappingStep("processKeyBindingClasses", () -> processKeyBindingClasses());
        runMappingStep("processMouseHelperClass", () -> processMouseHelperClass());
        runMappingStep("extractKeyBindings", () -> extractKeyBindings(get("GameSettings")));
        runMappingStep("findThirdPersonViewField", () -> findThirdPersonViewField());
        runMappingStep("findsendChatMessage", () -> findsendChatMessage());
        runMappingStep("findGuiInventory", () -> findGuiInventory());
        runMappingStep("findGetChunkFromChunkCoords", () -> findGetChunkFromChunkCoords());
        runMappingStep("findGetBlockInChunk", () -> findGetBlockInChunk());
        runMappingStep("findGetIdFromBlock", () -> findGetIdFromBlock());
        runMappingStep("worldacces", () -> worldacces());
        runMappingStep("findrenderGlobal", () -> findrenderGlobal());

        runMappingStep("processFloatContainerClasses", () -> processFloatContainerClasses());
        runMappingStep("processC02PacketUseEntityMethods", () -> processC02PacketUseEntityMethods());
        runMappingStep("findGetSlot", () -> putMethod("Container.getSlot", findGetSlot()));
        runMappingStep("findC08PacketPlayerBlockPlacement", () -> findC08PacketPlayerBlockPlacement());
        runMappingStep("findC06PacketPlayerPosLook", () -> findC06PacketPlayerPosLook(getClasses()));
        runMappingStep("findHeldItemChangePacketReflection", () -> findHeldItemChangePacketReflection());
        runMappingStep("mapC03XYZFromC06", () -> mapC03XYZFromC06());
        runMappingStep("mapIsBlockingASM", () -> mapIsBlockingASM());
        runMappingStep("mapSyncCurrentPlayItemASM", () -> mapSyncCurrentPlayItemASM());
        runMappingStep("findJumpOpcodeBased", () -> findJumpOpcodeBased());
        runMappingStep("analyzeAddMappingFirstParam", () -> analyzeAddMappingFirstParam());
    }

    // ==========================================
    // EXTRACTED METHODS FROM GL11.java
    // ==========================================

    // From Line 4841
    public static void findAxisAligned() {
        Class<?> icameraClass = get("ICamera");
        if (icameraClass == null) {
            System.out.println("nulaq");
            return;
        }
        for (Method method : icameraClass.getDeclaredMethods()) {
            Class<?>[] paramTypes = method.getParameterTypes();
            if (paramTypes.length == 1) {
                put("AxisAlignedBB", paramTypes[0]);

                putMethod("ICamera.getBoundingBox", method);
                break;
            }
        }
    }

    // From Line 4899
    public static void findRenderManager() {
        Class<?> textureManagerClass = get("TextureManager");
        Class<?> worldClass = get("World");
        Class<?> fontRendererClass = get("FontRenderer");
        Class<?> entityClass = get("Entity");

        if (textureManagerClass == null || worldClass == null || fontRendererClass == null || entityClass == null)
            return;

        for (Class<?> clazz : getClasses()) {
            Field[] fields = clazz.getDeclaredFields();

            boolean hasTextureManager = false;
            boolean hasWorld = false;
            boolean hasFontRenderer = false;
            boolean hasEntity = false;

            for (Field f : fields) {
                Class<?> type = f.getType();

                if (type.equals(textureManagerClass))
                    hasTextureManager = true;
                else if (type.equals(worldClass))
                    hasWorld = true;
                else if (type.equals(fontRendererClass))
                    hasFontRenderer = true;
                else if (type.equals(entityClass))
                    hasEntity = true;
            }

            if (hasTextureManager && hasWorld && hasFontRenderer && hasEntity) {
                put("RenderManager", clazz);
                for (Method method : clazz.getDeclaredMethods()) {
                    Class<?>[] paramTypes = method.getParameterTypes();

                    if (paramTypes.length == 5 &&
                            paramTypes[0].equals(entityClass) &&
                            paramTypes[1] != null &&
                            paramTypes[2] == double.class &&
                            paramTypes[3] == double.class &&
                            paramTypes[4] == double.class) {
                        put("ICamera", paramTypes[1]);

                        putMethod("RenderManager.shouldRender", method);
                        break;
                    }
                }
                for (Method method : clazz.getDeclaredMethods()) {
                    Class<?>[] paramTypes = method.getParameterTypes();

                    if (paramTypes.length == 3 &&
                            paramTypes[0].equals(entityClass) &&
                            paramTypes[1] == float.class &&
                            paramTypes[2] == boolean.class) {

                        putMethod("RenderManager.renderEntityStatic", method);
                        break;
                    }
                }
                Field mapField = null;
                Class<?> renderPlayerClass = null;
                for (Method method : clazz.getDeclaredMethods()) {
                    if (method.getReturnType().equals(Map.class)) {
                        java.lang.reflect.Type returnType = method.getGenericReturnType();
                        if (returnType instanceof ParameterizedType) {
                            ParameterizedType pType = (ParameterizedType) returnType;
                            java.lang.reflect.Type[] typeArgs = pType.getActualTypeArguments();
                            if (typeArgs.length == 2 && typeArgs[0].equals(String.class)
                                    && typeArgs[1] instanceof Class) {
                                renderPlayerClass = (Class<?>) typeArgs[1];
                                putMethod("RenderManager.getEntityRenderer", method);
                                break;
                            }
                        }
                    }
                }
                Field entityRenderMapField = null;
                Class<?> renderClass = null;

                for (Field field : clazz.getDeclaredFields()) {
                    if (field.getType().equals(Map.class)) {
                        java.lang.reflect.Type fieldType = field.getGenericType();
                        if (fieldType instanceof ParameterizedType) {
                            ParameterizedType pType = (ParameterizedType) fieldType;
                            java.lang.reflect.Type[] typeArgs = pType.getActualTypeArguments();
                            if (typeArgs.length == 2 &&
                                    typeArgs[0] instanceof ParameterizedType) {

                                ParameterizedType firstArgType = (ParameterizedType) typeArgs[0];
                                if (firstArgType.getRawType().equals(Class.class)) {
                                    entityRenderMapField = field;
                                    field.setAccessible(true);
                                    if (typeArgs[1] instanceof Class) {
                                        renderClass = (Class<?>) typeArgs[1];
                                    } else if (typeArgs[1] instanceof ParameterizedType) {
                                        ParameterizedType renderType = (ParameterizedType) typeArgs[1];
                                        renderClass = (Class<?>) renderType.getRawType();
                                    }

                                    if (renderClass != null) {
                                        put("Render", renderClass);

                                    }

                                    putField("RenderManager.entityRenderMap", field);

                                    break;
                                }
                            }
                        }
                        mapField = field;
                        field.setAccessible(true);
                        if (fieldType instanceof ParameterizedType) {
                            ParameterizedType pType = (ParameterizedType) fieldType;
                            java.lang.reflect.Type[] typeArgs = pType.getActualTypeArguments();
                            if (typeArgs.length == 2 && typeArgs[0].equals(String.class)
                                    && typeArgs[1] instanceof Class) {
                                if (renderPlayerClass == null) {
                                    renderPlayerClass = (Class<?>) typeArgs[1];
                                }
                            }
                        }

                        putField("RenderManager.playerRenderer", field);
                    }
                }
                if (renderPlayerClass == null && mapField != null) {
                    try {
                        Object renderManagerInstance = clazz.getDeclaredConstructor().newInstance();
                        Map<?, ?> map = (Map<?, ?>) mapField.get(renderManagerInstance);
                        if (map != null && !map.isEmpty()) {
                            Object firstValue = map.values().iterator().next();
                            if (firstValue != null) {
                                renderPlayerClass = firstValue.getClass();
                            }
                        }
                    } catch (Exception e) {

                    }
                }
                if (renderPlayerClass != null) {
                    put("RenderPlayer", renderPlayerClass);
                    findRenderPlayerMainModelOpcodes(renderPlayerClass);

                }

                findGetRenderManager();
                try {
                    findRenderManagerHooks();
                } catch (IOException e) {
                    e.printStackTrace();
                }

                break;
            }
        }
    }

    // From Line 8121
    public static void findPlayerCap() {
        Vector<Class<?>> classes = new Vector<>(getClasses());

        for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface() || candidate.isAnnotation()) {
                continue;
            }
            Constructor<?>[] constructors = candidate.getDeclaredConstructors();
            if (constructors.length != 1) {
                continue;
            }

            Constructor<?> ctor = constructors[0];
            Class<?>[] params = ctor.getParameterTypes();
            if (!(params.length == 2 && params[0] == boolean.class && params[1] == String.class)) {
                continue;
            }
            boolean hasThreadLocalRandomField = false;
            int threadLocalRandomFieldCount = 0;

            for (Field field : candidate.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (!java.lang.reflect.Modifier.isPrivate(modifiers) ||
                        !java.lang.reflect.Modifier.isFinal(modifiers) ||
                        field.getType() != int.class) {
                    continue;
                }
                String fieldName = field.getName();
                if (fieldName.length() == 1) {
                    threadLocalRandomFieldCount++;
                    hasThreadLocalRandomField = true;
                }
            }
            if (hasThreadLocalRandomField && threadLocalRandomFieldCount >= 2) {
                put("CapabilitiesContainer", candidate);
                return;
            }
        }

    }

    // From Line 8162
    public static void findEntityList() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> entityClass = get("Entity");
        Class<?> worldClass = get("World");
        Class<?> nbtTagCompoundClass = get("NBTTagCompound");
        if (entityClass == null || worldClass == null)
            return;

        for (Class<?> candidate : classes) {
            if (!Modifier.isPublic(candidate.getModifiers()) || candidate.isInterface() ||
                    candidate.isEnum() || candidate.isAnonymousClass() || candidate.getEnclosingClass() != null) {
                continue;
            }

            Method addMappingMethod = null;
            Method addMapping1Method = null;
            int matchingMethods = 0;

            for (Method method : candidate.getDeclaredMethods()) {
                Class<?>[] paramTypes = method.getParameterTypes();
                java.lang.reflect.Type[] genericParamTypes = method.getGenericParameterTypes();

                if (paramTypes.length == 3 &&
                        paramTypes[1] == String.class &&
                        paramTypes[2] == int.class &&
                        isEntityClassType(genericParamTypes[0], entityClass)) {
                    addMappingMethod = method;
                    matchingMethods++;
                } else if (paramTypes.length == 5 &&
                        paramTypes[1] == String.class &&
                        paramTypes[2] == int.class &&
                        paramTypes[3] == int.class &&
                        paramTypes[4] == int.class &&
                        isEntityClassType(genericParamTypes[0], entityClass)) {
                    addMapping1Method = method;
                    matchingMethods++;
                } else if (paramTypes.length == 2 &&
                        paramTypes[0] == String.class &&
                        paramTypes[1] == worldClass) {
                    matchingMethods++;
                } else if (nbtTagCompoundClass != null &&
                        paramTypes.length == 2 &&
                        paramTypes[0] == nbtTagCompoundClass &&
                        paramTypes[1] == worldClass) {
                    matchingMethods++;
                }
            }

            if (matchingMethods >= 3 && addMappingMethod != null) {
                put("EntityList", candidate);
                putMethod("EntityList.addMapping", addMappingMethod);

                if (addMapping1Method != null) {
                    putMethod("EntityList.addMapping1", addMapping1Method);
                }

                return;
            }
        }
    }

    // From Line 8223
    public static void analyzeAddMappingFirstParam() {
        try {
            Class<?> entityListClass = get("EntityList");
            if (entityListClass == null) {
                System.out.println("EntityList sınıfı bulunamadı!");
                return;
            }
            Method addMappingMethod = getMethod("EntityList.addMapping");
            if (addMappingMethod == null) {
                System.out.println("addMapping metodu bulunamadı!");
                return;
            }

            java.lang.reflect.Type[] genericParamTypes = addMappingMethod.getGenericParameterTypes();
            if (genericParamTypes.length > 0) {

                if (genericParamTypes[0] instanceof java.lang.reflect.ParameterizedType) {
                    java.lang.reflect.ParameterizedType pType = (java.lang.reflect.ParameterizedType) genericParamTypes[0];
                    java.lang.reflect.Type[] typeArgs = pType.getActualTypeArguments();
                    if (typeArgs.length > 0) {

                    }
                }
            }

            findAddMappingCalls(entityListClass);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // From Line 8703
    public static void mapSyncCurrentPlayItemASM() throws Exception {

        Class<?> pcmClass = get("PlayerControllerMP");
        byte[] bytes = getClassBytes(pcmClass);
        if (missingClassBytes("mapSyncCurrentPlayItemASM", pcmClass, bytes))
            return;

        ClassReader cr = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        String foundName = null;
        String foundDesc = null;

        for (MethodNode mn : cn.methods) {

            if (!mn.desc.equals("()V"))
                continue;
            if (mn.instructions == null || mn.instructions.size() == 0)
                continue;

            int intCompareCount = 0;
            int newCount = 0;
            int putFieldCount = 0;
            int getFieldCount = 0;
            boolean hasIntCtor = false;

            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {

                int op = insn.getOpcode();

                if (op == Opcodes.IF_ICMPNE || op == Opcodes.IF_ICMPEQ) {
                    intCompareCount++;
                }

                if (op == Opcodes.NEW) {
                    newCount++;
                }

                if (op == Opcodes.PUTFIELD) {
                    putFieldCount++;
                }

                if (op == Opcodes.GETFIELD) {
                    getFieldCount++;
                }

                if (op == Opcodes.INVOKESPECIAL) {
                    MethodInsnNode min = (MethodInsnNode) insn;
                    if (min.name.equals("<init>") && min.desc.equals("(I)V")) {
                        hasIntCtor = true;
                    }
                }
            }

            if (intCompareCount >= 1
                    && newCount >= 1
                    && hasIntCtor
                    && getFieldCount >= 1
                    && putFieldCount >= 1) {

                foundName = mn.name;
                foundDesc = mn.desc;
                break;
            }
        }

        if (foundName == null) {
            throw new RuntimeException("syncCurrentPlayItem ASM not found");
        }

        for (Method m : pcmClass.getDeclaredMethods()) {
            if (m.getName().equals(foundName)
                    && org.objectweb.asm.Type.getMethodDescriptor(m).equals(foundDesc)) {

                m.setAccessible(true);
                putMethod("PlayerControllerMP.syncCurrentPlayItem", m);

                System.out.println("[ASM] syncCurrentPlayItem FOUND -> " + m);
                return;
            }
        }

        throw new RuntimeException("Reflection match failed");
    }

    // From Line 10600
    private static Method findMethod(Class<?> clazz, int modifiers, Class<?> returnType, Class<?>... paramTypes) {
        if (clazz == null)
            return null;
        Method method = Arrays.stream(clazz.getDeclaredMethods())
                .filter(m -> (m.getModifiers() & modifiers) == (modifiers
                        & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.STATIC)) &&
                        m.getReturnType() == returnType &&
                        Arrays.equals(m.getParameterTypes(), paramTypes))
                .findFirst()
                .orElse(null);
        return method;
    }

    // From Line 10613
    public static void rectMethods() {
        Class<?> guiScreenClass = get("GuiScreen");
        if (guiScreenClass != null) {
            Class<?> guiClass = guiScreenClass.getSuperclass();
            if (guiClass != null) {
                put("Gui", guiClass);
            }
        }

        Class<?> Gui = get("Gui");
        if (Gui == null && guiScreenClass != null)
            Gui = guiScreenClass.getSuperclass();
        if (Gui == null) {
            return;
        }

        Method drawRect = findMethod(Gui, Modifier.PUBLIC | Modifier.STATIC, void.class,
                int.class, int.class, int.class, int.class, int.class);
        putMethod("Gui.drawRect", drawRect);

        Method drawModalRectWithCustomSizedTexture = findMethod(Gui, Modifier.PUBLIC | Modifier.STATIC, void.class,
                int.class, int.class, float.class, float.class, int.class, int.class, float.class, float.class);
        putMethod("Gui.drawModalRectWithCustomSizedTexture", drawModalRectWithCustomSizedTexture);

        Method drawScaledCustomSizeModalRect = findMethod(Gui, Modifier.PUBLIC | Modifier.STATIC, void.class,
                float.class, float.class, float.class, float.class, float.class,
                float.class, float.class, float.class, float.class, float.class);
        putMethod("Gui.drawScaledCustomSizeModalRect", drawScaledCustomSizeModalRect);

    }

    // From Line 10644
    public static void findSkin() {
        if (get("AbstractClientPlayer") == null || get("ResourceLocation") == null) {
            warnMappingSkip("findSkin", "AbstractClientPlayer or ResourceLocation is not mapped");
            return;
        }

        for (Object ent : getPlayerEntitiesInWorld()) {
            String name = getName(ent);
            if (name != null && name.contains("[CR]")) {
                Class<?> abst = get("AbstractClientPlayer");
                Class<?> res = get("ResourceLocation");
                if (abst != null && res != null) {
                    for (Method m : abst.getMethods()) {
                        if (!Modifier.isStatic(m.getModifiers()) && m.getReturnType().equals(res)) {
                            try {
                                Object head = m.invoke(ent);
                                if (head == null)
                                    continue;
                                putMethod("AbstractClientPlayer.getLocationSkin", m);
                            } catch (Exception e) {
                                continue;
                            }
                        }
                    }
                }
            }
        }
    }

    // From Line 12100
    public static void mapC03XYZFromC06() {
        try {
            Class<?> c06 = get("C06PacketPlayerPosLook");
            if (c06 == null)
                return;

            Class<?> c03 = c06.getSuperclass();
            if (c03 == null)
                return;

            byte[] bytes = getClassBytes(c06);
            if (missingClassBytes("mapC03XYZFromC06", c06, bytes))
                return;
            ClassReader cr = new ClassReader(bytes);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);
            MethodNode ctor = null;
            for (MethodNode mn : cn.methods) {
                if (mn.name.equals("<init>") &&
                        mn.desc.equals("(DDDFFZ)V")) {
                    ctor = mn;
                    break;
                }
            }
            if (ctor == null)
                return;

            List<FieldInsnNode> doublePuts = new ArrayList<>();

            for (AbstractInsnNode insn : ctor.instructions) {
                if (insn.getOpcode() == PUTFIELD) {
                    FieldInsnNode fin = (FieldInsnNode) insn;
                    if (fin.owner.equals(cn.name) && fin.desc.equals("D")) {
                        doublePuts.add(fin);
                    }
                }
            }

            if (doublePuts.size() < 3)
                return;
            FieldInsnNode fx = doublePuts.get(0);
            FieldInsnNode fy = doublePuts.get(1);
            FieldInsnNode fz = doublePuts.get(2);

            putField("C03PacketPlayer.x", c03.getDeclaredField(fx.name));
            putField("C03PacketPlayer.y", c03.getDeclaredField(fy.name));
            putField("C03PacketPlayer.z", c03.getDeclaredField(fz.name));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // From Line 12672
    public static void findEntityRenderer() {
        List<Class<?>> loadedClasses = getClasses();

        for (Class<?> clazz : loadedClasses) {
            int ifjMethodCount = 0;
            Method targetMethod = null;

            for (Method method : clazz.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();

                if (params.length == 3 &&
                        params[0] == int.class &&
                        params[1] == float.class &&
                        params[2] == long.class &&
                        method.getReturnType() == void.class) {

                    ifjMethodCount++;
                    targetMethod = method;
                }
            }

            if (ifjMethodCount == 1) {
                put("EntityRenderer", clazz);
                putMethod("EntityRenderer.renderWorldPass", targetMethod);

                return;
            }
        }

    }

    // From Line 13118
    public static Field findInventoryContainer(byte[] classBytes) throws Exception {
        if (classBytes == null || classBytes.length == 0 || get("Container") == null || get("EntityPlayer") == null) {
            warnMappingSkip("findInventoryContainer", "EntityPlayer bytes or Container mapping is missing");
            return null;
        }

        ClassReader cr = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        Class<?> containerClass = get("Container");
        String containerDesc = org.objectweb.asm.Type.getDescriptor(containerClass);

        for (MethodNode m : cn.methods) {
            if (!m.name.equals("<init>"))
                continue;

            InsnList insns = m.instructions;

            for (int i = 0; i < insns.size(); i++) {
                AbstractInsnNode insn = insns.get(i);
                if (insn.getOpcode() == PUTFIELD) {
                    FieldInsnNode f = (FieldInsnNode) insn;

                    if (f.desc.equals(containerDesc)) {
                        return asmFieldToReflectionField(get("EntityPlayer"), f.name, f.desc);
                    }
                }
            }
        }
        return null;
    }

    // From Line 13324
    public static Class<?> findContainerClassFromEntityPlayer(Class<?> entityPlayerClass) {
        if (entityPlayerClass == null) {
            warnMappingSkip("findContainerClassFromEntityPlayer", "EntityPlayer is not mapped");
            return null;
        }

        Field[] fields = entityPlayerClass.getDeclaredFields();

        Map<Class<?>, List<Field>> typeToFields = new HashMap<>();

        for (Field field : fields) {
            Class<?> fieldType = field.getType();

            if (fieldType.getName().contains("java.") || fieldType.getName().contains("javax.")) {
                continue;
            }
            if (!fieldType.isPrimitive()) {
                typeToFields.computeIfAbsent(fieldType, k -> new ArrayList<>()).add(field);
            }
        }

        for (Map.Entry<Class<?>, List<Field>> entry : typeToFields.entrySet()) {
            List<Field> fieldList = entry.getValue();
            if (fieldList.size() == 2) {
                return entry.getKey();
            }
        }

        return null;

    }

    // From Line 13555
    private static Field getFieldOfType(Class<?> clazz, Class<?> type) {
        if (clazz == null || type == null) {
            return null;
        }
        for (Field f : clazz.getDeclaredFields()) {
            if (f.getType() == type)
                return f;
        }
        return null;
    }

    // From Line 13563
    private static Class<?> getProtectedMethodSingleParam(Class<?> clazz) {
        if (clazz == null) {
            return null;
        }
        for (Method m : clazz.getDeclaredMethods()) {
            if (Modifier.isProtected(m.getModifiers()) && m.getParameterCount() == 1) {
                return m.getParameterTypes()[0];
            }
        }
        return null;
    }

    // From Line 13627
    private static Field getListFieldOfType(Class<?> clazz, Class<?> type) {
        if (clazz == null || type == null) {
            return null;
        }
        for (Field f : clazz.getDeclaredFields()) {
            if (List.class.isAssignableFrom(f.getType())) {
                java.lang.reflect.Type genType = f.getGenericType();
                if (genType instanceof ParameterizedType) {
                    ParameterizedType pt = (ParameterizedType) genType;
                    java.lang.reflect.Type[] args = pt.getActualTypeArguments();
                    if (args.length == 1 && args[0] instanceof Class<?>) {
                        if (args[0] == type) {
                            f.setAccessible(true);
                            return f;
                        }
                    }
                }
            }
        }
        return null;
    }

    // From Line 13646
    private static Method findGetStackMethod(Class<?> slotClass) {
        if (slotClass == null) {
            return null;
        }
        for (Method m : slotClass.getDeclaredMethods()) {
            if (m.getParameterCount() == 0) {
                Class<?> returnType = m.getReturnType();
                if (returnType.isPrimitive() || returnType == String.class)
                    continue;
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    // From Line 13659
    public static Method findGetSlot() {
        try {
            Class<?> containerClass = get("Container");
            Class<?> slotClass = get("Slot");
            if (containerClass == null || slotClass == null) {
                warnMappingSkip("findGetSlot", "Container or Slot is not mapped");
                return null;
            }

            for (Method method : containerClass.getMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length != 1 || params[0] != int.class)
                    continue;

                if (method.getReturnType() != slotClass)
                    continue;

                if (!Modifier.isPublic(method.getModifiers()))
                    continue;

                return method;
            }
        } catch (Exception e) {
            warnMappingSkip("findGetSlot", e.getMessage());
        }
        return null;
    }

    // From Line 13714
    public static void findTextureManagerClass() {
        for (Class<?> clazz : getClasses()) {

            if (clazz.isInterface())
                continue;

            if (clazz.getInterfaces().length < 2)
                continue;

            if (clazz.getSuperclass() != Object.class)
                continue;

            Field[] fields = clazz.getDeclaredFields();

            boolean hasLogger = false;
            int finalPrivateMapCount = 0;
            boolean hasFinalPrivateList = false;

            for (Field f : fields) {
                int mods = f.getModifiers();
                Class<?> type = f.getType();

                if (Modifier.isPrivate(mods) && Modifier.isStatic(mods) && Modifier.isFinal(mods)
                        && type.getName().equals("org.apache.logging.log4j.Logger")) {
                    hasLogger = true;
                }

                if (Modifier.isPrivate(mods) && Modifier.isFinal(mods)
                        && Map.class.isAssignableFrom(type)) {
                    finalPrivateMapCount++;
                }

                if (Modifier.isPrivate(mods) && Modifier.isFinal(mods)
                        && List.class.isAssignableFrom(type)) {
                    hasFinalPrivateList = true;
                }
            }

            if (!(hasLogger && finalPrivateMapCount >= 2 && hasFinalPrivateList))
                continue;

            Method[] methods = clazz.getDeclaredMethods();
            int publicVoidCount = 0;
            boolean hasPublicBooleanMethod = false;

            for (Method m : methods) {
                if (Modifier.isPublic(m.getModifiers())) {
                    if (m.getReturnType() == void.class)
                        publicVoidCount++;
                    if (m.getReturnType() == boolean.class)
                        hasPublicBooleanMethod = true;
                }
            }

            if (publicVoidCount < 4 || !hasPublicBooleanMethod)
                continue;

            boolean hasMatchingConstructor = false;
            for (Constructor<?> c : clazz.getDeclaredConstructors()) {
                if (c.getParameterCount() == 1) {
                    Class<?> paramType = c.getParameterTypes()[0];
                    if (!paramType.isPrimitive()) {
                        hasMatchingConstructor = true;
                        break;
                    }
                }
            }

            if (!hasMatchingConstructor)
                continue;

            put("TextureManager", clazz);
            break;
        }
    }

    // From Line 18278
    public static void findSwingMethod(byte[] classBytes) throws IOException {
        Class<?> packetClass = get("C0APacketAnimation");
        if (packetClass == null) {
            return;
        }
        if (missingClassBytes("findSwingMethod", classBytes))
            return;

        String targetInternalName = org.objectweb.asm.Type.getInternalName(packetClass);

        ClassReader reader = new ClassReader(classBytes);
        ClassNode classNode = new ClassNode();
        reader.accept(classNode, 0);

        for (MethodNode method : classNode.methods) {
            for (AbstractInsnNode insn : method.instructions) {
                if (insn.getOpcode() == Opcodes.NEW && insn instanceof TypeInsnNode) {
                    TypeInsnNode typeInsn = (TypeInsnNode) insn;
                    if (typeInsn.desc.equals(targetInternalName)) {
                        Method methodObj = asmMethodToReflectionMethod(get("EntityPlayerSP"), method.name, method.desc);
                        putMethod("EntityPlayerSP.swingItem", methodObj);
                        return;
                    }
                }
            }
        }

    }

    // From Line 18926
    public static Field analyzeNetworkPlayerInfoField(Class<?> clazz) {
        for (Field field : clazz.getDeclaredFields()) {
            int mod = field.getModifiers();
            Object instance = getThePlayer();

            if (Modifier.isPrivate(mod)
                    && !Modifier.isStatic(mod)
                    && !Modifier.isFinal(mod)
                    && !field.getType().isPrimitive()
                    && field.getType() != String.class) {

                try {
                    field.setAccessible(true);
                    Object value = field.get(instance);

                    if (value != null) {
                        return field;
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        return null;
    }

    // From Line 19477
    public static void mapNetworkPlayerInfoAndPacketClasses() {
        try {
            Class<?> clazz = get("NetworkPlayerInfo");
            if (clazz == null)
                return;

            Constructor<?>[] constructors = clazz.getDeclaredConstructors();
            if (constructors.length != 2)
                return;

            Class<?> gameProfileClass = get("GameProfile");

            Constructor<?> targetConstructor = null;
            for (Constructor<?> c : constructors) {
                Class<?>[] params = c.getParameterTypes();
                if (params.length == 1 && !params[0].equals(gameProfileClass)) {
                    targetConstructor = c;
                    break;
                }
            }

            if (targetConstructor == null)
                return;

            Class<?> addPlayerDataClass = targetConstructor.getParameterTypes()[0];
            put("S38PacketPlayerListItemAddPlayerData", addPlayerDataClass);
            put("S38PacketPlayerListItem", targetConstructor.getDeclaringClass());

            Field responseTimeField = null;
            for (Field f : clazz.getDeclaredFields()) {
                if (Modifier.isPrivate(f.getModifiers()) && f.getType() == int.class) {
                    f.setAccessible(true);
                    Object defaultValue = null;
                    try {
                        defaultValue = f.get(clazz);
                    } catch (Exception ignored) {
                    }
                    if (defaultValue == null || !(defaultValue instanceof Integer)) {
                        responseTimeField = f;
                        break;
                    }
                }
            }

            if (responseTimeField != null) {
                putField("NetworkPlayerInfo.responseTime", responseTimeField);

                Method getter = null;
                byte[] bytes = getClassBytes(clazz);
                if (missingClassBytes("mapNetworkPlayerInfoAndPacketClasses", clazz, bytes))
                    return;
                ClassReader cr = new ClassReader(bytes);
                ClassNode cn = new ClassNode();
                cr.accept(cn, 0);

                for (MethodNode mn : cn.methods) {

                    if (mn.desc.equals("()I")) {
                        InsnList insns = mn.instructions;
                        if (insns == null)
                            continue;

                        for (AbstractInsnNode insn : insns) {
                            if (insn.getOpcode() == Opcodes.GETFIELD) {
                                FieldInsnNode fin = (FieldInsnNode) insn;
                                if (fin.name.equals(responseTimeField.getName()) && fin.desc.equals("I")) {

                                    getter = clazz.getDeclaredMethod(mn.name);
                                    getter.setAccessible(true);
                                    break;
                                }
                            }
                        }
                    }
                    if (getter != null)
                        break;
                }

                if (getter != null) {
                    putMethod("NetworkPlayerInfo.getResponseTime", getter);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // From Line 19561
    public static boolean isTargetTextureClass(Class<?> clazz) {
        if (!Modifier.isAbstract(clazz.getModifiers()))
            return false;
        if (clazz.getInterfaces().length == 0)
            return false;

        int booleanFieldCount = 0;
        boolean hasMinusOneIntField = false;

        for (Field f : clazz.getDeclaredFields()) {
            if (f.getType() == boolean.class) {
                booleanFieldCount++;
            }

            if (f.getType() == int.class) {
                f.setAccessible(true);
                try {
                    
                    if (Modifier.isStatic(f.getModifiers())) {
                        Object v = f.get(null);
                        if (v instanceof Integer && ((Integer) v) == -1) {
                            hasMinusOneIntField = true;
                        }
                    } else {
                        
                        hasMinusOneIntField = true;
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        if (booleanFieldCount < 4)
            return false;
        if (!hasMinusOneIntField)
            return false;

        int boolBoolVoidMethods = 0;

        for (Method m : clazz.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2 &&
                    p[0] == boolean.class &&
                    p[1] == boolean.class &&
                    m.getReturnType() == void.class) {
                boolBoolVoidMethods++;
            }
        }

        return boolBoolVoidMethods >= 2;
    }

    // From Line 19613
    public static Method findGetGlTextureId(Class<?> clazz) {
        try {
            byte[] bytes = getClassBytes(clazz);
            if (missingClassBytes("findGetGlTextureId", clazz, bytes))
                return null;

            ClassReader cr = new ClassReader(bytes);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);
            for (MethodNode mn : cn.methods) {
                if (!mn.desc.equals("()I"))
                    continue;
                boolean hasMinusOneCheck = false;
                boolean hasStaticIntCall = false;
                boolean hasPutField = false;
                String intFieldName = null;
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn.getOpcode() == Opcodes.GETFIELD) {
                        FieldInsnNode fin = (FieldInsnNode) insn;
                        if (fin.desc.equals("I")) {
                            intFieldName = fin.name;
                        }
                    }
                    if (insn.getOpcode() == Opcodes.ICONST_M1) {
                        hasMinusOneCheck = true;
                    }
                    if (insn.getOpcode() == Opcodes.INVOKESTATIC) {
                        MethodInsnNode min = (MethodInsnNode) insn;
                        if (min.desc.equals("()I")) {
                            hasStaticIntCall = true;
                        }
                    }
                    if (insn.getOpcode() == Opcodes.PUTFIELD) {
                        FieldInsnNode fin = (FieldInsnNode) insn;
                        if (fin.desc.equals("I") && fin.name.equals(intFieldName)) {
                            hasPutField = true;
                        }
                    }
                }
                if (hasMinusOneCheck && hasStaticIntCall && hasPutField) {
                    Method m = clazz.getDeclaredMethod(mn.name);
                    m.setAccessible(true);
                    return m;
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return null;
    }

    // From Line 19857
    public static void findHeldItemChangePacketReflection() {
        List<Class<?>> classes = getClasses();
        Class<?> targetInterface = get("INetHandlerPlayServer");

        if (targetInterface == null) {
            System.out.println("INetHandlerPlayServer interface'i bulunamadı");
            return;
        }

        for (Class<?> clazz : classes) {
            if (!targetInterface.isAssignableFrom(clazz))
                continue;

            Field publicIntField = null;
            for (Field field : clazz.getDeclaredFields()) {
                if (Modifier.isPublic(field.getModifiers()) && field.getType() == int.class) {
                    publicIntField = field;
                    break;
                }
            }
            if (publicIntField == null)
                continue;

            boolean hasDefaultConstructor = false;
            boolean hasIntConstructor = false;

            for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                Class<?>[] params = constructor.getParameterTypes();

                if (params.length == 0) {
                    hasDefaultConstructor = true;
                } else if (params.length == 1 && params[0] == int.class) {
                    hasIntConstructor = true;

                    try {
                        Object instance = constructor.newInstance(123);
                        int value = publicIntField.getInt(instance);
                        if (value != 123) {
                            hasIntConstructor = false;
                        }
                    } catch (Exception e) {
                        hasIntConstructor = false;
                    }
                }
            }

            if (hasDefaultConstructor && hasIntConstructor) {
                put("C09PacketHeldItemChange", clazz);

                break;
            }
        }
    }

    // From Line 20497
    public static void finds0cpacket() {
        Class<?> packetIfc = get("Packet");
        Class<?> handlerClass = get("INetHandlerPlayClient");
        Class<?> entityPlayerClass = get("EntityPlayer");

        if (packetIfc == null || handlerClass == null || entityPlayerClass == null) {
            return;
        }

        for (Class<?> cls : getClasses()) {
            if (cls.isInterface() || cls.isEnum() || cls.isAnonymousClass() || cls.isMemberClass()
                    || cls.isLocalClass())
                continue;

            boolean matchesGeneric = false;

            java.lang.reflect.Type superType = cls.getGenericSuperclass();
            if (superType instanceof ParameterizedType) {
                ParameterizedType pt = (ParameterizedType) superType;
                java.lang.reflect.Type raw = pt.getRawType();
                if (raw instanceof Class && raw.equals(packetIfc)) {
                    java.lang.reflect.Type[] args = pt.getActualTypeArguments();
                    if (args.length > 0 && args[0] instanceof Class && args[0].equals(handlerClass)) {
                        matchesGeneric = true;
                    }
                }
            }

            java.lang.reflect.Type[] interfaces = cls.getGenericInterfaces();
            for (java.lang.reflect.Type iface : interfaces) {
                if (iface instanceof ParameterizedType) {
                    ParameterizedType pt = (ParameterizedType) iface;
                    java.lang.reflect.Type raw = pt.getRawType();
                    if (raw instanceof Class && raw.equals(packetIfc)) {
                        java.lang.reflect.Type[] args = pt.getActualTypeArguments();
                        if (args.length > 0 && args[0] instanceof Class && args[0].equals(handlerClass)) {
                            matchesGeneric = true;
                            break;
                        }
                    }
                }
            }

            if (!matchesGeneric)
                continue;

            boolean hasNoArg = false;
            boolean hasEntityPlayerCtor = false;

            Constructor<?>[] ctors = cls.getDeclaredConstructors();
            for (Constructor<?> ctor : ctors) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 0)
                    hasNoArg = true;
                if (params.length == 1 && params[0].equals(entityPlayerClass))
                    hasEntityPlayerCtor = true;
            }

            if (hasNoArg && hasEntityPlayerCtor) {
                put("S0CPacketSpawnPlayer", cls);
                return;
            }
        }
    }

    // From Line 20562
    public static void findS39PlayerCapabilities() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> packetIfc = get("Packet");
        Class<?> iNetHandlerPlayServer = get("INetHandlerPlayClient");
        Class<?> floatContainer = get("FloatContainer");
        if (packetIfc == null || iNetHandlerPlayServer == null || floatContainer == null) {

            return;
        }

        for (Class<?> candidate : classes) {
            if (candidate.isAnonymousClass() || candidate.isMemberClass() ||
                    candidate.isLocalClass() || candidate.isSynthetic() ||
                    candidate.getName().contains("$") ||
                    candidate.getDeclaredAnnotations().length > 0) {
                continue;
            }

            if (candidate.isEnum() || candidate.isInterface() || !packetIfc.isAssignableFrom(candidate)) {
                continue;
            }

            boolean implementsCorrectGeneric = false;

            java.lang.reflect.Type[] genericInterfaces = candidate.getGenericInterfaces();
            for (java.lang.reflect.Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericInterface;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayServer)) {
                            implementsCorrectGeneric = true;
                            break;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                java.lang.reflect.Type genericSuperclass = candidate.getGenericSuperclass();
                if (genericSuperclass instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericSuperclass;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayServer)) {
                            implementsCorrectGeneric = true;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                continue;
            }

            boolean hasNoArgConstructor = false;
            boolean hasSingleParamConstructor = false;
            Class<?> singleParamType = null;
            int constructorCount = 0;

            for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
                constructorCount++;
                Class<?>[] params = ctor.getParameterTypes();

                if (params.length == 0) {
                    hasNoArgConstructor = true;
                }

                if (params.length == 1) {
                    hasSingleParamConstructor = true;
                    singleParamType = params[0];
                }
            }

            if (constructorCount != 2 || !hasNoArgConstructor || !hasSingleParamConstructor) {
                continue;
            }

            int floatContainerFieldCount = 0;
            int floatFieldCount = 0;
            int booleanFieldCount = 0;

            for (Field field : candidate.getDeclaredFields()) {
                Class<?> fieldType = field.getType();

                if (fieldType == floatContainer) {
                    floatContainerFieldCount++;
                } else if (fieldType == float.class) {
                    floatFieldCount++;
                } else if (fieldType == boolean.class) {
                    booleanFieldCount++;
                }
            }
            if (floatContainerFieldCount >= 2) {
                put("S39PacketPlayerAbilities", candidate);
                return;
            }
        }

    }

    // From Line 20669
    public static void mapC13PacketPlayerAbilitiesFields() {
        try {
            Class<?> c13Class = get("C13PacketPlayerAbilities");
            if (c13Class == null) {
                System.out.println("C13PacketPlayerAbilities bulunamadı.");
                return;
            }

            Map<Integer, String> methodOrder = new LinkedHashMap<>();
            methodOrder.put(0, "setInvulnerable");
            methodOrder.put(1, "setFlying");
            methodOrder.put(2, "setAllowFlying");
            methodOrder.put(3, "setCreativeMode");
            methodOrder.put(4, "setFlySpeed");
            methodOrder.put(5, "setWalkSpeed");

            byte[] bytes = getClassBytes(c13Class);
            if (missingClassBytes("mapC13PacketPlayerAbilitiesFields", c13Class, bytes))
                return;

            ClassReader cr = new ClassReader(bytes);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);

            MethodNode capabilitiesCtor = null;
            for (MethodNode mn : cn.methods) {
                if (mn.name.equals("<init>")) {
                    if (mn.desc.contains("L" + get("PlayerCapabilities").getName().replace('.', '/') + ";")) {
                        capabilitiesCtor = mn;
                        break;
                    }
                }
            }

            if (capabilitiesCtor == null) {
                System.out.println("PlayerCapabilities constructor bulunamadı.");
                return;
            }

            List<FieldInsnNode> fieldPuts = new ArrayList<>();
            List<MethodInsnNode> methodCalls = new ArrayList<>();

            for (AbstractInsnNode insn : capabilitiesCtor.instructions) {
                if (insn.getOpcode() == Opcodes.PUTFIELD) {
                    FieldInsnNode fin = (FieldInsnNode) insn;
                    if (fin.owner.equals(cn.name)) {
                        fieldPuts.add(fin);
                    }
                } else if (insn.getType() == AbstractInsnNode.METHOD_INSN) {
                    MethodInsnNode min = (MethodInsnNode) insn;
                    if (min.owner.equals(cn.name) && min.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                        methodCalls.add(min);
                    }
                }
            }

            if (!fieldPuts.isEmpty()) {
                try {
                    Field field = c13Class.getDeclaredField(fieldPuts.get(0).name);
                    field.setAccessible(true);
                    putField("C13PacketPlayerAbilities.flySpeed", field);
                    System.out.println("Mapped field: " + fieldPuts.get(0).name + " -> flySpeed");
                } catch (NoSuchFieldException e) {
                    System.out.println("Field bulunamadı: " + fieldPuts.get(0).name);
                }
            }

            if (methodCalls.size() >= 6) {
                String[] methodNames = new String[methodCalls.size()];

                for (int i = 0; i < methodCalls.size(); i++) {
                    methodNames[i] = methodCalls.get(i).name;
                }

                try {

                    Method method1 = c13Class.getDeclaredMethod(methodNames[0], boolean.class);
                    putMethod("C13PacketPlayerAbilities.setInvulnerable", method1);
                    System.out.println("Mapped method 0: " + methodNames[0] + " -> setInvulnerable");

                    Method method2 = c13Class.getDeclaredMethod(methodNames[1], boolean.class);
                    putMethod("C13PacketPlayerAbilities.setFlying", method2);
                    System.out.println("Mapped method 1: " + methodNames[1] + " -> setFlying");

                    Method method3 = c13Class.getDeclaredMethod(methodNames[2], boolean.class);
                    putMethod("C13PacketPlayerAbilities.setAllowFlying", method3);
                    System.out.println("Mapped method 2: " + methodNames[2] + " -> setAllowFlying");

                    Method method4 = c13Class.getDeclaredMethod(methodNames[3], boolean.class);
                    putMethod("C13PacketPlayerAbilities.setCreativeMode", method4);
                    System.out.println("Mapped method 3: " + methodNames[3] + " -> setCreativeMode");

                    Method method5 = c13Class.getDeclaredMethod(methodNames[4], float.class);
                    putMethod("C13PacketPlayerAbilities.setFlySpeed", method5);
                    System.out.println("Mapped method 4: " + methodNames[4] + " -> setFlySpeed");

                    Method method6 = c13Class.getDeclaredMethod(methodNames[5], float.class);
                    putMethod("C13PacketPlayerAbilities.setWalkSpeed", method6);
                    System.out.println("Mapped method 5: " + methodNames[5] + " -> setWalkSpeed");

                } catch (NoSuchMethodException e) {
                    System.out.println("Method bulunamadı: " + e.getMessage());
                    e.printStackTrace();
                }
            } else {
                System.out.println("Yeterli method call bulunamadı: " + methodCalls.size());

                System.out.println("\nBulunan methodlar:");
                for (int i = 0; i < methodCalls.size(); i++) {
                    System.out.println(i + ": " + methodCalls.get(i).name + " - " + methodCalls.get(i).desc);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // From Line 20784
    public static void mapPlayerCapabilitiesFields() {
        try {
            Class<?> capClass = get("PlayerCapabilities");
            if (capClass == null) {
                System.out.println("PlayerCapabilities bulunamadı.");
                return;
            }

            byte[] bytes = getClassBytes(capClass);
            if (missingClassBytes("mapPlayerCapabilitiesFields", capClass, bytes))
                return;

            ClassReader cr = new ClassReader(bytes);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);
            MethodNode ctor = null;
            for (MethodNode mn : cn.methods) {
                if (mn.name.equals("<init>")) {
                    ctor = mn;
                    break;
                }
            }
            if (ctor == null)
                return;

            List<FieldInsnNode> puts = new ArrayList<>();
            for (AbstractInsnNode insn : ctor.instructions) {
                if (insn.getOpcode() == PUTFIELD) {
                    FieldInsnNode fin = (FieldInsnNode) insn;
                    if (fin.owner.equals(cn.name)) {
                        puts.add(fin);
                    }
                }
            }

            if (puts.size() < 7) {
                System.out.println("Beklenen 7 PUTFIELD yok. Bulunan: " + puts.size());
                return;
            }

            FieldInsnNode f_disableDamage = puts.get(0);
            FieldInsnNode f_isFlying = puts.get(1);
            FieldInsnNode f_allowFlying = puts.get(2);
            FieldInsnNode f_isCreativeMode = puts.get(3);
            FieldInsnNode f_allowEdit = puts.get(4);
            FieldInsnNode f_flySpeed = puts.get(5);
            FieldInsnNode f_walkSpeed = puts.get(6);

            putField("PlayerCapabilities.disableDamage", capClass.getDeclaredField(f_disableDamage.name));
            putField("PlayerCapabilities.isFlying", capClass.getDeclaredField(f_isFlying.name));
            putField("PlayerCapabilities.allowFlying", capClass.getDeclaredField(f_allowFlying.name));
            putField("PlayerCapabilities.isCreativeMode", capClass.getDeclaredField(f_isCreativeMode.name));
            putField("PlayerCapabilities.allowEdit", capClass.getDeclaredField(f_allowEdit.name));
            putField("PlayerCapabilities.flySpeed", capClass.getDeclaredField(f_flySpeed.name));
            putField("PlayerCapabilities.walkSpeed", capClass.getDeclaredField(f_walkSpeed.name));

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // From Line 20927
    public static void findC13PacketPlayerAbilities() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> packetIfc = get("Packet");
        Class<?> iNetHandlerPlayServer = get("INetHandlerPlayServer");
        Class<?> floatContainer = get("FloatContainer");
        if (packetIfc == null || iNetHandlerPlayServer == null || floatContainer == null) {

            return;
        }

        for (Class<?> candidate : classes) {
            if (candidate.isAnonymousClass() || candidate.isMemberClass() ||
                    candidate.isLocalClass() || candidate.isSynthetic() ||
                    candidate.getName().contains("$") ||
                    candidate.getDeclaredAnnotations().length > 0) {
                continue;
            }

            if (candidate.isEnum() || candidate.isInterface() || !packetIfc.isAssignableFrom(candidate)) {
                continue;
            }

            boolean implementsCorrectGeneric = false;

            java.lang.reflect.Type[] genericInterfaces = candidate.getGenericInterfaces();
            for (java.lang.reflect.Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericInterface;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayServer)) {
                            implementsCorrectGeneric = true;
                            break;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                java.lang.reflect.Type genericSuperclass = candidate.getGenericSuperclass();
                if (genericSuperclass instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericSuperclass;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayServer)) {
                            implementsCorrectGeneric = true;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                continue;
            }

            boolean hasNoArgConstructor = false;
            boolean hasSingleParamConstructor = false;
            Class<?> singleParamType = null;
            int constructorCount = 0;

            for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
                constructorCount++;
                Class<?>[] params = ctor.getParameterTypes();

                if (params.length == 0) {
                    hasNoArgConstructor = true;
                }

                if (params.length == 1) {
                    hasSingleParamConstructor = true;
                    singleParamType = params[0];
                }
            }

            if (constructorCount != 2 || !hasNoArgConstructor || !hasSingleParamConstructor) {
                continue;
            }

            int floatContainerFieldCount = 0;
            int floatFieldCount = 0;
            int booleanFieldCount = 0;

            for (Field field : candidate.getDeclaredFields()) {
                Class<?> fieldType = field.getType();

                if (fieldType == floatContainer) {
                    floatContainerFieldCount++;
                } else if (fieldType == float.class) {
                    floatFieldCount++;
                } else if (fieldType == boolean.class) {
                    booleanFieldCount++;
                }
            }
            if (floatContainerFieldCount >= 2) {
                put("C13PacketPlayerAbilities", candidate);
                if (singleParamType != null) {
                    put("PlayerCapabilities", singleParamType);

                }
                return;
            }
        }

    }

    // From Line 21944
    public static void findGetChunkFromChunkCoords() {
        Class<?> worldClass = get("World");
        if (worldClass == null)
            return;

        for (Method method : worldClass.getDeclaredMethods()) {
            int mods = method.getModifiers();
            if (Modifier.isPublic(mods) && !Modifier.isStatic(mods)) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 2
                        && params[0] == int.class
                        && params[1] == int.class
                        && !method.getReturnType().isPrimitive()) {

                    put("Chunk", method.getReturnType());
                    putMethod("World.getChunkFromChunkCoords", method);
                    findBlockInChunk(method.getReturnType());

                    try {
                        Class<?> chunkClass = method.getReturnType();
                        byte[] classBytes = getClassBytes(chunkClass);
                        if (classBytes != null) {
                            ClassReader cr = new ClassReader(classBytes);
                            ClassNode cn = new ClassNode();
                            cr.accept(cn, 0);

                            for (MethodNode mn : cn.methods) {
                                if ((mn.access & Opcodes.ACC_PRIVATE) != 0 &&
                                        mn.desc.equals("(III)I")) {

                                    boolean hasShiftRight = false;
                                    boolean hasTryCatch = mn.tryCatchBlocks != null && !mn.tryCatchBlocks.isEmpty();

                                    for (AbstractInsnNode insn : mn.instructions.toArray()) {
                                        if (insn.getOpcode() == Opcodes.ISHR) {
                                            hasShiftRight = true;
                                            break;
                                        }
                                    }

                                    if (hasShiftRight && hasTryCatch) {
                                        try {
                                            Method m = chunkClass.getDeclaredMethod(
                                                    mn.name, int.class, int.class, int.class);
                                            m.setAccessible(true);
                                            putMethod("Chunk.getBlockMetadata", m);

                                        } catch (NoSuchMethodException e) {
                                            e.printStackTrace();
                                        }
                                        break;
                                    }
                                }
                            }
                        }
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }

                    break;
                }
            }
        }
    }

    // From Line 22498
    public static boolean isPacketBuffer(Class<?> clazz) {

        if (!ByteBuf.class.isAssignableFrom(clazz))
            return false;

        boolean hasValidConstructor = false;
        for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
            Class<?>[] params = constructor.getParameterTypes();
            if (params.length == 1 && ByteBuf.class.isAssignableFrom(params[0])) {
                hasValidConstructor = true;
                break;
            }
        }

        if (!hasValidConstructor)
            return false;

        for (Method method : clazz.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers()) &&
                    Modifier.isStatic(method.getModifiers()) &&
                    method.getReturnType() == int.class) {
                analyze(clazz);
                return true;
            }
        }

        return false;
    }

    // From Line 22527
    public static void analyze(Class<?> clazz) {
        Method writeFloatMethod = null;
        Method writeEnumValueMethod = null;
        Method writeVarIntMethod = null;

        for (Method method : clazz.getDeclaredMethods()) {
            method.setAccessible(true);

            if (method.getReturnType() == ByteBuf.class && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == float.class) {
                writeFloatMethod = method;
            }

            if (method.getParameterCount() == 1 && Enum.class.isAssignableFrom(method.getParameterTypes()[0])) {
                writeEnumValueMethod = method;
            }

            if (method.getReturnType() == void.class && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == int.class) {
                writeVarIntMethod = method;
            }
        }

        if (writeFloatMethod != null && writeEnumValueMethod != null && writeVarIntMethod != null) {
            putMethod("PacketBuffer.writeFloat", writeFloatMethod);
            putMethod("PacketBuffer.writeEnumValue", writeEnumValueMethod);
            putMethod("PacketBuffer.writeVarIntToBuffer", writeVarIntMethod);
        }
    }

    // From Line 22596
    public static void findC05PacketPlayerLook(List<Class<?>> classes) {
        Class<?> c03 = get("C03PacketPlayer");
        if (c03 == null) {
            System.out.println("C03PacketPlayer bulunamadı.");
            return;
        }

        for (Class<?> clazz : classes) {

            if (clazz.getSuperclass() != c03)
                continue;

            int constructorMatchCount = 0;

            for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                Class<?>[] params = constructor.getParameterTypes();

                if (params.length == 0) {
                    constructorMatchCount++;
                } else if (params.length == 3 &&
                        params[0] == float.class &&
                        params[1] == float.class &&
                        params[2] == boolean.class) {
                    constructorMatchCount++;
                }
            }

            if (constructorMatchCount == 2) {
                put("C05PacketPlayerLook", clazz);
                try {
                    detectC05Fields(getClassBytes(clazz), c03);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return;
            }
        }

        System.out.println("C05PacketPlayerLook bulunamadı.");
    }

    // From Line 22637
    public static void findC08PacketPlayerBlockPlacement() throws Exception {
        Class<?> itemStackClass = get("ItemStack");
        Class<?> blockPosClass = get("BlockPos");
        if (itemStackClass == null || blockPosClass == null)
            return;

        for (Class<?> clazz : getClasses()) {
            Constructor<?>[] constructors = clazz.getDeclaredConstructors();
            if (constructors.length != 3)
                continue;

            boolean hasNoParam = false;
            boolean hasOneParamItemStack = false;
            boolean hasSixParamMatching = false;

            for (Constructor<?> ctor : constructors) {
                Class<?>[] params = ctor.getParameterTypes();

                if (params.length == 0) {
                    hasNoParam = true;
                } else if (params.length == 1 && params[0].equals(itemStackClass)) {
                    hasOneParamItemStack = true;
                } else if (params.length == 6
                        && params[0].equals(blockPosClass)
                        && params[1] == int.class
                        && params[2].equals(itemStackClass)
                        && params[3] == float.class
                        && params[4] == float.class
                        && params[5] == float.class) {
                    hasSixParamMatching = true;
                }
            }

            if (hasNoParam && hasOneParamItemStack && hasSixParamMatching) {
                put("C08PacketPlayerBlockPlacement", clazz);
                break;
            }
        }
    }

    // From Line 22702
    public static void startMappings() {

        for (Class<?> clazz : getClasses()) {
            if (containsMinecraftFields(clazz)) {
                put("Minecraft", clazz);
                break;
            }
        }

        for (Class<?> clazz : getClasses()) {
            if (isPacketBuffer(clazz)) {
                put("PacketBuffer", clazz);
                break;
            }
        }

        if (get("Minecraft") != null) {
            for (Field field : get("Minecraft").getDeclaredFields()) {
                if (field.getType() == get("Minecraft") && Modifier.isPublic(field.getModifiers())
                        && Modifier.isStatic(field.getModifiers())) {
                    putField("Minecraft.theMinecraft", field);
                    break;
                }
            }
        }

        if (get("Minecraft") != null) {
            for (Class<?> clazz : getClasses()) {
                if (isEntityPlayerSP(clazz)) {
                    Class<?> entityPlayerSPClazz = clazz;
                    Class<?> abstractClientPlayerClazz = clazz.getSuperclass();
                    Class<?> entityPlayerClazz = abstractClientPlayerClazz.getSuperclass();
                    Class<?> entityLivingBaseClazz = entityPlayerClazz.getSuperclass();
                    Class<?> entityClazz = entityLivingBaseClazz.getSuperclass();

                    put("EntityPlayerSP", entityPlayerSPClazz);
                    for (Method m : entityPlayerSPClazz.getDeclaredMethods()) {
                        if (Modifier.isProtected(m.getModifiers()) &&
                                m.getReturnType() == boolean.class &&
                                m.getParameterCount() == 0) {

                            m.setAccessible(true);
                            putMethod("EntityPlayerSP.isCurrentViewEntity", m);
                            break;
                        }
                    }
                    put("AbstractClientPlayer", abstractClientPlayerClazz);

                    put("EntityPlayer", entityPlayerClazz);
                    put("EntityLivingBase", entityLivingBaseClazz);

                    put("Entity", entityClazz);
                    java.util.List<java.lang.reflect.Method> candidateMethods = Arrays
                            .stream(entityPlayerSPClazz.getDeclaredMethods())
                            .filter(m -> Modifier.isProtected(m.getModifiers())
                                    && m.getReturnType() == void.class
                                    && m.getParameterCount() == 1
                                    && !m.getParameterTypes()[0].isPrimitive())
                            .collect(java.util.stream.Collectors.toList());
                    if (!candidateMethods.isEmpty()) {
                        java.lang.reflect.Method method = candidateMethods.get(0);
                        Class<?> entityItemClass = method.getParameterTypes()[0];
                        put("EntityItem", entityItemClass);
                        putMethod("EntityPlayerSP.joinEntityItemWithWorld", method);

                        if (candidateMethods.size() > 1) {
                            for (int i = 1; i < candidateMethods.size(); i++) {
                                java.lang.reflect.Method m = candidateMethods.get(i);

                            }
                        }
                    }
                    break;
                }
            }
        }

        if (get("Minecraft") != null && get("EntityPlayerSP") != null) {
            for (Method method : get("Minecraft").getMethods()) {
                if (Modifier.isPublic(method.getModifiers()) && method.getReturnType() == get("EntityPlayerSP")
                        && method.getParameterCount() == 0) {
                    putMethod("Minecraft.getThePlayer", method);
                    break;
                }
            }
            for (Field field : get("Minecraft").getDeclaredFields()) {
                if (field.getType() == get("EntityPlayerSP")) {
                    putField("Minecraft.thePlayer", field);
                    break;
                }
            }
        }

        Class<?> entityClass = get("Entity");
        if (entityClass != null) {
            for (Method method : entityClass.getMethods()) {
                if (method.getReturnType() == void.class) {
                    Class<?>[] params = method.getParameterTypes();
                    if (params.length == 5 &&
                            params[0] == double.class && params[1] == double.class && params[2] == double.class &&
                            params[3] == float.class && params[4] == float.class) {
                        putMethod("Entity.setPositionAndRotation", method);
                        break;
                    }
                }
            }
        } else {
            warnMappingSkip("Entity.setPositionAndRotation", "Entity is not mapped");
        }

        if (entityClass != null) {
            Field[] fields4 = entityClass.getFields();
            for (Field field : fields4) {
                if (field.getType() == get("World") && Modifier.isPublic(field.getModifiers())) {
                    putField("EntityPlayerSP.worldObj", field);
                }
            }
        }

        if (entityClass != null) {
            putMethod("Entity.getDistanceToEntity",
                    Arrays.stream(getAllMethods(entityClass)).filter(method -> method.toString().contains("public float")
                            && method.getParameterCount() == 1 && method.getParameterTypes()[0].equals(entityClass))
                            .findFirst().orElse(null));
        }
        putField("EntityPlayerSP.sendQueue", getFieldByType(get("EntityPlayerSP"), get("NetHandlerPlayClient")));
        putField("World.playerEntities", findListUsingParam(get("World"), get("EntityPlayer")));
        put("C02PacketUseEntityAction", findClassUsingFieldNames("ATTACK", "INTERACT_AT"));
        Class<?> c02PacketUseEntityAction = get("C02PacketUseEntityAction");
        if (c02PacketUseEntityAction != null) {
            put("C02PacketUseEntity", findClassByName(getOuterClassName(c02PacketUseEntityAction.getName())));
        } else {
            warnMappingSkip("C02PacketUseEntity", "C02PacketUseEntityAction is not mapped");
        }

        Class<?> c02PacketUseEntity = get("C02PacketUseEntity");
        if (c02PacketUseEntity != null) {
            Class<?>[] interfaces = c02PacketUseEntity.getInterfaces();
            Class<?> firstInterface = interfaces.length > 0 ? interfaces[0] : null;
            put("Packet", firstInterface);
        } else {
            warnMappingSkip("Packet", "C02PacketUseEntity is not mapped");
        }
        putMethod("NetHandlerPlayClient.sendPacket", findSpecificMethod(get("NetHandlerPlayClient"), get("Packet")));
        Class<?> netHandlerPlayClient = get("NetHandlerPlayClient");
        if (netHandlerPlayClient != null) {
            Constructor<?>[] constructors = netHandlerPlayClient.getDeclaredConstructors();

            for (Constructor<?> ctor : constructors) {
                if (ctor.getParameterCount() == 4) {
                    Class<?> thirdParamType = ctor.getParameterTypes()[2];
                    put("NetworkManager", thirdParamType);
                    putField("NetHandlerPlayClient.networkManager",
                            getFieldByType(netHandlerPlayClient, get("NetworkManager")));
                    putField("NetworkManager.channel",
                            getFieldByType(get("NetworkManager"), io.netty.channel.Channel.class));
                    for (Constructor<?> netManagerCtor : thirdParamType.getDeclaredConstructors()) {
                        if (netManagerCtor.getParameterCount() == 1) {
                            Class<?> firstParamType = netManagerCtor.getParameterTypes()[0];
                            put("EnumPacketDirection", firstParamType);
                            break;
                        }
                    }
                    break;
                }
            }
        } else {
            warnMappingSkip("NetworkManager", "NetHandlerPlayClient is not mapped");
        }

        if (get("Packet") != null) {
            put("C0APacketAnimation", findSwingPacket(get("Packet")));
        }

        for (Class<?> clazz : getClasses()) {
            if (isIChatComponent(clazz)) {
                put("IChatComponent", clazz);
                break;
            }
        }

        for (Class<?> clazz : getClasses()) {
            if (isChatComponentStyle(clazz)) {
                put("ChatComponentStyle", clazz);
                break;
            }
        }

        for (Class<?> clazz : getClasses()) {
            if (isChatComponentText(clazz)) {
                put("ChatComponentText", clazz);
                break;
            }
        }

        if (get("EntityPlayerSP") != null && get("IChatComponent") != null) {
            for (Method method : get("EntityPlayerSP").getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (params.length == 1 && params[0] == get("IChatComponent")) {
                    putMethod("EntityPlayerSP.addChatMessage", method);
                    break;
                }
            }
        }

        Class<?> velocityPacket = findS12PacketVelocity();
        put("S12PacketEntityVelocity", velocityPacket);

        if (velocityPacket != null) {
            mapVelocityFields(velocityPacket);
            mapVelocityMethods(velocityPacket);

            for (Method m : velocityPacket.getDeclaredMethods()) {
                if (Modifier.isPublic(m.getModifiers()) && m.getReturnType() == int.class && m.getParameterCount() == 0) {
                    m.setAccessible(true);
                    putMethod("S12PacketEntityVelocity.getEntityID", m);
                    break;
                }
            }

            findMotionFields(getClassBytes(velocityPacket));
        } else {
            warnMappingSkip("S12PacketEntityVelocity", "velocity packet class was not found");
        }

        try {

            put("S0BPacketAnimation", findS0BPacketAnimation());
            try {
                findSwingMethod(getClassBytes(get("EntityPlayerSP")));
            } catch (Throwable t) {
                Logger.warn("EntityPlayerSP.swingItem mapping skipped: " + String.valueOf(t.getMessage()));
            }

            Object player = getThePlayer();
            Class<?> entityClazz = get("Entity");
            if (player != null && entityClazz != null) {
                Method[] methods = entityClazz.getMethods();

                Method matchedMethod = null;
                int matchedCount = 0;

                for (Method method : methods) {

                    if (Modifier.isPublic(method.getModifiers())
                            && method.getParameterCount() == 0
                            && method.getReturnType() == float.class) {
                        try {
                            float val = (float) method.invoke(player);

                            if (val <= 0.0f)
                                continue;
                            if (val < 1.0f)
                                continue;

                            if (val >= 3.0f || val == 0.0f || val == 1.0f || val == 0.1f)
                                continue;

                            matchedCount++;
                            matchedMethod = method;

                        } catch (Exception e) {
                            continue;
                        }
                    }
                }

                if (matchedCount == 1) {
                    putMethod("Entity.getEyeHeight", matchedMethod);
                }
            } else {
                warnMappingSkip("Entity.getEyeHeight", "player instance or Entity class is not available");
            }

            Class<?> c02PacketUseEntityForVec = get("C02PacketUseEntity");
            Class<?> c02PacketUseEntityActionForVec = get("C02PacketUseEntityAction");

            if (c02PacketUseEntityForVec != null && c02PacketUseEntityActionForVec != null) {
                Constructor<?>[] c12 = c02PacketUseEntityForVec.getDeclaredConstructors();

                for (Constructor<?> ctor : c12) {
                    Class<?>[] paramTypes = ctor.getParameterTypes();

                    if (paramTypes.length == 2) {
                        Class<?> secondParam = paramTypes[1];

                        if (!secondParam.equals(c02PacketUseEntityActionForVec)) {
                            put("Vec3", secondParam);
                            break;
                        }
                    }
                }
            }

            for (Class<?> clazz : getClasses()) {
                if (!clazz.isEnum())
                    continue;

                Object[] enumConstants = clazz.getEnumConstants();
                if (enumConstants == null || enumConstants.length == 0)
                    continue;

                boolean looksLikeEnumFacing = false;
                for (Object c : enumConstants) {
                    try {
                        Method nameMethod = c.getClass().getMethod("name");
                        String name = (String) nameMethod.invoke(c);
                        if (name.equalsIgnoreCase("DOWN") || name.equalsIgnoreCase("UP")
                                || name.equalsIgnoreCase("NORTH")) {
                            looksLikeEnumFacing = true;
                            break;
                        }
                    } catch (Exception ignored) {
                    }
                }

                if (looksLikeEnumFacing) {
                    put("EnumFacing", clazz);

                    for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {

                        Class<?>[] params = ctor.getParameterTypes();

                        if (params.length == 11
                                && params[0] == String.class
                                && params[1] == int.class
                                && params[2] == String.class
                                && params[3] == int.class
                                && params[4] == int.class
                                && params[5] == int.class
                                && params[6] == int.class
                                && params[7] == String.class) {

                            put("EnumAxisDirection", params[8]);
                            put("EnumAxis", params[9]);
                            put("Vec3i", params[10]);

                            put("EnumFacing", clazz);
                            break;
                        }

                    }

                    break;
                }
            }

            for (Class<?> clazz : getClasses()) {
                if (clazz.isInterface() || clazz.isEnum())
                    continue;

                Constructor<?>[] constructorsv = clazz.getDeclaredConstructors();
                if (constructorsv.length == 5) {

                    Class<?> movingObjectTypeClass = get("MovingObjectType");
                    Class<?> vec3Class = get("Vec3");
                    Class<?> enumFacingClass = get("EnumFacing");
                    Class<?> blockPosClass = get("BlockPos");

                    boolean hasMatchingCtor = false;
                    Class<?> discoveredMovingObjectType = null;
                    Class<?> discoveredBlockPos = null;

                    for (Constructor<?> ctor : constructorsv) {
                        Class<?>[] params = ctor.getParameterTypes();

                        if (params.length == 4) {
                            if (vec3Class == null) {

                                break;
                            }

                            if (enumFacingClass == null) {

                                break;
                            }

                            if (!params[1].equals(vec3Class) || !params[2].equals(enumFacingClass)) {
                                continue;
                            }

                            if (movingObjectTypeClass != null && params[0] != movingObjectTypeClass) {
                                continue;
                            }
                            if (movingObjectTypeClass == null
                                    && (!params[0].isEnum() || !isGameRuntimeClass(params[0]))) {
                                continue;
                            }

                            if (blockPosClass != null && params[3] != blockPosClass) {
                                continue;
                            }
                            if (blockPosClass == null && !isBlockPosCandidate(params[3])) {
                                continue;
                            }

                            discoveredMovingObjectType = params[0];
                            discoveredBlockPos = params[3];
                            hasMatchingCtor = true;
                            break;
                        }
                    }

                    if (hasMatchingCtor) {
                        if (movingObjectTypeClass == null) {
                            movingObjectTypeClass = discoveredMovingObjectType;
                        }
                        if (blockPosClass == null) {
                            blockPosClass = discoveredBlockPos;
                        }

                        boolean hasPrivateBlockPosField = false;
                        for (Field f : clazz.getDeclaredFields()) {
                            if (f.getType() == blockPosClass) {
                                hasPrivateBlockPosField = true;
                                break;
                            }
                        }

                        if (hasPrivateBlockPosField) {
                            put("MovingObjectType", movingObjectTypeClass);
                            put("BlockPos", blockPosClass);
                            put("MovingObjectPosition", clazz);
                            for (Field field : clazz.getDeclaredFields()) {
                                if (Modifier.isPublic(field.getModifiers()) &&
                                        movingObjectTypeClass != null &&
                                        field.getType() == movingObjectTypeClass) {

                                    putField("MovingObjectPosition.typeOfHit", field);
                                    break;
                                }
                            }

                            break;
                        }
                    }
                }
            }

            Class<?> minecraftClass = get("Minecraft");
            Class<?> movingObjectPositionClass = get("MovingObjectPosition");

            if (minecraftClass != null && movingObjectPositionClass != null) {
                for (Field field : minecraftClass.getDeclaredFields()) {
                    if (field.getType() == movingObjectPositionClass) {
                        putField("Minecraft.objectMouseOver", field);
                        break;
                    }
                }
            }

            Class<?> entityClass2 = get("Entity");
            Class<?> vec3Class = get("Vec3");

            if (movingObjectPositionClass != null) {
                for (Field field : movingObjectPositionClass.getDeclaredFields()) {
                    if (field.getType() == entityClass2) {
                        putField("MovingObjectPosition.entityHit", field);
                    } else if (field.getType() == vec3Class) {
                        putField("MovingObjectPosition.hitVec", field);
                    }
                }
            }

            flag();
            Class<?> C07DiggingAction = findClassUsingFieldNames(
                    "START_DESTROY_BLOCK", "ABORT_DESTROY_BLOCK", "STOP_DESTROY_BLOCK");
            put("C07PacketPlayerDigging.Action", C07DiggingAction);

            if (C07DiggingAction != null) {
                Class<?> C07DiggingPacket = findClassByName(
                        getOuterClassName(C07DiggingAction.getName()));
                put("C07PacketPlayerDigging", C07DiggingPacket);
            } else {
                warnMappingSkip("C07PacketPlayerDigging", "digging action enum was not found");
            }

            Class<?> entityPlayerSP = get("EntityPlayerSP");

            if (entityPlayerSP != null) {
                for (Method method : entityPlayerSP.getDeclaredMethods()) {
                    if (Modifier.isPublic(method.getModifiers()) &&
                            method.getReturnType() == void.class &&
                            method.getParameterCount() == 1 &&
                            method.getParameterTypes()[0] == boolean.class) {

                        putMethod("EntityPlayerSP.setSprinting", method);
                        break;
                    }
                }
            }

            Class<?> iChatComponentClass = get("IChatComponent");
            Class<?> splitterClass = null;
            try {
                splitterClass = Class.forName("com.google.common.base.Splitter");
            } catch (ClassNotFoundException e) {
                warnMappingSkip("GuiNewChat discovery", "Splitter class is not available");
            }
            Class<?> loggerClass = null;
            try {
                loggerClass = Class.forName("org.apache.logging.log4j.Logger");
            } catch (ClassNotFoundException e) {
                warnMappingSkip("GuiNewChat discovery", "Log4j Logger class is not available");
            }
            Class<?> uriClass = null;
            try {
                uriClass = Class.forName("java.net.URI");
            } catch (ClassNotFoundException e) {
                warnMappingSkip("GuiNewChat discovery", "URI class is not available");
            }

            for (Class<?> clazz : getClasses()) {
                boolean hasProtectedBooleanMethodWithIChatComponent = false;
                for (Method m : clazz.getDeclaredMethods()) {
                    if (Modifier.isProtected(m.getModifiers())
                            && m.getReturnType() == boolean.class
                            && m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == iChatComponentClass) {
                        hasProtectedBooleanMethodWithIChatComponent = true;
                        break;
                    }
                }
                if (!hasProtectedBooleanMethodWithIChatComponent)
                    continue;

                boolean hasSplitterField = false;
                for (Field f : clazz.getDeclaredFields()) {
                    if (f.getType() == splitterClass) {
                        hasSplitterField = true;
                        break;
                    }
                }
                if (!hasSplitterField)
                    continue;

                int publicIntFieldCount = 0;
                for (Field f : clazz.getDeclaredFields()) {
                    if (Modifier.isPublic(f.getModifiers()) && f.getType() == int.class) {
                        publicIntFieldCount++;
                    }
                }
                if (publicIntFieldCount < 2)
                    continue;

                boolean hasLoggerField = false;
                for (Field f : clazz.getDeclaredFields()) {
                    if (f.getType() == loggerClass) {
                        hasLoggerField = true;
                        break;
                    }
                }
                if (!hasLoggerField)
                    continue;

                boolean hasURIField = false;
                for (Field f : clazz.getDeclaredFields()) {
                    if (f.getType() == uriClass) {
                        hasURIField = true;
                        break;
                    }
                }
                if (!hasURIField)
                    continue;

                boolean has3IntParamsThrowsIOExceptionMethod = false;
                for (Method m : clazz.getDeclaredMethods()) {
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length == 3
                            && params[0] == int.class
                            && params[1] == int.class
                            && params[2] == int.class) {

                        Class<?>[] exTypes = m.getExceptionTypes();
                        boolean throwsIOException = false;
                        for (Class<?> exType : exTypes) {
                            if (exType == IOException.class) {
                                throwsIOException = true;
                                break;
                            }
                        }

                        if (throwsIOException) {
                            has3IntParamsThrowsIOExceptionMethod = true;
                            break;
                        }
                    }
                }
                if (!has3IntParamsThrowsIOExceptionMethod)
                    continue;

                put("GuiScreen", clazz);
                break;
            }
            if (entityClass != null) {

                for (Method method : entityClass.getDeclaredMethods()) {
                    if (Modifier.isPublic(method.getModifiers()) &&
                            !Modifier.isStatic(method.getModifiers()) &&
                            method.getReturnType() == void.class &&
                            method.getParameterCount() == 0) {
                    }
                }

                for (Method method : entityClass.getDeclaredMethods()) {
                    if (Modifier.isPublic(method.getModifiers()) &&
                            method.getReturnType() == void.class &&
                            method.getParameterCount() == 3) {

                        Class<?>[] params = method.getParameterTypes();
                        if (params[0] == double.class &&
                                params[1] == double.class &&
                                params[2] == double.class) {

                            putMethod("Entity.setPositionAndUpdate", method);
                            break;
                        }
                    }
                }

            }
            if (get("Minecraft") != null && get("GuiScreen") != null) {
                for (Field field : get("Minecraft").getDeclaredFields()) {
                    if (field.getType() == get("GuiScreen") && Modifier.isPublic(field.getModifiers())) {
                        putField("Minecraft.currentScreen", field);
                        break;
                    }
                }
            }

            for (Class<?> clazz : getClasses()) {
                if (clazz.getSuperclass() != null) {
                    if (!clazz.getSuperclass().getName().equals("java.lang.Object")) {
                        Constructor<?>[] constructors6 = clazz.getDeclaredConstructors();
                        for (Constructor<?> ctor : constructors6) {
                            Class<?>[] params = ctor.getParameterTypes();
                            if (constructors6.length == 2) {
                                if (params.length == 2 &&
                                        params[0] == params[1] &&
                                        !params[0].isPrimitive() &&
                                        !params[0].getName().startsWith("java.")) {
                                    put("GuiChest", clazz);
                                    put("IInventory", params[0]);
                                    break;
                                }
                            }
                        }
                        if (get("GuiChest") == clazz)
                            break;
                    }
                }
            }

            Class<?> entityPlayerClass = get("EntityPlayer");

            Class<?> guiChest = get("GuiChest");
            Class<?> guiContainer = null;
            Class<?> containerCls = null;
            if (guiChest != null && entityPlayerClass != null) {
                guiContainer = guiChest.getSuperclass();
                put("GuiChest", guiChest);
                put("GuiContainer", guiContainer);
                containerCls = findContainerClassFromEntityPlayer(entityPlayerClass);
                put("Container", containerCls);
                putField("Container.windowId", findWindowID(containerCls));
                Field inventorySlots = getFieldOfType(guiContainer, containerCls);
                putField("Container.inventorySlots", inventorySlots);
                Class<?> slotClass = getProtectedMethodSingleParam(containerCls);
                put("Slot", slotClass);
                putMethod("Container.getSlot", findGetSlot());
                Field slotsField = getListFieldOfType(containerCls, slotClass);
                putField("Slot.inventorySlots", slotsField);
                Method getStackMethod = findGetStackMethod(slotClass);
                putMethod("Slot.getStack", getStackMethod);
                if (getStackMethod != null) {
                    put("ItemStack", getStackMethod.getReturnType());
                }
            } else {
                warnMappingSkip("GuiContainer/Container", "GuiChest or EntityPlayer is not mapped");
            }

            for (Class<?> clazz : getClasses()) {
                if (isPlayerControllerMP(clazz)) {
                    put("PlayerControllerMP", clazz);
                }
            }

            Class<?> playerControllerMP = get("PlayerControllerMP");

            if (playerControllerMP != null) {
                for (Method method : playerControllerMP.getDeclaredMethods()) {
                    Class<?>[] params = method.getParameterTypes();

                    if (params.length == 5 &&
                            params[0] == int.class &&
                            params[1] == int.class &&
                            params[2] == int.class &&
                            params[3] == int.class &&
                            params[4] == entityPlayerClass) {

                        putMethod("PlayerControllerMP.windowClick", method);
                        break;
                    }
                }

                if (minecraftClass != null) {
                    for (Field field : minecraftClass.getDeclaredFields()) {
                        if (field.getType() == playerControllerMP) {
                            putField("Minecraft.playerController", field);
                            break;
                        }
                    }
                }

            }

            Class<?>[] paramTypes = new Class<?>[] {
                    get("EntityPlayer"),
                    get("World"),
                    get("ItemStack"),
                    int.class,
                    int.class,
                    int.class,
                    int.class,
                    get("Vec3")
            };

            if (playerControllerMP != null) {
                Method foundMethod = findMethodFromParam(playerControllerMP, paramTypes);
                if (foundMethod != null) {
                    putMethod("PlayerControllerMP.onPlayerRightClick", foundMethod);
                }
            }

            try {
                Field sa = entityPlayerClass == null ? null : findInventoryContainer(getClassBytes(entityPlayerClass));
                putField("EntityPlayer.inventoryContainer", sa);

                if (entityPlayerClass != null && get("Container") != null) {
                    for (Field f : entityPlayerClass.getDeclaredFields()) {
                        if (f.getType().equals(get("Container")) && !f.equals(sa)) {
                            putField("EntityPlayer.openContainer", f);
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                warnMappingSkip("EntityPlayer container fields", e.getMessage());
            }

            Method rayTraceMethod = getMethodByParamAndReturnTypeAndVisibility(
                    get("Entity"),
                    new Class<?>[] { double.class, float.class },
                    get("MovingObjectPosition"),
                    "public");
            if (rayTraceMethod != null) {
                putMethod("Entity.rayTrace", rayTraceMethod);
            }

            Map<String, Class<?>> requiredFields = new HashMap<>();
            requiredFields.put("OF_NAME", String.class);
            requiredFields.put("MC_VERSION", String.class);
            requiredFields.put("OF_EDITION", String.class);
            requiredFields.put("OF_RELEASE", String.class);
            requiredFields.put("VERSION", String.class);
            requiredFields.put("renderPartialTicks", float.class);

            Class<?> configClass = findClassWithFieldsAndExactTypes(requiredFields, getClasses());

            put("craftrise.Config", configClass);

            if (configClass != null) {
                Field renderPartialTicksField = configClass.getDeclaredField("renderPartialTicks");
                putField("craftrise.Config.renderPartialTicks", renderPartialTicksField);
            } else {
                warnMappingSkip("craftrise.Config.renderPartialTicks", "Config class was not found");
            }

            try {
                Class<?> entityLivingBase = get("EntityLivingBase");
                if (entityLivingBase != null) {
                    Field swingField = findPublicBooleanDeclaredField(entityLivingBase);
                    if (swingField != null) {
                        putField("EntityLivingBase.isSwingInProgress", swingField);
                    }
                } else {
                    warnMappingSkip("EntityLivingBase.isSwingInProgress", "EntityLivingBase is not mapped");
                }
            } catch (Exception e) {
                warnMappingSkip("EntityLivingBase.isSwingInProgress", e.getMessage());
            }

            try {
                Class<?> mcClass = get("Minecraft");
                Object mcInstance = getMinecraft();

                if (mcClass == null || mcInstance == null) {
                    throw new IllegalStateException("Minecraft class or instance is null");
                }

                Field cps1 = null;
                Field cps2 = null;

                for (Field f : mcClass.getDeclaredFields()) {
                    try {
                        if (f.getType() == int.class &&
                                java.lang.reflect.Modifier.isPrivate(f.getModifiers()) &&
                                !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {

                            f.setAccessible(true);
                            int val = f.getInt(mcInstance);

                            if (val == 0) {
                                if (cps1 == null) {
                                    cps1 = f;
                                    putField("Minecraft.cps1", cps1);
                                } else if (cps2 == null) {
                                    cps2 = f;
                                    putField("Minecraft.cps2", cps2);
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("Field okunurken hata:");
                        e.printStackTrace();
                    }
                }

                if (cps1 == null || cps2 == null) {
                }

            } catch (Exception ex) {
                System.err.println("Genel hata:");
                ex.printStackTrace();
            }

            Class<?> fontRendererClass = null;

            for (Class<?> clazz : getClasses()) {
                try {

                    if (clazz.getInterfaces().length == 0)
                        continue;

                    boolean hasCtor = false;
                    for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
                        Class<?>[] params = ctor.getParameterTypes();
                        if (params.length == 4 && params[3] == boolean.class) {
                            hasCtor = true;
                            break;
                        }
                    }
                    if (!hasCtor)
                        continue;

                    boolean hasStringFloatFloatIntBoolMethod = false;

                    for (Method m : clazz.getDeclaredMethods()) {
                        Class<?>[] p = m.getParameterTypes();

                        if (p.length == 5 &&
                                p[0] == String.class &&
                                p[1] == float.class &&
                                p[2] == float.class &&
                                p[3] == int.class &&
                                p[4] == boolean.class) {
                            hasStringFloatFloatIntBoolMethod = true;
                        }
                    }

                    if (hasStringFloatFloatIntBoolMethod) {
                        fontRendererClass = clazz;
                        break;
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

            if (fontRendererClass != null) {
                put("FontRenderer", fontRendererClass);
            } else {
                System.out.println("FontRenderer bulunamadı.");
            }
            if (fontRendererClass != null) {
                for (Method m : fontRendererClass.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers())
                            && Modifier.isPublic(m.getModifiers())
                            && m.getParameterCount() == 0
                            && m.getReturnType() == String.class) {
                        putMethod("FontRenderer.getTitle", m);

                        break;
                    }
                }
            } else {
                System.out.println("FontRenderer bulunamadı.");
            }
            if (fontRendererClass != null) {
                Method drawStringMethod = null;

                for (Method m : fontRendererClass.getDeclaredMethods()) {
                    Class<?>[] params = m.getParameterTypes();

                    if (params.length == 5 &&
                            params[0] == String.class &&
                            params[1] == float.class &&
                            params[2] == float.class &&
                            params[3] == int.class &&
                            params[4] == boolean.class) {
                        drawStringMethod = m;
                        break;
                    }
                }

                if (drawStringMethod != null) {
                    putMethod("FontRenderer.drawString", drawStringMethod);
                } else {
                    System.out.println("drawString metodu bulunamadı.");
                }
            }

            try {
                Class<?> mcClass = get("Minecraft");

                if (mcClass == null || fontRendererClass == null) {
                    throw new IllegalStateException("Minecraft or FontRenderer class is not mapped");
                }

                Method targetMethod = null;

                for (Method m : mcClass.getDeclaredMethods()) {
                    try {
                        if (Modifier.isPublic(m.getModifiers()) &&
                                m.getReturnType() == fontRendererClass &&
                                m.getParameterCount() == 0) {

                            targetMethod = m;
                            break;
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                if (targetMethod != null) {
                    putMethod("Minecraft.getFontRendererObj", targetMethod);

                } else {
                    System.out.println("getFontRendererObj metodu bulunamadı.");
                }
            } catch (Exception e) {
                e.printStackTrace();
            }

            outer: for (Class<?> clazz : getClasses()) {

                boolean hasMapField = Arrays.stream(clazz.getDeclaredFields())
                        .anyMatch(f -> Modifier.isPrivate(f.getModifiers())
                                && Modifier.isStatic(f.getModifiers())
                                && Modifier.isFinal(f.getModifiers())
                                && java.util.Map.class.isAssignableFrom(f.getType()));

                if (!hasMapField) {
                    continue;
                }

                if (Arrays.stream(clazz.getDeclaredFields()).anyMatch(f -> Modifier.isProtected(f.getModifiers())
                        && Modifier.isFinal(f.getModifiers()) && f.getType() == get("Minecraft"))) {
                    java.util.Optional<java.lang.reflect.Method> targetMethod = Arrays
                            .stream(clazz.getDeclaredMethods())
                            .filter(m -> Modifier.isPublic(m.getModifiers())
                                    && m.getParameterCount() == 2
                                    && m.getParameterTypes()[1] == float.class)
                            .findFirst();

                    if (targetMethod.isPresent()) {
                        java.lang.reflect.Method method = targetMethod.get();
                        Class<?> unknownClass = method.getParameterTypes()[0];
                        put("ScaledResolution", unknownClass);
                        put("GuiInGame", clazz);
                        break outer;
                    }
                }
            }

            try {
                Class<?> mcClass = get("Minecraft");
                Class<?> guiIngameClass = get("GuiInGame");

                if (mcClass == null || guiIngameClass == null) {
                    throw new IllegalStateException("Minecraft or GuiInGame class is not mapped");
                }

                for (Field f : mcClass.getDeclaredFields()) {
                    if (f.getType() == guiIngameClass && Modifier.isPublic(f.getModifiers())) {
                        putField("Minecraft.ingameGUI", f);

                        break;
                    }
                }

            } catch (Exception e) {
                System.err.println("Minecraft.ingameGUI field'ı alınırken hata:");
                e.printStackTrace();
            }

            rectMethods();

            Method method = Arrays.stream(getAllMethods(get("FontRenderer")))
                    .filter(m -> m.getReturnType() == int.class
                            && m.getParameterCount() == 1
                            && m.getParameterTypes()[0].equals(String.class)
                            && Modifier.isPublic(m.getModifiers()))
                    .filter(m -> {
                        try {
                            Object fontRenderer = getFontRenderer();
                            int result = (int) m.invoke(fontRenderer, "alperenamigotudagitti");
                            return result == 105;
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .findFirst()
                    .orElse(null);

            if (method != null) {
                putMethod("FontRenderer.getStringWidth", method);
            }

            findTextureManagerClass();
            putMethod("Minecraft.getTextureManager",
                    findMethod(get("Minecraft"), Modifier.PUBLIC, get("TextureManager")));

            for (Method mt : getAllMethods(get("TextureManager"))) {
                Class<?>[] params = mt.getParameterTypes();
                if (params.length == 3
                        && !params[0].isPrimitive()
                        && params[1] == int.class
                        && params[2] == int.class) {
                    putMethod("TextureManager.bindTexture", mt);
                    break;
                }
            }

            Class<?> guiClass = get("Gui");
            if (guiClass == null) {
                warnMappingSkip("Gui ResourceLocation discovery", "Gui class is not mapped");
            } else {
                Field[] fields = guiClass.getDeclaredFields();
                Map<Class<?>, Integer> typeCount = new HashMap<>();

                for (Field f : fields) {
                    Class<?> type = f.getType();

                    if (!type.isPrimitive() && !List.class.isAssignableFrom(type)) {
                        typeCount.put(type, typeCount.getOrDefault(type, 0) + 1);
                    }
                }

                for (Map.Entry<Class<?>, Integer> entry : typeCount.entrySet()) {
                    if (entry.getValue() >= 3) {
                        put("ResourceLocation", entry.getKey());
                        break;
                    }
                }
            }

            Class<?> textureManager = get("TextureManager");
            Class<?> resourceLocationClass = get("ResourceLocation");
            if (textureManager == null || resourceLocationClass == null) {
                warnMappingSkip("TextureManager.getDynamicTextureLocation", "TextureManager or ResourceLocation is not mapped");
            } else {
                for (Method m : textureManager.getDeclaredMethods()) {
                    if (m.getReturnType().equals(resourceLocationClass)) {
                        Class<?>[] params = m.getParameterTypes();

                        if (params.length >= 2 && params[0].equals(String.class)) {
                            putMethod("TextureManager.getDynamicTextureLocation", m);
                            put("DynamicTexture", params[1]);
                            break;
                        }
                    }
                }
            }

            findS18();
            findOnGround(getClassBytes(get("S18PacketEntityTeleport")));
            findInventoryPlayerClass();

            Class<?> inventoryPlayerClass = get("InventoryPlayer");

            if (entityPlayerClass == null || inventoryPlayerClass == null) {
                warnMappingSkip("InventoryPlayer fields", "EntityPlayer or InventoryPlayer is not mapped");
            } else {
                for (Field field : entityPlayerClass.getDeclaredFields()) {
                    int mods = field.getModifiers();
                    if (Modifier.isPublic(mods) &&
                            field.getType().equals(inventoryPlayerClass)) {
                        putField("EntityPlayer.inventory", field);
                        break;
                    }
                }

                Field currentItem = findCurrentItem(getClassBytes(inventoryPlayerClass), inventoryPlayerClass);
                putField("InventoryPlayer.currentItem", currentItem);
                Class<?> clazz = get("InventoryPlayer");
                Class<?> itemStackClass = get("ItemStack");
                Object inventoryPlayerInstance = getInventory();

                if (clazz == null || itemStackClass == null || inventoryPlayerInstance == null) {
                    warnMappingSkip("InventoryPlayer array fields", "InventoryPlayer, ItemStack, or inventory instance is not available");
                } else {
                    for (Field field : clazz.getDeclaredFields()) {
                        if (field.getType().isArray() && field.getType().getComponentType().equals(itemStackClass)) {
                            field.setAccessible(true);
                            try {
                                Object arrayInstance = field.get(inventoryPlayerInstance);
                                if (arrayInstance == null)
                                    continue;
                                int length = java.lang.reflect.Array.getLength(arrayInstance);
                                if (length == 36)
                                    putField("InventoryPlayer.mainInventory", field);
                                else if (length == 4)
                                    putField("InventoryPlayer.armorInventory", field);
                            } catch (IllegalAccessException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
            findSkin();
            findGuiInventory();
            findGetChunkFromChunkCoords();
            findEffectRenderer();
            findRenderManager();

            processItemClass();
            findMathHelper();

            Field playerInfo = analyzeNetworkPlayerInfoField(get("AbstractClientPlayer"));
            if (playerInfo == null) {
                warnMappingSkip("NetworkPlayerInfo", "AbstractClientPlayer.playerInfo field was not found");
            } else {
                putField("AbstractClientPlayer.playerInfo", playerInfo);
                put("NetworkPlayerInfo", playerInfo.getType());
            }

            mapNetworkPlayerInfoAndPacketClasses();
            findC08PacketPlayerBlockPlacement();
            findC06PacketPlayerPosLook(getClasses());
            findItemStackSize();
            findRenderManagerFields();
            findEntityFields();
            processPacketClass();
            findItemStackDisplayName();
            findItemStackItemField();
            findGameProfile();
            findAxisAligned();
            processGameSettingsClass();
            findTimerContainer();
            processKeyBindingClasses();
            processMouseHelperClass();
            extractKeyBindings(get("GameSettings"));
            findThirdPersonViewField();
            findsendChatMessage();
            processModelBiped();
            findItemRendererClass();
            findTimerClass();
            findglstatemanager(20);
            processModelBipedMethods();
            findEntityRenderer();
            mapC03XYZFromC06();
            findSetTagInfoMethod();
            processFloatContainerClasses();
            findHeldItemChangePacketReflection();
            finds33packet();
            processC02PacketUseEntityMethods();

            findEntityBoat();
            findPlayerCap();

            findC13PacketPlayerAbilities();
            finds02();
            findEntityList();
            findallmobs();
            analyzeAddMappingFirstParam();
            findWorldBorderPacket();
            findTitlePacket();
            findS3FPacket();
            findC17Custom();
            finds37andstatbase();
            findUpdateSignPacket();

            findResourcePackStatusPacket();
            findClickWindowPacket();
            findC10PacketCreativeInventoryAction();

            findClientStatusPacket();
            findInputPacket();
            findHandshakePacket();
            findEntityActionPacket();
            findUpdateScorePacket();
            findc09packet();
            findGetSubCompoundMethod();
            findModelManager();
            worldacces();
            findrenderGlobal();
            findRendererLivingEntity();
            finds0cpacket();
            Class<?> playerClass = get("EntityPlayer");
            findPrevRenderYawOffset();
            findPlayerPosLookPacket();

            findC0DPacketCloseWindow();
            mapGetHealth();
            findS39PlayerCapabilities();
            findPlayerCapabilitiesMethods(get("NetHandlerPlayClient"), get("S39PacketPlayerAbilities"),
                    get("PlayerCapabilities"));
            mapPlayerCapabilitiesFields();
            mapMoveEntityWithHeadingAndParams();
            mapC13PacketPlayerAbilitiesFields();
            findEntityThrowable();
            mapIsBlockingASM();
            mapSyncCurrentPlayItemASM();

            findJumpOpcodeBased();
            for (Class<?> clazz123 : getClasses()) {
                if (!isTargetTextureClass(clazz123))
                    continue;

                Method getGlTextureId = findGetGlTextureId(clazz123);
                if (getGlTextureId == null)
                    continue;

                put("AbstractTexture", clazz123);
                putMethod("AbstractTexture.getGlTextureId", getGlTextureId);
                break;
            }
            mapPerformHurtAnimation();

            if (playerClass == null) {
                warnMappingSkip("EntityPlayer.setItemInUse", "EntityPlayer is not mapped");
            } else {
                for (Method m : playerClass.getDeclaredMethods()) {
                    if (!Modifier.isPublic(m.getModifiers()))
                        continue;
                    if (!m.getReturnType().equals(void.class))
                        continue;
                    Class<?>[] params = m.getParameterTypes();
                    if (params.length != 2)
                        continue;
                    if (!params[0].equals(get("ItemStack")))
                        continue;
                    if (!params[1].equals(int.class))
                        continue;
                    putMethod("EntityPlayer.setItemInUse", m);
                }
            }
            for (Class<?> clazz1 : getClasses()) {
                byte[] classBytes = getClassBytes(clazz1);
                if (classBytes == null || classBytes.length == 0)
                    continue;

                ClassNode cn = new ClassNode();
                new ClassReader(classBytes).accept(cn, 0);

                int staticFieldCount = 0;
                for (FieldNode f : cn.fields)
                    if ((f.access & Opcodes.ACC_STATIC) != 0)
                        staticFieldCount++;

                if (staticFieldCount <= 55 || staticFieldCount >= 80)
                    continue;

                boolean hasGsonMethod = false;
                for (MethodNode m : cn.methods) {
                    if ((m.access & Opcodes.ACC_PUBLIC) != 0 &&
                            (m.access & Opcodes.ACC_STATIC) != 0 &&
                            m.desc.equals("()Lcom/google/gson/Gson;")) {
                        hasGsonMethod = true;
                        break;
                    }
                }
                if (!hasGsonMethod)
                    continue;
                put("ClientUtils", clazz1);
                break;
            }

            for (Class<?> clazz1 : getClasses()) {
                byte[] classBytes = getClassBytes(clazz1);
                if (classBytes == null || classBytes.length == 0)
                    continue;

                ClassReader cr = new ClassReader(classBytes);
                ClassNode cn = new ClassNode();
                cr.accept(cn, 0);

                boolean hasNoArgConstructor = false;
                boolean hasMapConstructor = false;
                Field mapField = null;
                for (MethodNode m : cn.methods) {
                    if (m.name.equals("<init>")) {
                        org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(m.desc);
                        if (args.length == 0) {
                            hasNoArgConstructor = true;
                        } else if (args.length == 1
                                && args[0].getClassName().equals("it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap")) {
                            hasMapConstructor = true;
                        }
                    }
                }
                for (FieldNode f : cn.fields) {
                    if ((f.access & Opcodes.ACC_PRIVATE) != 0
                            && f.desc.equals("Lit/unimi/dsi/fastutil/ints/Int2ObjectOpenHashMap;")) {
                        try {
                            mapField = clazz1.getDeclaredField(f.name);
                            break;
                        } catch (NoSuchFieldException e) {
                        }
                    }
                }

                if (hasNoArgConstructor && hasMapConstructor && mapField != null) {
                    put("ScoreBoardUtil", clazz1);
                    putField("ScoreBoardUtil.scoreBoardInfo", mapField);
                    break;
                }
            }

            Class<?> clientUtilsClass = get("ClientUtils");
            Class<?> scoreBoardClass = get("ScoreBoardUtil");
            if (clientUtilsClass == null || scoreBoardClass == null) {
                warnMappingSkip("ClientUtils.scoreBoard", "ClientUtils or ScoreBoardUtil is not mapped");
            } else {
                for (Field field : clientUtilsClass.getDeclaredFields()) {
                    int mods = field.getModifiers();

                    if (Modifier.isPrivate(mods) && Modifier.isStatic(mods)
                            && field.getType().equals(scoreBoardClass)) {
                        field.setAccessible(true);
                        putField("ClientUtils.scoreBoard", field);
                        break;
                    }
                }
            }

        } catch (Exception e) {
            Logger.error("startMappings failed: " + String.valueOf(e.getMessage()));
            e.printStackTrace();
        }
    }

    // From Line 24069
    public static void mapGetHealth() {
        try {
            Class<?> clazz = get("EntityLivingBase");
            if (clazz == null) {
                System.out.println("[mapGetHealth] EntityLivingBase bulunamadı!");
                return;
            }

            System.out.println("[mapGetHealth] EntityLivingBase=" + clazz.getName());

            // DEBUG: log all no-arg float methods via reflection across hierarchy
            System.out.println("[mapGetHealth] Reflection scan for ()F methods:");
            Class<?> scan = clazz;
            while (scan != null && !scan.equals(Object.class)) {
                for (Method m : scan.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 && m.getReturnType() == float.class
                            && (m.getModifiers() & java.lang.reflect.Modifier.STATIC) == 0) {
                        System.out.println("[mapGetHealth]   " + scan.getSimpleName() + "." + m.getName() + "()F");
                    }
                }
                scan = scan.getSuperclass();
            }

            byte[] bytes = getClassBytes(clazz);
            if (bytes == null) {
                System.out.println("[mapGetHealth] class bytes NULL — reflection-only path");
                String[] knownNames = {"getHealth", "func_110143_aJ"};
                for (String name : knownNames) {
                    try {
                        Method m = clazz.getDeclaredMethod(name);
                        if (m.getReturnType() == float.class) {
                            m.setAccessible(true);
                            putMethod("EntityLivingBase.getHealth", m);
                            System.out.println("[mapGetHealth] reflection found: " + name);
                            return;
                        }
                    } catch (NoSuchMethodException ignored) {}
                }
                System.out.println("[mapGetHealth] reflection fallback failed");
                return;
            }

            System.out.println("[mapGetHealth] class bytes OK size=" + bytes.length);

            ClassReader cr = new ClassReader(bytes);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);

            // DEBUG: log all ()F methods in bytecode
            System.out.println("[mapGetHealth] ASM ()F methods:");
            for (MethodNode mn : cn.methods) {
                if ("()F".equals(mn.desc) && (mn.access & org.objectweb.asm.Opcodes.ACC_STATIC) == 0) {
                    System.out.println("[mapGetHealth]   " + mn.name + " insns=" + mn.instructions.size());
                }
            }

            MethodNode target = null;

            // Pass 1: strict original pattern
            for (MethodNode mn : cn.methods) {
                InsnList insns = mn.instructions;
                if (insns.size() < 6) continue;
                try {
                    if (insns.get(0).getOpcode() == 25 && insns.get(1).getOpcode() == 180 &&
                            insns.get(2).getOpcode() == 178 && insns.get(3).getOpcode() == 182 &&
                            insns.get(4).getOpcode() == 182 && insns.get(5).getOpcode() == 174) {
                        target = mn;
                        System.out.println("[mapGetHealth] Pass1 matched: " + mn.name);
                        break;
                    }
                } catch (Exception ignored) {}
            }

            // Pass 2: find health field via setHealth(F)V setter, then find getter
            if (target == null) {
                System.out.println("[mapGetHealth] Pass1 failed, trying Pass2...");
                Set<String> floatFields = new java.util.LinkedHashSet<>();
                for (org.objectweb.asm.tree.FieldNode fn : cn.fields) {
                    if ("F".equals(fn.desc) && (fn.access & org.objectweb.asm.Opcodes.ACC_STATIC) == 0)
                        floatFields.add(fn.name);
                }
                System.out.println("[mapGetHealth] float fields: " + floatFields);

                // Log all (F)V methods for debug
                System.out.println("[mapGetHealth] (F)V setter candidates:");
                for (MethodNode mn : cn.methods) {
                    if ("(F)V".equals(mn.desc) && (mn.access & org.objectweb.asm.Opcodes.ACC_STATIC) == 0)
                        System.out.println("[mapGetHealth]   " + mn.name);
                }

                String healthField = null;
                for (MethodNode mn : cn.methods) {
                    if (!"(F)V".equals(mn.desc) || (mn.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0) continue;
                    for (AbstractInsnNode insn : mn.instructions.toArray()) {
                        if (insn.getOpcode() == 181) {
                            org.objectweb.asm.tree.FieldInsnNode fin = (org.objectweb.asm.tree.FieldInsnNode) insn;
                            if ("F".equals(fin.desc) && floatFields.contains(fin.name)) {
                                healthField = fin.name;
                                System.out.println("[mapGetHealth] health field: " + healthField + " via setter=" + mn.name);
                                break;
                            }
                        }
                    }
                    if (healthField != null) break;
                }

                if (healthField != null) {
                    for (MethodNode mn : cn.methods) {
                        if (!"()F".equals(mn.desc) || (mn.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0) continue;
                        for (AbstractInsnNode insn : mn.instructions.toArray()) {
                            if (insn.getOpcode() == 180) {
                                org.objectweb.asm.tree.FieldInsnNode fin = (org.objectweb.asm.tree.FieldInsnNode) insn;
                                if (healthField.equals(fin.name)) {
                                    target = mn;
                                    System.out.println("[mapGetHealth] Pass2 matched: " + mn.name);
                                    break;
                                }
                            }
                        }
                        if (target != null) break;
                    }
                }
            }

            // Pass 3: known names
            if (target == null) {
                System.out.println("[mapGetHealth] Pass2 failed, trying Pass3...");
                String[] knownNames = {"getHealth", "func_110143_aJ"};
                for (String name : knownNames) {
                    try {
                        Method m = clazz.getDeclaredMethod(name);
                        if (m.getReturnType() == float.class) {
                            m.setAccessible(true);
                            putMethod("EntityLivingBase.getHealth", m);
                            System.out.println("[mapGetHealth] Pass3 found: " + name);
                            return;
                        }
                    } catch (NoSuchMethodException ignored) {}
                }
                System.out.println("[mapGetHealth] all passes failed");
                return;
            }

            Method real = clazz.getDeclaredMethod(target.name);
            real.setAccessible(true);
            putMethod("EntityLivingBase.getHealth", real);
            System.out.println("[mapGetHealth] SUCCESS -> " + target.name);

        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    // From Line 24193
    public static Field findCurrentItem(byte[] classBytes, Class<?> clazz) throws Exception {
        if (missingClassBytes("findCurrentItem", clazz, classBytes))
            return null;

        ClassReader cr = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        Set<String> initializedInCtor = new HashSet<>();
        for (MethodNode mn : cn.methods) {
            if ("<init>".equals(mn.name)) {
                for (AbstractInsnNode insn : mn.instructions.toArray()) {
                    if (insn instanceof FieldInsnNode) {
                        FieldInsnNode fin = (FieldInsnNode) insn;
                        if (fin.getOpcode() == PUTFIELD) {
                            if (cn.name != null && cn.name.equals(fin.owner)) {
                                if ("I".equals(fin.desc)) {
                                    initializedInCtor.add(fin.name);
                                }
                            }
                        }
                    }
                }
            }
        }
        for (FieldNode fn : cn.fields) {
            boolean isStatic = (fn.access & Opcodes.ACC_STATIC) != 0;
            boolean isPublic = (fn.access & Opcodes.ACC_PUBLIC) != 0;
            boolean isInt = "I".equals(fn.desc) || "Ljava/lang/Integer;".equals(fn.desc);
            boolean hasConstantValue = fn.value != null;
            if (!isStatic && isInt && !hasConstantValue && !initializedInCtor.contains(fn.name)) {
                try {
                    Field f = clazz.getDeclaredField(fn.name);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException e) {
                    continue;
                } catch (SecurityException se) {
                    continue;
                }
            }
        }

        return null;
    }

    // From Line 24427
    public static Field findWindowID(Class<?> clazz) throws Exception {
        byte[] bytes = getClassBytes(clazz);
        if (missingClassBytes("findWindowID", clazz, bytes))
            return null;

        ClassReader cr = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        Set<String> initializedInCtor = new HashSet<>();
        for (MethodNode mn : cn.methods) {
            if ("<init>".equals(mn.name)) {
                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn.getOpcode() == PUTFIELD) {
                        FieldInsnNode fin = (FieldInsnNode) insn;
                        if ("I".equals(fin.desc)) {
                            initializedInCtor.add(fin.name);
                        }
                    }
                }
            }
        }

        for (FieldNode fn : cn.fields) {
            boolean isPublic = (fn.access & Opcodes.ACC_PUBLIC) != 0;
            boolean isInt = "I".equals(fn.desc);
            boolean hasConstantValue = fn.value != null;

            if (isPublic && isInt && !hasConstantValue && !initializedInCtor.contains(fn.name)) {
                Field f = clazz.getDeclaredField(fn.name);
                f.setAccessible(true);
                return f;
            }
        }

        return null;
    }

    // From Line 24582
    public static void findGuiInventory() throws Exception {
        Class<?> guiContainerClass = get("GuiContainer");
        Class<?> entityPlayerClass = get("EntityPlayer");
        if (guiContainerClass == null || entityPlayerClass == null)
            return;

        for (Class<?> clazz : getClasses()) {
            Class<?> super1 = clazz.getSuperclass();
            if (super1 == null)
                continue;

            if (!Modifier.isAbstract(super1.getModifiers()))
                continue;

            Class<?> super2 = super1.getSuperclass();
            if (super2 == null)
                continue;

            if (!super2.equals(guiContainerClass))
                continue;

            Constructor<?>[] constructors = clazz.getDeclaredConstructors();
            if (constructors.length != 1)
                continue;

            Constructor<?> ctor = constructors[0];
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length != 1)
                continue;

            if (!params[0].equals(entityPlayerClass))
                continue;

            put("GuiInventory", clazz);

            for (Method method : clazz.getDeclaredMethods()) {
                int mods = method.getModifiers();
                if (Modifier.isPublic(mods) && Modifier.isStatic(mods)
                        && method.getReturnType() == void.class
                        && method.getParameterCount() == 6) {
                    Class<?>[] paramTypes = method.getParameterTypes();
                    if (paramTypes[0] == int.class &&
                            paramTypes[1] == int.class &&
                            paramTypes[2] == int.class &&
                            paramTypes[3] == float.class &&
                            paramTypes[4] == float.class) {

                        Class<?> entityLivingBaseClass = get("EntityLivingBase");
                        if (entityLivingBaseClass != null && paramTypes[5].equals(entityLivingBaseClass)) {
                            putMethod("GuiInventory.drawEntityOnScreen", method);
                            break;
                        }
                    }
                }
            }
            break;
        }
    }

    // From Line 25073
    public static void findOnGround(byte[] classBytes) {
        try {
            if (missingClassBytes("findOnGround", classBytes))
                return;

            ClassReader cr = new ClassReader(classBytes);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);

            Class<?> entityClass = get("Entity");
            if (entityClass == null)
                return;

            String entityDesc = "L" + org.objectweb.asm.Type.getInternalName(entityClass) + ";";

            for (MethodNode mn : cn.methods) {
                if (!mn.name.equals("<init>") || !mn.desc.equals("(" + entityDesc + ")V"))
                    continue;

                List<AbstractInsnNode> insns = Arrays.asList(mn.instructions.toArray());

                for (int i = 0; i < insns.size(); i++) {
                    AbstractInsnNode insn = insns.get(i);

                    if (insn.getOpcode() == PUTFIELD && insn instanceof FieldInsnNode) {
                        FieldInsnNode putFieldInsn = (FieldInsnNode) insn;

                        if (i >= 3) {
                            AbstractInsnNode insn1 = insns.get(i - 1);
                            AbstractInsnNode insn2 = insns.get(i - 2);
                            AbstractInsnNode insn3 = insns.get(i - 3);

                            if (insn1.getOpcode() == INVOKEVIRTUAL && insn1 instanceof MethodInsnNode &&
                                    insn2.getOpcode() == Opcodes.GETFIELD && insn2 instanceof FieldInsnNode &&
                                    insn3.getOpcode() == Opcodes.ALOAD && ((VarInsnNode) insn3).var == 1) {

                                MethodInsnNode invokeVirtualInsn = (MethodInsnNode) insn1;
                                FieldInsnNode getFieldInsn = (FieldInsnNode) insn2;

                                Field f = findField(entityClass, getFieldInsn.name, getFieldInsn.desc);
                                if (f != null) {
                                    putField("Entity.onGround", f);
                                    put("BooleanContainer", f.getType());

                                    Method m = f.getType().getDeclaredMethod(invokeVirtualInsn.name);
                                    if (m.getReturnType() == boolean.class) {
                                        putMethod("BooleanContainer.getValueBoolean", m);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[ERROR] findOnGround: " + e.getMessage());
        }
    }

    // From Line 25129
    public static void findInventoryPlayerClass() {
        Class<?> iInventoryClass = get("IInventory");
        Class<?> entityPlayerClass = get("EntityPlayer");
        Class<?> itemStackClass = get("ItemStack");

        if (iInventoryClass == null || entityPlayerClass == null || itemStackClass == null)
            return;

        for (Class<?> clazz : getClasses()) {
            boolean implementsIInventory = false;
            for (Class<?> iface : clazz.getInterfaces()) {
                if (iface.equals(iInventoryClass)) {
                    implementsIInventory = true;
                    break;
                }
            }
            if (!implementsIInventory)
                continue;

            Constructor<?>[] constructors = clazz.getConstructors();

            boolean hasEntityPlayerConstructor = false;
            for (Constructor<?> c : constructors) {
                Class<?>[] params = c.getParameterTypes();
                if (params.length == 1 && params[0].equals(entityPlayerClass)) {
                    hasEntityPlayerConstructor = true;
                    break;
                }
            }
            if (!hasEntityPlayerConstructor)
                continue;

            put("InventoryPlayer", clazz);
            break;
        }
    }

    // From Line 25166
    public static Class<?> findS18() {
        List<Class<?>> classes = getClasses();
        Class<?> packetClass = get("Packet");

        for (Class<?> clazz : classes) {
            if (packetClass.isAssignableFrom(clazz)) {
                Constructor<?>[] constructors = clazz.getDeclaredConstructors();
                if (constructors.length < 3)
                    continue;

                for (Constructor<?> constructor : constructors) {
                    Class<?>[] parameterTypes = constructor.getParameterTypes();
                    if (parameterTypes.length == 7 && parameterTypes[0] == int.class && parameterTypes[1] == int.class
                            && parameterTypes[2] == int.class && parameterTypes[3] == int.class
                            && parameterTypes[4] == byte.class && parameterTypes[5] == byte.class
                            && parameterTypes[6] == boolean.class) {
                        put("S18PacketEntityTeleport", clazz);
                        return clazz;
                    }
                }
            }
        }
        return null;
    }

    // From Line 25191
    public static void findTitlePacket() {
        Class<?> titleTypeEnum = findClassUsingFieldNames("SUBTITLE");

        if (titleTypeEnum == null || !titleTypeEnum.isEnum()) {
            System.out.println("SUBTITLE field'ına sahip enum bulunamadı.");
            return;
        }

        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> packetIfc = get("Packet");
        Class<?> iNetHandlerPlayClient = get("INetHandlerPlayClient");

        if (packetIfc == null || iNetHandlerPlayClient == null) {

            return;
        }

        for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface() || !packetIfc.isAssignableFrom(candidate)) {
                continue;
            }

            boolean implementsCorrectGeneric = false;

            java.lang.reflect.Type[] genericInterfaces = candidate.getGenericInterfaces();
            for (java.lang.reflect.Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericInterface;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayClient)) {
                            implementsCorrectGeneric = true;
                            break;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                java.lang.reflect.Type genericSuperclass = candidate.getGenericSuperclass();
                if (genericSuperclass instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericSuperclass;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayClient)) {
                            implementsCorrectGeneric = true;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                continue;
            }

            boolean containsEnum = false;
            Class<?>[] declaredClasses = candidate.getDeclaredClasses();
            for (Class<?> innerClass : declaredClasses) {
                if (innerClass.equals(titleTypeEnum)) {
                    containsEnum = true;
                    break;
                }
            }

            if (containsEnum) {
                put("S45PacketTitle", candidate);
                put("S45PacketTitle.Type", titleTypeEnum);

                break;
            }
        }
    }

    // From Line 25270
    public static void findHandshakePacket() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> packetIfc = get("Packet");
        Class<?> intClass = Integer.TYPE;
        Class<?> stringClass = String.class;

        if (packetIfc == null) {
            System.out.println("Packet interface bulunamadı.");
            return;
        }

        for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface() || !packetIfc.isAssignableFrom(candidate)) {
                continue;
            }

            boolean hasGenericInterface = false;
            Class<?> genericHandlerClass = null;

            java.lang.reflect.Type[] genericInterfaces = candidate.getGenericInterfaces();
            for (java.lang.reflect.Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericInterface;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                            hasGenericInterface = true;
                            genericHandlerClass = (Class<?>) typeArgs[0];
                            break;
                        }
                    }
                }
            }

            if (!hasGenericInterface) {
                java.lang.reflect.Type genericSuperclass = candidate.getGenericSuperclass();
                if (genericSuperclass instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericSuperclass;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 && typeArgs[0] instanceof Class) {
                            hasGenericInterface = true;
                            genericHandlerClass = (Class<?>) typeArgs[0];
                        }
                    }
                }
            }

            if (!hasGenericInterface || genericHandlerClass == null) {
                continue;
            }

            boolean hasNoArgConstructor = false;
            boolean hasHandshakeConstructor = false;
            Class<?> enumConnectionStateClass = null;

            for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();

                if (params.length == 0) {
                    hasNoArgConstructor = true;
                }

                if (params.length == 4) {
                    if ((params[0] == intClass || params[0] == Integer.class) &&
                            params[1] == stringClass &&
                            (params[2] == intClass || params[2] == Integer.class) &&
                            params[3].isEnum()) {

                        enumConnectionStateClass = params[3];
                        hasHandshakeConstructor = true;
                    }
                }
            }

            if (hasNoArgConstructor && hasHandshakeConstructor && enumConnectionStateClass != null) {
                put("C00Handshake", candidate);
                put("INetHandlerHandshakeServer", genericHandlerClass);
                put("EnumConnectionState", enumConnectionStateClass);
                break;
            }
        }
    }

    // From Line 25356
    public static void findc09packet() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> packetIfc = get("Packet");
        Class<?> iNetHandlerPlayServer = get("INetHandlerPlayServer");
        Class<?> intClass = Integer.TYPE;

        if (packetIfc == null || iNetHandlerPlayServer == null) {

            return;
        }

        for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface() || !packetIfc.isAssignableFrom(candidate)) {
                continue;
            }

            boolean implementsCorrectGeneric = false;

            java.lang.reflect.Type[] genericInterfaces = candidate.getGenericInterfaces();
            for (java.lang.reflect.Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericInterface;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayServer)) {
                            implementsCorrectGeneric = true;
                            break;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                java.lang.reflect.Type genericSuperclass = candidate.getGenericSuperclass();
                if (genericSuperclass instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericSuperclass;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayServer)) {
                            implementsCorrectGeneric = true;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                continue;
            }

            boolean hasNoArgConstructor = false;
            boolean hasIntConstructor = false;

            for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();

                if (params.length == 0) {
                    hasNoArgConstructor = true;
                }

                if (params.length == 1 && (params[0] == intClass || params[0] == Integer.class)) {
                    hasIntConstructor = true;
                }
            }

            if (hasNoArgConstructor && hasIntConstructor) {
                put("C09PacketHeldItemChange", candidate);

                break;
            }
        }
    }

    // From Line 25434
    public static void findUpdateScorePacket() {
        Class<?> actionEnum = findClassUsingFieldNames("REMOVE", "CHANGE");

        if (actionEnum == null || !actionEnum.isEnum()) {
            System.out.println("Score Action enum'u bulunamadı.");
            return;
        }

        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> packetIfc = get("Packet");
        Class<?> iNetHandlerPlayClient = get("INetHandlerPlayClient");

        if (packetIfc == null || iNetHandlerPlayClient == null) {

            return;
        }

        for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface() || !packetIfc.isAssignableFrom(candidate)) {
                continue;
            }

            boolean implementsCorrectGeneric = false;

            java.lang.reflect.Type[] genericInterfaces = candidate.getGenericInterfaces();
            for (java.lang.reflect.Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericInterface;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayClient)) {
                            implementsCorrectGeneric = true;
                            break;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                java.lang.reflect.Type genericSuperclass = candidate.getGenericSuperclass();
                if (genericSuperclass instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericSuperclass;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayClient)) {
                            implementsCorrectGeneric = true;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                continue;
            }

            boolean containsEnum = false;
            Class<?>[] declaredClasses = candidate.getDeclaredClasses();
            for (Class<?> innerClass : declaredClasses) {
                if (innerClass.equals(actionEnum)) {
                    containsEnum = true;
                    break;
                }
            }

            if (containsEnum) {
                put("S3CPacketUpdateScore", candidate);
                put("S3CPacketUpdateScore.Action", actionEnum);
                break;
            }
        }
    }

    // From Line 25512
    public static void findEntityActionPacket() {
        Class<?> actionEnum = findClassUsingFieldNames("RIDING_JUMP", "STOP_SPRINTING", "OPEN_INVENTORY",
                "STOP_SNEAKING");

        if (actionEnum == null || !actionEnum.isEnum()) {

            return;
        }

        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> packetIfc = get("Packet");
        Class<?> iNetHandlerPlayServer = get("INetHandlerPlayServer");

        if (packetIfc == null || iNetHandlerPlayServer == null) {

            return;
        }

        for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface() || !packetIfc.isAssignableFrom(candidate)) {
                continue;
            }

            boolean implementsCorrectGeneric = false;

            java.lang.reflect.Type[] genericInterfaces = candidate.getGenericInterfaces();
            for (java.lang.reflect.Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericInterface;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayServer)) {
                            implementsCorrectGeneric = true;
                            break;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                java.lang.reflect.Type genericSuperclass = candidate.getGenericSuperclass();
                if (genericSuperclass instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericSuperclass;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayServer)) {
                            implementsCorrectGeneric = true;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                continue;
            }

            boolean containsEnum = false;
            Class<?>[] declaredClasses = candidate.getDeclaredClasses();
            for (Class<?> innerClass : declaredClasses) {
                if (innerClass.equals(actionEnum)) {
                    containsEnum = true;
                    break;
                }
            }

            if (containsEnum) {
                put("C0BPacketEntityAction", candidate);
                put("C0BPacketEntityAction.Action", actionEnum);

                break;
            }
        }
    }

    // From Line 25592
    public static void findInputPacket() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> packetIfc = get("Packet");
        Class<?> iNetHandlerPlayServer = get("INetHandlerPlayServer");
        Class<?> floatClass = Float.TYPE;
        Class<?> booleanClass = Boolean.TYPE;

        if (packetIfc == null || iNetHandlerPlayServer == null) {

            return;
        }

        for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface() || !packetIfc.isAssignableFrom(candidate)) {
                continue;
            }

            boolean implementsCorrectGeneric = false;

            java.lang.reflect.Type[] genericInterfaces = candidate.getGenericInterfaces();
            for (java.lang.reflect.Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericInterface;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayServer)) {
                            implementsCorrectGeneric = true;
                            break;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                java.lang.reflect.Type genericSuperclass = candidate.getGenericSuperclass();
                if (genericSuperclass instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericSuperclass;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayServer)) {
                            implementsCorrectGeneric = true;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                continue;
            }

            boolean hasNoArgConstructor = false;
            boolean hasInputConstructor = false;

            for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();

                if (params.length == 0) {
                    hasNoArgConstructor = true;
                }

                if (params.length == 4) {
                    if ((params[0] == floatClass || params[0] == Float.class) &&
                            (params[1] == floatClass || params[1] == Float.class) &&
                            (params[2] == booleanClass || params[2] == Boolean.class) &&
                            (params[3] == booleanClass || params[3] == Boolean.class)) {
                        hasInputConstructor = true;
                    }
                }
            }

            if (hasNoArgConstructor && hasInputConstructor) {
                put("C0CPacketInput", candidate);

                break;
            }
        }
    }

    // From Line 25745
    public static Object getFontRenderer() {
        try {
            Object minecraftInstance = getMinecraft();
            Method getFontRendererMethod = getMethod("Minecraft.getFontRendererObj");
            if (minecraftInstance == null)
                return null;

            if (getFontRendererMethod != null) {
                return getFontRendererMethod.invoke(minecraftInstance);
            }

            Field fontRendererField = getField("Minecraft.fontRendererObj");
            if (fontRendererField != null) {
                return fontRendererField.get(minecraftInstance);
            }

            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // From Line 25878
    private static Field findPublicBooleanDeclaredField(Class<?> clazz) {
        for (Field field : clazz.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers()) && field.getType() == boolean.class) {
                return field;
            }
        }
        return null;
    }

    // From Line 25887
    public static Class<?> findClassWithFieldsAndExactTypes(Map<String, Class<?>> fieldsAndTypes, List<Class<?>> list) {
        for (Class<?> clazz : list) {
            Field[] fields = clazz.getDeclaredFields();
            boolean allMatch = true;
            for (Map.Entry<String, Class<?>> entry : fieldsAndTypes.entrySet()) {
                try {
                    Field f = clazz.getDeclaredField(entry.getKey());
                    if (!f.getType().equals(entry.getValue())) {
                        allMatch = false;
                        break;
                    }
                } catch (NoSuchFieldException e) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                return clazz;
            }
        }
        return null;
    }

    // From Line 26002
    public static void mapIsBlockingASM() throws Exception {

        Class<?> playerClass = get("EntityPlayer");
        byte[] bytes = getClassBytes(playerClass);
        if (missingClassBytes("mapIsBlockingASM", playerClass, bytes))
            return;

        ClassReader cr = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

        String foundName = null;
        String foundDesc = null;

        for (MethodNode mn : cn.methods) {

            if (!mn.desc.equals("()Z"))
                continue;
            if (mn.instructions == null || mn.instructions.size() == 0)
                continue;

            int jumpCount = 0;
            int booleanInvokeCount = 0;
            boolean hasObjectCompare = false;
            boolean hasShortCircuit = false;

            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {

                int op = insn.getOpcode();

                if (insn instanceof JumpInsnNode) {
                    jumpCount++;
                }

                if (op == Opcodes.IF_ACMPEQ || op == Opcodes.IF_ACMPNE) {
                    hasObjectCompare = true;
                }

                if (op == Opcodes.INVOKEVIRTUAL) {
                    MethodInsnNode min = (MethodInsnNode) insn;
                    if (min.desc.equals("()Z")) {
                        booleanInvokeCount++;
                    }
                }

                if (op == Opcodes.IFEQ || op == Opcodes.IFNE) {
                    hasShortCircuit = true;
                }
            }

            if (jumpCount >= 2
                    && booleanInvokeCount >= 1
                    && hasObjectCompare
                    && hasShortCircuit) {

                foundName = mn.name;
                foundDesc = mn.desc;
                break;
            }
        }

        if (foundName == null) {
            throw new RuntimeException("isBlocking ASM not found");
        }

        for (Method m : playerClass.getDeclaredMethods()) {
            if (m.getName().equals(foundName)
                    && org.objectweb.asm.Type.getMethodDescriptor(m).equals(foundDesc)) {

                m.setAccessible(true);
                putMethod("EntityPlayer.isBlocking", m);
                System.out.println("[ASM FINAL] isBlocking FOUND -> " + m);
                return;
            }
        }

        throw new RuntimeException("Reflection match failed");
    }

    // From Line 26412
    public static Method findMethodFromParam(Class<?> clazz, Class<?>[] paramTypes) {
        if (clazz == null || paramTypes == null) {
            return null;
        }
        for (Class<?> paramType : paramTypes) {
            if (paramType == null) {
                return null;
            }
        }
        for (Method method : clazz.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length != paramTypes.length)
                continue;

            boolean match = true;
            for (int i = 0; i < params.length; i++) {
                if (params[i] != paramTypes[i]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return method;
            }
        }
        return null;
    }

    // From Line 26536
    public static void flag() {
        Class<?> entity = get("Entity");
        if (entity == null) {
            warnMappingSkip("flag", "Entity is not mapped");
            return;
        }

        Method getFlag = null;
        Method setFlag = null;

        for (Method m : getAllMethods(entity)) {
            if (Modifier.isProtected(m.getModifiers())) {
                if (m.getReturnType() == boolean.class &&
                        m.getParameterCount() == 1 &&
                        m.getParameterTypes()[0] == int.class) {
                    getFlag = m;
                } else if (m.getReturnType() == void.class &&
                        m.getParameterCount() == 2 &&
                        m.getParameterTypes()[0] == int.class &&
                        m.getParameterTypes()[1] == boolean.class) {
                    setFlag = m;
                }
            }
            if (getFlag != null && setFlag != null)
                break;
        }

        if (getFlag != null)
            putMethod("Entity.getFlag", getFlag);
        if (setFlag != null) {
            setFlag.setAccessible(true);
            putMethod("Entity.setFlag", setFlag);
        }
    }

    // From Line 26764
    public static void mapMoveEntityWithHeadingAndParams() {
        try {
            Class<?> clazz = get("EntityLivingBase");
            byte[] bytes = getClassBytes(clazz);
            if (missingClassBytes("mapMoveEntityWithHeadingAndParams", clazz, bytes))
                return;

            ClassReader cr = new ClassReader(bytes);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);

            MethodNode moveNode = null;
            String moveName = null;

            for (MethodNode mn : cn.methods) {
                if ((mn.access & Opcodes.ACC_PUBLIC) == 0)
                    continue;
                if (!mn.desc.equals("(FF)V"))
                    continue;

                boolean override = false;
                try {
                    clazz.getSuperclass().getDeclaredMethod(mn.name, float.class, float.class);
                    override = true;
                } catch (Exception ignored) {
                }

                if (!override) {
                    moveNode = mn;
                    moveName = mn.name;
                    break;
                }
            }

            if (moveNode == null)
                return;

            putMethod(
                    "EntityLivingBase.moveEntityWithHeading",
                    clazz.getDeclaredMethod(moveName, float.class, float.class));

            MethodInsnNode callInsn = null;

            for (MethodNode mn : cn.methods) {
                for (AbstractInsnNode i = mn.instructions.getFirst(); i != null; i = i.getNext()) {
                    if (i.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                        MethodInsnNode min = (MethodInsnNode) i;
                        if (min.name.equals(moveName) && min.desc.equals("(FF)V")) {
                            callInsn = min;
                            break;
                        }
                    }
                }
                if (callInsn != null)
                    break;
            }

            if (callInsn == null)
                return;

            FieldInsnNode f1 = null, f2 = null;
            AbstractInsnNode ins = callInsn.getPrevious();
            int found = 0;

            while (ins != null && found < 2) {
                if (ins.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                    MethodInsnNode getter = (MethodInsnNode) ins;
                    if (getter.desc.equals("()F")) {
                        AbstractInsnNode prev = ins.getPrevious();
                        if (prev != null && prev.getOpcode() == Opcodes.GETFIELD) {
                            if (found == 0)
                                f2 = (FieldInsnNode) prev;
                            else
                                f1 = (FieldInsnNode) prev;
                            found++;
                            ins = prev.getPrevious();
                            continue;
                        }
                    }
                }
                ins = ins.getPrevious();
            }

            if (f1 == null || f2 == null)
                return;

            putField("EntityLivingBase.moveStrafing", clazz.getDeclaredField(f1.name));
            putField("EntityLivingBase.moveForward", clazz.getDeclaredField(f2.name));

            Class<?> floatContainer = get("FloatContainer");

            java.lang.reflect.Field jumpMovementFactor = null;
            java.lang.reflect.Field landMovementFactor = null;
            java.lang.reflect.Field randomYawVelocity = null;

            for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {

                if (f.getType() != floatContainer)
                    continue;

                int mod = f.getModifiers();

                if (Modifier.isPublic(mod) && Modifier.isFinal(mod)) {
                    jumpMovementFactor = f;
                    continue;
                }

                if (Modifier.isPrivate(mod)) {
                    landMovementFactor = f;
                    continue;
                }

                if (Modifier.isProtected(mod)) {
                    randomYawVelocity = f;
                }
            }

            if (jumpMovementFactor != null)
                putField("EntityLivingBase.jumpMovementFactor", jumpMovementFactor);

            if (landMovementFactor != null)
                putField("EntityLivingBase.landMovementFactor", landMovementFactor);

            if (randomYawVelocity != null)
                putField("EntityLivingBase.randomYawVelocity", randomYawVelocity);

            if (landMovementFactor == null)
                return;

            Method getLand = null;
            Method setLand = null;

            String fcOwner = floatContainer.getName().replace('.', '/');

            for (MethodNode mn : cn.methods) {

                if ((mn.access & Opcodes.ACC_PUBLIC) == 0)
                    continue;

                if (mn.desc.equals("()F")) {
                    for (AbstractInsnNode ain = mn.instructions.getFirst(); ain != null; ain = ain.getNext()) {
                        if (ain.getOpcode() == Opcodes.GETFIELD) {
                            FieldInsnNode fin = (FieldInsnNode) ain;
                            if (fin.name.equals(landMovementFactor.getName())) {
                                getLand = clazz.getDeclaredMethod(mn.name);
                                break;
                            }
                        }
                    }
                }

                if (mn.desc.equals("(F)V")) {

                    for (AbstractInsnNode ain = mn.instructions.getFirst(); ain != null; ain = ain.getNext()) {

                        if (ain.getOpcode() != Opcodes.GETFIELD)
                            continue;

                        FieldInsnNode fin = (FieldInsnNode) ain;
                        if (!fin.name.equals(landMovementFactor.getName()))
                            continue;

                        AbstractInsnNode fload = fin.getNext();
                        if (fload == null || fload.getOpcode() != Opcodes.FLOAD)
                            continue;

                        AbstractInsnNode invoke = fload.getNext();
                        if (invoke == null || invoke.getOpcode() != Opcodes.INVOKEVIRTUAL)
                            continue;

                        MethodInsnNode min = (MethodInsnNode) invoke;

                        if (min.owner.equals(fcOwner) && min.desc.equals("(F)F")) {
                            setLand = clazz.getDeclaredMethod(mn.name, float.class);
                            break;
                        }
                    }
                }
            }

            if (getLand != null)
                putMethod("EntityLivingBase.getLandMovementFactor", getLand);

            if (setLand != null)
                putMethod("EntityLivingBase.setLandMovementFactor", setLand);

        } catch (Exception ignored) {
        }
    }

    // From Line 27001
    private static String opcodeName(int op) {
        if (op == -1)
            return "NONOP";

        switch (op) {
            case Opcodes.NOP:
                return "NOP";
            case Opcodes.ALOAD:
                return "ALOAD";
            case Opcodes.FLOAD:
                return "FLOAD";
            case Opcodes.GETFIELD:
                return "GETFIELD";
            case Opcodes.PUTFIELD:
                return "PUTFIELD";
            case Opcodes.INVOKEVIRTUAL:
                return "INVOKEVIRTUAL";
            case Opcodes.INVOKESTATIC:
                return "INVOKESTATIC";
            case Opcodes.RETURN:
                return "RETURN";
            case Opcodes.FRETURN:
                return "FRETURN";
            case Opcodes.DUP:
                return "DUP";
            case Opcodes.FCONST_0:
                return "FCONST_0";
            case Opcodes.FCONST_1:
                return "FCONST_1";
            default:
                return "OP_" + op;
        }
    }

    // From Line 27035
    public static void mapPerformHurtAnimation() {
        try {
            Class<?> clazz = get("EntityLivingBase");
            byte[] bytes = getClassBytes(clazz);
            if (missingClassBytes("mapPerformHurtAnimation", clazz, bytes))
                return;

            ClassReader cr = new ClassReader(bytes);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);

            MethodNode target = null;

            FieldInsnNode hurtTime = null;
            FieldInsnNode maxHurtTime = null;
            FieldInsnNode attackedAtYaw = null;

            for (MethodNode mn : cn.methods) {

                if (!mn.desc.equals("()V"))
                    continue;

                List<FieldInsnNode> putFields = new ArrayList<>();
                boolean writesZeroFloat = false;

                for (AbstractInsnNode ain = mn.instructions.getFirst(); ain != null; ain = ain.getNext()) {

                    if (ain.getOpcode() == Opcodes.PUTFIELD) {
                        putFields.add((FieldInsnNode) ain);
                    }

                    if (ain.getOpcode() == Opcodes.FCONST_0) {
                        AbstractInsnNode next = ain.getNext();
                        if (next != null && next.getOpcode() == Opcodes.PUTFIELD) {
                            writesZeroFloat = true;
                            attackedAtYaw = (FieldInsnNode) next;
                        }
                    }
                }

                if (putFields.size() != 3 || !writesZeroFloat)
                    continue;

                for (FieldInsnNode fin : putFields) {
                    String desc = fin.desc;
                    if (desc.equals("I")) {
                        if (hurtTime == null)
                            hurtTime = fin;
                        else
                            maxHurtTime = fin;

                    }
                }

                if (hurtTime != null && maxHurtTime != null && attackedAtYaw != null) {
                    target = mn;
                    break;
                }
            }

            if (target == null)
                return;

            putMethod(
                    "EntityLivingBase.performHurtAnimation",
                    clazz.getDeclaredMethod(target.name));

            putField("EntityLivingBase.hurtTime",
                    clazz.getDeclaredField(maxHurtTime.name));

            putField("EntityLivingBase.maxHurtTime",
                    clazz.getDeclaredField(hurtTime.name));

            putField("EntityLivingBase.attackedAtYaw",
                    clazz.getDeclaredField(attackedAtYaw.name));

        } catch (Exception ignored) {
        }
    }

    // From Line 27131
    public static Class<?> findS0BPacketAnimation() {
        Class<?> entityClass = get("Entity");
        if (entityClass == null) {
            System.out.println("❌ Entity sınıfı tanımlı değil, önce Entity bulunmalı.");
            return null;
        }

        for (Class<?> clazz : getClasses()) {
            try {
                
                Constructor<?>[] constructors = clazz.getDeclaredConstructors();
                Field[] fields = clazz.getDeclaredFields();
                Method[] methods = clazz.getDeclaredMethods();

                long privateIntFields = Arrays.stream(fields)
                        .filter(f -> Modifier.isPrivate(f.getModifiers()) && f.getType() == int.class)
                        .count();
                if (privateIntFields != 2)
                    continue;

                boolean hasEmptyConstructor = false;
                boolean hasEntityIntConstructor = false;

                for (Constructor<?> ctor : constructors) {
                    Class<?>[] params = ctor.getParameterTypes();
                    if (params.length == 0) {
                        hasEmptyConstructor = true;
                    } else if (params.length == 2 &&
                            params[0] == entityClass &&
                            params[1] == int.class) {
                        hasEntityIntConstructor = true;
                    }
                }
                if (!hasEmptyConstructor || !hasEntityIntConstructor)
                    continue;

                long ioExceptionCount = Arrays.stream(methods)
                        .filter(m -> Arrays.asList(m.getExceptionTypes()).contains(IOException.class))
                        .count();
                if (ioExceptionCount != 2)
                    continue;

                
                byte[] bytes = getClassBytes(clazz);
                if (missingClassBytes("findProtocolClass", clazz, bytes))
                    continue;

                ClassReader cr = new ClassReader(bytes);
                ClassNode cn = new ClassNode();
                cr.accept(cn, 0);

                MethodNode ctor = null;
                for (MethodNode mn : cn.methods) {
                    if (mn.name.equals("<init>")) {
                        org.objectweb.asm.Type[] args = org.objectweb.asm.Type.getArgumentTypes(mn.desc);
                        if (args.length == 2 &&
                                args[0].getClassName().equals(entityClass.getName()) &&
                                args[1].getSort() == org.objectweb.asm.Type.INT) {
                            ctor = mn;
                            break;
                        }
                    }
                }
                if (ctor == null)
                    continue;

                List<FieldInsnNode> intPuts = new ArrayList<>();
                MethodInsnNode getEntityIdCall = null;

                for (AbstractInsnNode insn : ctor.instructions) {
                    if (insn.getOpcode() == PUTFIELD) {
                        FieldInsnNode fin = (FieldInsnNode) insn;
                        if (fin.owner.equals(cn.name) && fin.desc.equals("I")) {
                            intPuts.add(fin);
                        }
                    } else if (insn.getOpcode() == INVOKEVIRTUAL) {
                        MethodInsnNode min = (MethodInsnNode) insn;
                        if (min.owner.equals(org.objectweb.asm.Type.getInternalName(entityClass)) &&
                                min.desc.equals("()I")) {
                            getEntityIdCall = min;
                        }
                    }
                }

                if (intPuts.size() != 2 || getEntityIdCall == null)
                    continue;

                
                FieldInsnNode entityIdField = intPuts.get(0);
                FieldInsnNode typeField = intPuts.get(1);

                putField("S0BPacketAnimation.entityId",
                        clazz.getDeclaredField(entityIdField.name));
                putField("S0BPacketAnimation.type",
                        clazz.getDeclaredField(typeField.name));

                Method getEntityId = entityClass.getDeclaredMethod(getEntityIdCall.name);
                putMethod("Entity.getEntityId", getEntityId);

                
                findAndSaveINetHandlerPlayClient(clazz);
                return clazz;

            } catch (Throwable t) {
                
            }
        }
        return null;
    }

    // From Line 27238
    private static void findAndSaveINetHandlerPlayClient(Class<?> packetClass) {
        Class<?> packetInterface = get("Packet");
        if (packetInterface == null) {
            System.out.println("Packet interface'i bulunamadı");
            return;
        }

        java.lang.reflect.Type[] genericInterfaces = packetClass.getGenericInterfaces();
        for (java.lang.reflect.Type genericInterface : genericInterfaces) {

            if (genericInterface instanceof java.lang.reflect.ParameterizedType) {
                java.lang.reflect.ParameterizedType paramType = (java.lang.reflect.ParameterizedType) genericInterface;
                java.lang.reflect.Type rawType = paramType.getRawType();

                if (rawType.equals(packetInterface)) {
                    java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();

                    if (typeArgs.length > 0) {
                        java.lang.reflect.Type firstArg = typeArgs[0];

                        if (extractAndSaveHandlerClientClass(firstArg)) {
                            return;
                        }
                    }
                }
            }
        }

        java.lang.reflect.Type genericSuperclass = packetClass.getGenericSuperclass();
        if (genericSuperclass instanceof java.lang.reflect.ParameterizedType) {
            java.lang.reflect.ParameterizedType paramType = (java.lang.reflect.ParameterizedType) genericSuperclass;
            java.lang.reflect.Type rawType = paramType.getRawType();

            if (rawType instanceof Class && packetInterface.isAssignableFrom((Class<?>) rawType)) {
                java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                if (typeArgs.length > 0) {
                    java.lang.reflect.Type firstArg = typeArgs[0];
                    extractAndSaveHandlerClientClass(firstArg);
                }
            }
        }
    }

    // From Line 27385
    public static void mapVelocityFields(Class<?> velocityPacket) {
        try {
            String owner = velocityPacket.getName().replace('.', '/');

            byte[] classBytes = getClassBytes(velocityPacket);
            if (classBytes == null)
                return;

            ClassReader cr = new ClassReader(classBytes);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);

            MethodNode init = null;
            for (MethodNode mn : cn.methods) {
                if ("<init>".equals(mn.name) && "()V".equals(mn.desc)) {
                    init = mn;
                    break;
                }
            }
            if (init == null)
                return;

            List<String> fieldNames = new ArrayList<>();

            for (AbstractInsnNode insn = init.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.PUTFIELD) {
                    FieldInsnNode f = (FieldInsnNode) insn;
                    if (f.owner.equals(owner)) {
                        fieldNames.add(f.name);
                    }
                }
            }
            if (fieldNames.size() < 3)
                return;
            Field fx = velocityPacket.getDeclaredField(fieldNames.get(0));
            Field fy = velocityPacket.getDeclaredField(fieldNames.get(1));
            Field fz = velocityPacket.getDeclaredField(fieldNames.get(2));

            fx.setAccessible(true);
            fy.setAccessible(true);
            fz.setAccessible(true);

            putField("S12PacketEntityVelocity.motionX", fx);
            putField("S12PacketEntityVelocity.motionY", fy);
            putField("S12PacketEntityVelocity.motionZ", fz);

        } catch (Throwable ignored) {
        }
    }

    // From Line 27435
    public static void mapVelocityMethods(Class<?> velocityPacket) {
        try {
            String owner = velocityPacket.getName().replace('.', '/');

            byte[] classBytes = getClassBytes(velocityPacket);
            if (classBytes == null)
                return;

            ClassReader cr = new ClassReader(classBytes);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);

            Field fx = getField("S12PacketEntityVelocity.motionX");
            Field fy = getField("S12PacketEntityVelocity.motionY");
            Field fz = getField("S12PacketEntityVelocity.motionZ");
            if (fx == null || fy == null || fz == null)
                return;

            String xName = fx.getName();
            String yName = fy.getName();
            String zName = fz.getName();

            for (MethodNode mn : cn.methods) {
                if (!"()D".equals(mn.desc))
                    continue;

                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == Opcodes.GETFIELD) {
                        FieldInsnNode fin = (FieldInsnNode) insn;
                        if (!fin.owner.equals(owner))
                            continue;

                        Method m;
                        try {
                            m = velocityPacket.getDeclaredMethod(mn.name);
                            m.setAccessible(true);
                        } catch (Throwable t) {
                            break;
                        }

                        if (fin.name.equals(xName)) {
                            putMethod("S12PacketEntityVelocity.getMotionX", m);
                        } else if (fin.name.equals(yName)) {
                            putMethod("S12PacketEntityVelocity.getMotionY", m);
                        } else if (fin.name.equals(zName)) {
                            putMethod("S12PacketEntityVelocity.getMotionZ", m);
                        }
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // From Line 27500
    public static void findS3FPacket() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> packetIfc = get("Packet");
        Class<?> iNetHandlerPlayClient = get("INetHandlerPlayClient");
        Class<?> packetBufferClass = get("PacketBuffer");

        if (packetIfc == null || iNetHandlerPlayClient == null || packetBufferClass == null) {

            return;
        }

        for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface() || !packetIfc.isAssignableFrom(candidate)) {
                continue;
            }

            boolean implementsCorrectGeneric = false;

            java.lang.reflect.Type[] genericInterfaces = candidate.getGenericInterfaces();
            for (java.lang.reflect.Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericInterface;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayClient)) {
                            implementsCorrectGeneric = true;
                            break;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                java.lang.reflect.Type genericSuperclass = candidate.getGenericSuperclass();
                if (genericSuperclass instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericSuperclass;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayClient)) {
                            implementsCorrectGeneric = true;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                continue;
            }

            boolean hasNoArgConstructor = false;
            boolean hasStringPacketBufferConstructor = false;

            for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();

                if (params.length == 0) {
                    hasNoArgConstructor = true;
                }

                if (params.length == 2 && params[0] == String.class && params[1] == packetBufferClass) {
                    hasStringPacketBufferConstructor = true;
                }
            }

            if (hasNoArgConstructor && hasStringPacketBufferConstructor) {
                put("S3FPacketCustomPayload", candidate);
            }
        }
    }

    // From Line 27576
    public static void findC17Custom() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> packetIfc = get("Packet");
        Class<?> iNetHandlerPlayClient = get("INetHandlerPlayServer");
        Class<?> packetBufferClass = get("PacketBuffer");

        if (packetIfc == null || iNetHandlerPlayClient == null || packetBufferClass == null) {

            return;
        }

        for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface() || !packetIfc.isAssignableFrom(candidate)) {
                continue;
            }

            boolean implementsCorrectGeneric = false;

            java.lang.reflect.Type[] genericInterfaces = candidate.getGenericInterfaces();
            for (java.lang.reflect.Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericInterface;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayClient)) {
                            implementsCorrectGeneric = true;
                            break;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                java.lang.reflect.Type genericSuperclass = candidate.getGenericSuperclass();
                if (genericSuperclass instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericSuperclass;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayClient)) {
                            implementsCorrectGeneric = true;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                continue;
            }

            boolean hasNoArgConstructor = false;
            boolean hasStringPacketBufferConstructor = false;

            for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();

                if (params.length == 0) {
                    hasNoArgConstructor = true;
                }

                if (params.length == 2 && params[0] == String.class && params[1] == packetBufferClass) {
                    hasStringPacketBufferConstructor = true;
                }
            }

            if (hasNoArgConstructor && hasStringPacketBufferConstructor) {
                put("C17PacketCustomPayload", candidate);
            }
        }
    }

    // From Line 27652
    public static void findWorldBorderPacket() {
        Class<?> actionEnum = findClassUsingFieldNames("SET_SIZE", "LERP_SIZE", "SET_CENTER", "SET_WARNING_TIME");

        if (actionEnum == null || !actionEnum.isEnum()) {
            System.out.println("WorldBorder Action enum'u bulunamadı.");
            return;
        }

        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> packetIfc = get("Packet");
        Class<?> iNetHandlerPlayClient = get("INetHandlerPlayClient");

        if (packetIfc == null || iNetHandlerPlayClient == null) {

            return;
        }

        for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface() || !packetIfc.isAssignableFrom(candidate)) {
                continue;
            }

            boolean implementsCorrectGeneric = false;

            java.lang.reflect.Type[] genericInterfaces = candidate.getGenericInterfaces();
            for (java.lang.reflect.Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericInterface;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayClient)) {
                            implementsCorrectGeneric = true;
                            break;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                java.lang.reflect.Type genericSuperclass = candidate.getGenericSuperclass();
                if (genericSuperclass instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericSuperclass;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayClient)) {
                            implementsCorrectGeneric = true;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                continue;
            }

            boolean containsEnum = false;
            Class<?>[] declaredClasses = candidate.getDeclaredClasses();
            for (Class<?> innerClass : declaredClasses) {
                if (innerClass.equals(actionEnum)) {
                    containsEnum = true;
                    break;
                }
            }

            if (containsEnum) {
                put("S44PacketWorldBorder", candidate);
                put("S44PacketWorldBorder$Action", actionEnum);

                break;
            }
        }
    }

    // From Line 27731
    public static void finds33packet() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> packetIfc = get("Packet");
        Class<?> iNetHandlerPlayClient = get("INetHandlerPlayClient");
        Class<?> worldClass = get("World");
        Class<?> blockPosClass = get("BlockPos");
        Class<?> iChatComponentClass = get("IChatComponent");

        if (packetIfc == null || iNetHandlerPlayClient == null || worldClass == null ||
                blockPosClass == null || iChatComponentClass == null) {

            return;
        }

        for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface() || !packetIfc.isAssignableFrom(candidate)) {
                continue;
            }

            boolean implementsCorrectGeneric = false;

            java.lang.reflect.Type[] genericInterfaces = candidate.getGenericInterfaces();
            for (java.lang.reflect.Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericInterface;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayClient)) {
                            implementsCorrectGeneric = true;
                            break;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                java.lang.reflect.Type genericSuperclass = candidate.getGenericSuperclass();
                if (genericSuperclass instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericSuperclass;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayClient)) {
                            implementsCorrectGeneric = true;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                continue;
            }

            boolean hasNoArgConstructor = false;
            boolean hasWorldBlockPosIChatComponentConstructor = false;

            for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();

                if (params.length == 0) {
                    hasNoArgConstructor = true;
                }

                if (params.length == 3) {
                    if (params[0] == worldClass &&
                            params[1] == blockPosClass &&
                            params[2] == iChatComponentClass) {
                        hasWorldBlockPosIChatComponentConstructor = true;
                    }

                    if (params[0] == worldClass &&
                            params[1] == blockPosClass &&
                            params[2].isArray() &&
                            params[2].getComponentType() == iChatComponentClass) {
                        hasWorldBlockPosIChatComponentConstructor = true;
                    }
                }
            }

            if (hasNoArgConstructor && hasWorldBlockPosIChatComponentConstructor) {
                put("S33PacketUpdateSign", candidate);
            }
        }
    }

    // From Line 27821
    public static void findC0DPacketCloseWindow() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> packetIfc = get("Packet");
        Class<?> iNetHandlerPlayServer = get("INetHandlerPlayServer");

        if (packetIfc == null || iNetHandlerPlayServer == null) {
            return;
        }

        for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface() || !packetIfc.isAssignableFrom(candidate)) {
                continue;
            }
            boolean implementsCorrectGeneric = false;

            java.lang.reflect.Type[] genericInterfaces = candidate.getGenericInterfaces();
            for (java.lang.reflect.Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericInterface;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayServer)) {
                            implementsCorrectGeneric = true;
                            break;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                java.lang.reflect.Type genericSuperclass = candidate.getGenericSuperclass();
                if (genericSuperclass instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericSuperclass;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayServer)) {
                            implementsCorrectGeneric = true;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                continue;
            }
            boolean hasIntField = false;
            for (Field field : candidate.getDeclaredFields()) {
                if (field.getType() == int.class) {
                    hasIntField = true;
                    break;
                }
            }

            if (!hasIntField) {
                continue;
            }
            Constructor<?> voidConstructor = null;
            Constructor<?> intConstructor = null;

            for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();

                if (params.length == 0) {
                    voidConstructor = ctor;
                }

                if (params.length == 1 && params[0] == int.class) {
                    intConstructor = ctor;
                    boolean hasIntFieldAssignment = false;
                    try {
                        ctor.setAccessible(true);
                        Object instance = ctor.newInstance(123);
                        for (Field field : candidate.getDeclaredFields()) {
                            if (field.getType() == int.class) {
                                field.setAccessible(true);
                                int value = field.getInt(instance);
                                if (value == 123) {
                                    hasIntFieldAssignment = true;
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) {
                    }

                    if (!hasIntFieldAssignment) {
                        intConstructor = null;
                    }
                }
            }
            if (voidConstructor != null && intConstructor != null) {
                put("C0DPacketCloseWindow", candidate);
                break;
            }
        }
    }

    // From Line 27946
    public static void findEntityThrowable() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> entityClass = get("Entity");
        Class<?> worldClass = get("World");
        Class<?> livingClass = get("EntityLivingBase");

        if (entityClass == null || worldClass == null || livingClass == null) {
            return;
        }
        for (Class<?> candidate : classes) {
            if (!Modifier.isAbstract(candidate.getModifiers()))
                continue;
            if (candidate.getSuperclass() != entityClass)
                continue;

            Class<?>[] interfaces = candidate.getInterfaces();
            if (interfaces.length != 1)
                continue;
            Class<?> projectileInterface = interfaces[0];
            put("IProjectile", projectileInterface);
            boolean hasWorldCtor = false;
            boolean hasWorldLivingCtor = false;
            boolean hasWorldXYZCtor = false;

            for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
                Class<?>[] p = ctor.getParameterTypes();
                if (p.length == 1 && p[0] == worldClass) {
                    hasWorldCtor = true;
                }

                if (p.length == 2 &&
                        p[0] == worldClass &&
                        p[1] == livingClass) {
                    hasWorldLivingCtor = true;
                }
                if (p.length == 4 &&
                        p[0] == worldClass &&
                        p[1] == double.class &&
                        p[2] == double.class &&
                        p[3] == double.class) {
                    hasWorldXYZCtor = true;
                }
            }

            if (hasWorldCtor && hasWorldLivingCtor && hasWorldXYZCtor) {
                put("EntityThrowable", candidate);
                return;
            }
        }
    }

    // From Line 27997
    public static void worldacces() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> blockPosClass = get("BlockPos");
        Class<?> entityPlayerClass = get("EntityPlayer");
        Class<?> entityClass = get("Entity");

        if (blockPosClass == null || entityPlayerClass == null || entityClass == null) {
            return;
        }

        for (Class<?> candidate : classes) {
            if (!candidate.isInterface()) {
                continue;
            }

            boolean hasRequiredMethod = false;
            int matchingMethods = 0;

            for (Method method : candidate.getDeclaredMethods()) {
                Class<?>[] paramTypes = method.getParameterTypes();
                if (paramTypes.length == 3 &&
                        paramTypes[0] == int.class &&
                        paramTypes[1] == blockPosClass &&
                        paramTypes[2] == int.class) {
                    matchingMethods++;
                    continue;
                }
                if (paramTypes.length == 4 &&
                        paramTypes[0] == entityPlayerClass &&
                        paramTypes[1] == int.class &&
                        paramTypes[2] == blockPosClass &&
                        paramTypes[3] == int.class) {
                    matchingMethods++;
                    continue;
                }
                if (paramTypes.length == 1 &&
                        paramTypes[0] == entityClass) {
                    matchingMethods++;
                    continue;
                }
                if (paramTypes.length == 2 &&
                        paramTypes[0] == String.class &&
                        paramTypes[1] == blockPosClass) {
                    matchingMethods++;
                    continue;
                }
                if (paramTypes.length == 9 &&
                        paramTypes[0] == int.class &&
                        paramTypes[1] == boolean.class &&
                        paramTypes[2] == double.class &&
                        paramTypes[3] == double.class &&
                        paramTypes[4] == double.class &&
                        paramTypes[5] == double.class &&
                        paramTypes[6] == double.class &&
                        paramTypes[7] == double.class &&
                        paramTypes[8] == int[].class) {
                    matchingMethods++;
                    continue;
                }
                if (paramTypes.length == 7 &&
                        paramTypes[0] == entityPlayerClass &&
                        paramTypes[1] == String.class &&
                        paramTypes[2] == double.class &&
                        paramTypes[3] == double.class &&
                        paramTypes[4] == double.class &&
                        paramTypes[5] == float.class &&
                        paramTypes[6] == float.class) {
                    matchingMethods++;
                    continue;
                }
                if (paramTypes.length == 6 &&
                        paramTypes[0] == int.class &&
                        paramTypes[1] == int.class &&
                        paramTypes[2] == int.class &&
                        paramTypes[3] == int.class &&
                        paramTypes[4] == int.class &&
                        paramTypes[5] == int.class) {
                    matchingMethods++;
                    continue;
                }
                if (paramTypes.length == 1 &&
                        paramTypes[0] == blockPosClass) {
                    matchingMethods++;
                    continue;
                }
                if (paramTypes.length == 3 &&
                        paramTypes[0] == int.class &&
                        paramTypes[1] == blockPosClass &&
                        paramTypes[2] == int.class) {
                    matchingMethods++;
                    continue;
                }
                if (paramTypes.length == 6 &&
                        paramTypes[0] == String.class &&
                        paramTypes[1] == double.class &&
                        paramTypes[2] == double.class &&
                        paramTypes[3] == double.class &&
                        paramTypes[4] == float.class &&
                        paramTypes[5] == float.class) {
                    matchingMethods++;
                    continue;
                }
            }
            if (matchingMethods >= 5) {
                hasRequiredMethod = true;
            }

            if (hasRequiredMethod) {
                put("IWorldAccess", candidate);

                break;
            }
        }
    }

    // From Line 28112
    public static void findrenderGlobal() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> iWorldAccess = get("IWorldAccess");
        Class<?> iResourceManagerReloadListener = get("IResourceManagerReloadListener");
        if (iWorldAccess == null || iResourceManagerReloadListener == null) {

            return;
        }
        for (Class<?> candidate : classes) {

            boolean implementsIWorldAccess = iWorldAccess.isAssignableFrom(candidate);
            boolean implementsIResourceManagerReloadListener = iResourceManagerReloadListener
                    .isAssignableFrom(candidate);

            if (!implementsIWorldAccess || !implementsIResourceManagerReloadListener) {
                continue;
            }

            put("RenderGlobal", candidate);
            return;

        }

    }

    // From Line 28137
    public static void findTimerClass() {
        Vector<Class<?>> classes = new Vector<>(getClasses());

        Class<?> timerContainerClass = get("TimerContainer");
        if (timerContainerClass == null) {
            return;
        }

        for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface() || candidate.isAnnotation()) {
                continue;
            }
            boolean hasTimerContainerField = false;
            String timerContainerFieldName = null;

            for (Field field : candidate.getDeclaredFields()) {
                Class<?> fieldType = field.getType();
                if (fieldType.getName().equals(timerContainerClass.getName())) {
                    hasTimerContainerField = true;
                    timerContainerFieldName = field.getName();

                    break;
                }
            }

            if (!hasTimerContainerField) {

                continue;
            }

            int constructorCount = 0;
            boolean hasSingleFloatConstructor = false;
            Constructor<?> targetConstructor = null;

            for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
                constructorCount++;
                Class<?>[] params = ctor.getParameterTypes();

                StringBuilder paramInfo = new StringBuilder();
                for (Class<?> param : params) {
                    paramInfo.append(param.getSimpleName()).append(", ");
                }
                if (paramInfo.length() > 0)
                    paramInfo.setLength(paramInfo.length() - 2);

                if (params.length == 1 && params[0] == float.class) {
                    hasSingleFloatConstructor = true;
                    targetConstructor = ctor;
                }
            }

            boolean allCriteriaMet = hasTimerContainerField &&
                    (constructorCount == 1) &&
                    hasSingleFloatConstructor;

            if (allCriteriaMet) {
                put("Timer", candidate);
                return;
            } else {
            }
        }

    }

    // From Line 28201
    public static void findEntityBoat() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        for (Class<?> candidate : classes) {
            if (candidate.getName().equals("crsecond.էҌ")) {
                put("teiste", candidate);

                return;
            }
        }
    }

    // From Line 28212
    public static void findTimerContainer() {
        Vector<Class<?>> classes = new Vector<>(getClasses());

        for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface() || candidate.isAnnotation()) {
                continue;
            }
            int nonStaticLongFieldCount = 0;
            int staticLongFieldCount = 0;
            int booleanFieldCount = 0;
            int totalLongFields = 0;

            for (Field field : candidate.getDeclaredFields()) {
                Class<?> fieldType = field.getType();
                boolean isStatic = java.lang.reflect.Modifier.isStatic(field.getModifiers());

                if (fieldType == long.class) {
                    totalLongFields++;
                    if (isStatic) {
                        staticLongFieldCount++;
                    } else {
                        nonStaticLongFieldCount++;
                    }

                }
                if (fieldType == boolean.class && !isStatic) {
                    booleanFieldCount++;

                }
            }

            boolean hasBooleanField = booleanFieldCount >= 1;

            int constructorCount = 0;
            boolean hasDoubleBooleanConstructor = false;
            boolean hasDoubleConstructor = false;

            for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
                constructorCount++;
                Class<?>[] params = ctor.getParameterTypes();

                StringBuilder paramInfo = new StringBuilder();
                for (Class<?> param : params) {
                    paramInfo.append(param.getSimpleName()).append(", ");
                }
                if (paramInfo.length() > 0)
                    paramInfo.setLength(paramInfo.length() - 2);

                if (params.length == 2) {
                    boolean hasDouble = false, hasBoolean = false;
                    for (Class<?> param : params) {
                        if (param == double.class)
                            hasDouble = true;
                        if (param == boolean.class)
                            hasBoolean = true;
                    }
                    if (hasDouble && hasBoolean) {
                        hasDoubleBooleanConstructor = true;

                    }
                }

                if (params.length == 1 && params[0] == double.class) {
                    hasDoubleConstructor = true;

                }
            }

            boolean foundTargetMethod = false;
            boolean hasHashCodeMethod = false;
            Method targetMethod = null;

            for (Method method : candidate.getDeclaredMethods()) {
                boolean isPublic = java.lang.reflect.Modifier.isPublic(method.getModifiers());
                Class<?> returnType = method.getReturnType();
                Class<?>[] paramTypes = method.getParameterTypes();
                if (method.getName().equals("hashCode") && returnType == int.class && paramTypes.length == 0) {
                    hasHashCodeMethod = true;
                    continue;
                }
                if (isPublic && returnType == void.class && paramTypes.length == 1 && paramTypes[0] == double.class) {
                    foundTargetMethod = true;
                    targetMethod = method;

                }
            }
            int staticFieldCount = 0;
            for (Field field : candidate.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    staticFieldCount++;
                }
            }
            boolean hasReasonableStaticFields = staticFieldCount <= 5;

            boolean allCriteriaMet = (totalLongFields >= 3) &&
                    hasBooleanField &&
                    (constructorCount == 2) &&
                    hasDoubleBooleanConstructor &&
                    hasDoubleConstructor &&
                    foundTargetMethod &&
                    hasHashCodeMethod &&
                    hasReasonableStaticFields;

            if (allCriteriaMet) {
                if (targetMethod != null) {
                    putMethod("TimerContainer.getValue", targetMethod);
                }
                put("TimerContainer", candidate);
                return;
            } else {
            }
        }

    }

    // From Line 28327
    public static void finds37andstatbase() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> packetIfc = get("Packet");
        Class<?> iNetHandlerPlayClient = get("INetHandlerPlayClient");
        Class<?> worldClass = get("World");
        Class<?> blockPosClass = get("BlockPos");
        Class<?> iChatComponentClass = get("IChatComponent");

        if (packetIfc == null || iNetHandlerPlayClient == null || worldClass == null ||
                blockPosClass == null || iChatComponentClass == null) {
            return;
        }

        for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface() || !packetIfc.isAssignableFrom(candidate)) {
                continue;
            }

            boolean implementsCorrectGeneric = false;

            java.lang.reflect.Type[] genericInterfaces = candidate.getGenericInterfaces();
            for (java.lang.reflect.Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericInterface;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayClient)) {
                            implementsCorrectGeneric = true;
                            break;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                java.lang.reflect.Type genericSuperclass = candidate.getGenericSuperclass();
                if (genericSuperclass instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericSuperclass;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayClient)) {
                            implementsCorrectGeneric = true;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                continue;
            }

            boolean hasNoArgConstructor = false;
            boolean hasMapConstructor = false;
            Class<?> statBaseClass = null;

            for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();

                if (params.length == 0) {
                    hasNoArgConstructor = true;
                }

                if (params.length == 1 && Map.class.isAssignableFrom(params[0])) {
                    java.lang.reflect.Type[] genericParameterTypes = ctor.getGenericParameterTypes();
                    if (genericParameterTypes.length > 0 &&
                            genericParameterTypes[0] instanceof ParameterizedType) {

                        ParameterizedType mapType = (ParameterizedType) genericParameterTypes[0];
                        java.lang.reflect.Type[] mapTypeArgs = mapType.getActualTypeArguments();

                        if (mapTypeArgs.length == 2) {
                            if (mapTypeArgs[0] instanceof Class &&
                                    mapTypeArgs[1] instanceof Class &&
                                    mapTypeArgs[1].equals(Integer.class)) {

                                statBaseClass = (Class<?>) mapTypeArgs[0];
                                hasMapConstructor = true;
                            }
                        }
                    }
                }
            }

            if (hasNoArgConstructor && hasMapConstructor && statBaseClass != null) {
                put("S37PacketStatistics", candidate);
                put("StatBase", statBaseClass);
                break;
            }
        }
    }

    // From Line 28424
    public static void finds02() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> packetIfc = get("Packet");
        Class<?> iNetHandlerPlayClient = get("INetHandlerPlayClient");
        Class<?> iChatComponentClass = get("IChatComponent");

        if (packetIfc == null || iNetHandlerPlayClient == null || iChatComponentClass == null) {
            return;
        }

        for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface() || !packetIfc.isAssignableFrom(candidate)) {
                continue;
            }

            boolean implementsCorrectGeneric = false;
            java.lang.reflect.Type[] genericInterfaces = candidate.getGenericInterfaces();
            for (java.lang.reflect.Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericInterface;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayClient)) {
                            implementsCorrectGeneric = true;
                            break;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                java.lang.reflect.Type genericSuperclass = candidate.getGenericSuperclass();
                if (genericSuperclass instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericSuperclass;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayClient)) {
                            implementsCorrectGeneric = true;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                continue;
            }

            boolean hasNoArgConstructor = false;
            boolean hasIChatComponentConstructor = false;
            boolean hasIChatComponentByteConstructor = false;

            for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 0) {
                    hasNoArgConstructor = true;
                }
                if (params.length == 1 && params[0] == iChatComponentClass) {
                    hasIChatComponentConstructor = true;
                }
                if (params.length == 2 &&
                        params[0] == iChatComponentClass &&
                        params[1] == byte.class) {
                    hasIChatComponentByteConstructor = true;
                }
            }

            if (hasNoArgConstructor && hasIChatComponentConstructor && hasIChatComponentByteConstructor) {
                put("S02PacketChat", candidate);

                findAndPutChatComponentMethod(candidate, iChatComponentClass);
                break;
            }
        }
    }

    // From Line 28505
    private static void findAndPutChatComponentMethod(Class<?> packetClass, Class<?> iChatComponentClass) {
        for (Method method : packetClass.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(method.getModifiers()) &&
                    method.getReturnType() == iChatComponentClass &&
                    method.getParameterCount() == 0) {

                putMethod("S02PacketChat.getChatComponent", method);
                return;
            }
        }

        for (Method method : packetClass.getDeclaredMethods()) {
            if (method.getReturnType() == iChatComponentClass &&
                    method.getParameterCount() == 0) {

                putMethod("S02PacketChat.getChatComponent", method);
                return;
            }
        }

    }

    // From Line 28527
    public static void findResourcePackStatusPacket() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> packetIfc = get("Packet");
        Class<?> iNetHandlerPlayServer = get("INetHandlerPlayServer");
        Class<?> stringClass = String.class;

        if (packetIfc == null || iNetHandlerPlayServer == null) {
            return;
        }

        for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface() || !packetIfc.isAssignableFrom(candidate)) {
                continue;
            }

            boolean implementsCorrectGeneric = false;

            java.lang.reflect.Type[] genericInterfaces = candidate.getGenericInterfaces();
            for (java.lang.reflect.Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericInterface;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayServer)) {
                            implementsCorrectGeneric = true;
                            break;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                java.lang.reflect.Type genericSuperclass = candidate.getGenericSuperclass();
                if (genericSuperclass instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericSuperclass;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayServer)) {
                            implementsCorrectGeneric = true;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                continue;
            }

            boolean hasNoArgConstructor = false;
            boolean hasStringAndEnumConstructor = false;
            Class<?> actionEnumClass = null;

            for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();

                if (params.length == 0) {
                    hasNoArgConstructor = true;
                }

                if (params.length == 2) {
                    if (params[0] == stringClass && params[1].isEnum()) {
                        actionEnumClass = params[1];
                        Class<?> foundEnumClass = findClassUsingFieldNames("SUCCESSFULLY_LOADED", "DECLINED",
                                "FAILED_DOWNLOAD", "ACCEPTED");
                        if (foundEnumClass != null && foundEnumClass.equals(actionEnumClass)) {
                            hasStringAndEnumConstructor = true;
                        }
                    }
                }
            }

            if (hasNoArgConstructor && hasStringAndEnumConstructor && actionEnumClass != null) {
                put("C19PacketResourcePackStatus", candidate);
                put("C19PacketResourcePackStatus.Action", actionEnumClass);
                break;
            }
        }
    }

    // From Line 28612
    public static void findClickWindowPacket() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> packetIfc = get("Packet");
        Class<?> iNetHandlerPlayServer = get("INetHandlerPlayServer");
        Class<?> itemStackClass = get("ItemStack");
        Class<?> intClass = Integer.TYPE;
        Class<?> shortClass = Short.TYPE;

        if (packetIfc == null || iNetHandlerPlayServer == null || itemStackClass == null) {

            return;
        }

        for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface() || !packetIfc.isAssignableFrom(candidate)) {
                continue;
            }

            boolean implementsCorrectGeneric = false;

            java.lang.reflect.Type[] genericInterfaces = candidate.getGenericInterfaces();
            for (java.lang.reflect.Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericInterface;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayServer)) {
                            implementsCorrectGeneric = true;
                            break;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                java.lang.reflect.Type genericSuperclass = candidate.getGenericSuperclass();
                if (genericSuperclass instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericSuperclass;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayServer)) {
                            implementsCorrectGeneric = true;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                continue;
            }

            boolean hasNoArgConstructor = false;
            boolean hasComplexConstructor = false;

            for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();

                if (params.length == 0) {
                    hasNoArgConstructor = true;
                }

                if (params.length == 6) {
                    if (params[0] == intClass &&
                            params[1] == intClass &&
                            params[2] == intClass &&
                            params[3] == intClass &&
                            params[4] == itemStackClass &&
                            params[5] == shortClass) {
                        hasComplexConstructor = true;
                    }
                }
            }
            if (hasNoArgConstructor && hasComplexConstructor) {
                put("C0EPacketClickWindow", candidate);
                break;
            }
        }
    }

    // From Line 28698
    public static void findC10PacketCreativeInventoryAction() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> packetIfc = get("Packet");
        Class<?> iNetHandlerPlayServer = get("INetHandlerPlayServer");
        Class<?> itemStackClass = get("ItemStack");

        if (packetIfc == null || iNetHandlerPlayServer == null || itemStackClass == null) {
            System.out.println("[C10] Gerekli classlar bulunamadı!");
            return;
        }

        for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface() || !packetIfc.isAssignableFrom(candidate)) {
                continue;
            }

            boolean implementsCorrectGeneric = false;
            java.lang.reflect.Type[] genericInterfaces = candidate.getGenericInterfaces();
            for (java.lang.reflect.Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericInterface;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayServer)) {
                            implementsCorrectGeneric = true;
                            break;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                java.lang.reflect.Type genericSuperclass = candidate.getGenericSuperclass();
                if (genericSuperclass instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericSuperclass;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayServer)) {
                            implementsCorrectGeneric = true;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) continue;

            boolean hasNoArgCtor = false;
            boolean hasC10Ctor = false;
            boolean hasSixParamCtor = false;

            for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 0) hasNoArgCtor = true;
                if (params.length == 2 && params[0] == int.class && params[1] == itemStackClass) hasC10Ctor = true;
                if (params.length == 6) hasSixParamCtor = true;
            }

            if (hasNoArgCtor && hasC10Ctor && !hasSixParamCtor) {
                Class<?> c0e = get("C0EPacketClickWindow");
                if (c0e != null && c0e.equals(candidate)) continue;
                put("C10PacketCreativeInventoryAction", candidate);
                System.out.println("[C10] C10PacketCreativeInventoryAction bulundu: " + candidate.getName());
                break;
            }
        }
    }

    // From Line 28771
    public static void findUpdateSignPacket() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> packetIfc = get("Packet");
        Class<?> iNetHandlerPlayServer = get("INetHandlerPlayServer");
        Class<?> blockPosClass = get("BlockPos");
        Class<?> iChatComponentClass = get("IChatComponent");

        if (packetIfc == null || iNetHandlerPlayServer == null || blockPosClass == null
                || iChatComponentClass == null) {

            return;
        }

        for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface() || !packetIfc.isAssignableFrom(candidate)) {
                continue;
            }

            boolean implementsCorrectGeneric = false;

            java.lang.reflect.Type[] genericInterfaces = candidate.getGenericInterfaces();
            for (java.lang.reflect.Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericInterface;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayServer)) {
                            implementsCorrectGeneric = true;
                            break;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                java.lang.reflect.Type genericSuperclass = candidate.getGenericSuperclass();
                if (genericSuperclass instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericSuperclass;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayServer)) {
                            implementsCorrectGeneric = true;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                continue;
            }

            boolean hasNoArgConstructor = false;
            boolean hasBlockPosAndIChatComponentConstructor = false;

            for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();

                if (params.length == 0) {
                    hasNoArgConstructor = true;
                }

                if (params.length == 2) {
                    if (params[0] == blockPosClass &&
                            params[1].isArray() &&
                            params[1].getComponentType() == iChatComponentClass) {
                        hasBlockPosAndIChatComponentConstructor = true;
                    }
                }
            }

            if (hasNoArgConstructor && hasBlockPosAndIChatComponentConstructor) {
                Field blockPosField = null;
                Field chatComponentsField = null;

                for (Field field : candidate.getDeclaredFields()) {
                    if (field.getType() == blockPosClass) {
                        blockPosField = field;
                    } else if (field.getType().isArray() &&
                            field.getType().getComponentType() == iChatComponentClass) {
                        chatComponentsField = field;
                    }
                }

                if (blockPosField != null && chatComponentsField != null) {
                    put("C12PacketUpdateSign", candidate);
                    break;
                }
            }
        }
    }

    // From Line 28868
    public static void processPacketClass() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> packetIfc = get("Packet");
        Class<?> packetBufferClass = get("PacketBuffer");

        if (packetIfc == null || packetBufferClass == null) {
            return;
        }

        outer: for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface() || candidate.getSuperclass() != Object.class)
                continue;

            boolean implementsPacket = false;
            for (Class<?> iface : candidate.getInterfaces()) {
                if (packetIfc.isAssignableFrom(iface)) {
                    implementsPacket = true;
                    break;
                }
            }
            if (!implementsPacket)
                continue;

            Field[] fields = candidate.getDeclaredFields();
            int stringFieldCount = 0;
            boolean hasRemove = false;
            for (Field f : fields) {
                f.setAccessible(true);
                if (f.getName().toUpperCase().contains("REMOVE")) {
                    hasRemove = true;
                    break;
                }
                if (f.getType() == String.class)
                    stringFieldCount++;
            }
            if (hasRemove || stringFieldCount != 1)
                continue;

            boolean hasNoArgCtor = false;
            boolean hasStringCtor = false;
            for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 0)
                    hasNoArgCtor = true;
                if (params.length == 1 && params[0] == String.class)
                    hasStringCtor = true;
            }
            if (!hasNoArgCtor || !hasStringCtor)
                continue;

            Method[] methods = candidate.getDeclaredMethods();
            int packetBufferMethodCount = 0;
            for (Method m : methods) {
                if (m.getName().toUpperCase().contains("REMOVE")) {
                    hasRemove = true;
                    break outer;
                }
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 1 && params[0] == packetBufferClass)
                    packetBufferMethodCount++;
            }
            if (packetBufferMethodCount < 2)
                continue;

            put("C01PacketChatMessage", candidate);

            findAndSaveINetHandlerPlayServer(candidate);

            Method getMessageMethod = null;

            for (Method m : candidate.getDeclaredMethods()) {
                if (Modifier.isPublic(m.getModifiers())
                        && m.getReturnType() == String.class
                        && m.getParameterCount() == 0) {

                    getMessageMethod = m;
                    putMethod("C01PacketChatMessage.getMessage", m);
                    break;
                }
            }

            if (getMessageMethod != null) {
                Field messageField = null;

                for (Field f : candidate.getDeclaredFields()) {
                    if (f.getType() != String.class)
                        continue;

                    f.setAccessible(true);

                    try {
                        Object instance = candidate.getDeclaredConstructor().newInstance();
                        Object fieldValue = f.get(instance);
                        Object methodValue = getMessageMethod.invoke(instance);

                        
                        if (fieldValue == null && methodValue == null) {
                            messageField = f;
                            break;
                        }
                    } catch (Throwable ignored) {
                    }
                }

                
                if (messageField == null) {
                    for (Field f : candidate.getDeclaredFields()) {
                        if (f.getType() == String.class) {
                            messageField = f;
                            break;
                        }
                    }
                }

                if (messageField != null) {
                    putField("C01PacketChatMessage.message", messageField);
                }
            }
            break;
        }
    }

    // From Line 28990
    private static void findAndSaveINetHandlerPlayServer(Class<?> packetClass) {
        Class<?> packetInterface = get("Packet");
        if (packetInterface == null) {
            System.out.println("Packet interface'i bulunamadı");
            return;
        }

        java.lang.reflect.Type[] genericInterfaces = packetClass.getGenericInterfaces();
        for (java.lang.reflect.Type genericInterface : genericInterfaces) {

            if (genericInterface instanceof java.lang.reflect.ParameterizedType) {
                java.lang.reflect.ParameterizedType paramType = (java.lang.reflect.ParameterizedType) genericInterface;
                java.lang.reflect.Type rawType = paramType.getRawType();

                ;

                if (rawType.equals(packetInterface)) {
                    java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();

                    if (typeArgs.length > 0) {
                        java.lang.reflect.Type firstArg = typeArgs[0];

                        if (extractAndSaveHandlerClass(firstArg)) {
                            return;
                        }
                    }
                }
            }
        }

        java.lang.reflect.Type genericSuperclass = packetClass.getGenericSuperclass();
        if (genericSuperclass instanceof java.lang.reflect.ParameterizedType) {
            java.lang.reflect.ParameterizedType paramType = (java.lang.reflect.ParameterizedType) genericSuperclass;
            java.lang.reflect.Type rawType = paramType.getRawType();

            if (rawType instanceof Class && packetInterface.isAssignableFrom((Class<?>) rawType)) {
                java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                if (typeArgs.length > 0) {
                    java.lang.reflect.Type firstArg = typeArgs[0];
                    extractAndSaveHandlerClass(firstArg);
                }
            }
        }
    }

    // From Line 29313
    public static void findMotionFields(byte[] classBytes) {
        try {
            if (missingClassBytes("findMotionFields", classBytes))
                return;

            ClassReader cr = new ClassReader(classBytes);
            ClassNode classNode = new ClassNode();
            cr.accept(classNode, 0);

            Class<?> entityClass = get("Entity");
            if (entityClass == null)
                return;

            String entityInternalName = org.objectweb.asm.Type.getInternalName(entityClass);

            for (MethodNode method : classNode.methods) {
                if (!method.name.equals("<init>"))
                    continue;

                List<AbstractInsnNode> insns = Arrays.asList(method.instructions.toArray());

                int motionFieldCount = 0;

                for (int i = 0; i < insns.size(); i++) {
                    AbstractInsnNode insn = insns.get(i);

                    if (insn.getOpcode() == Opcodes.GETFIELD && insn instanceof FieldInsnNode) {
                        FieldInsnNode fin = (FieldInsnNode) insn;

                        if (!fin.owner.equals(entityInternalName))
                            continue;

                        Field f = findField(entityClass, fin.name, fin.desc);
                        if (f == null)
                            continue;

                        if (i + 1 < insns.size()) {
                            AbstractInsnNode nextInsn = insns.get(i + 1);

                            if (nextInsn.getOpcode() == INVOKEVIRTUAL && nextInsn instanceof MethodInsnNode) {
                                MethodInsnNode min = (MethodInsnNode) nextInsn;

                                if ("()D".equals(min.desc)) {
                                    put("MotionContainer", f.getType());

                                    switch (motionFieldCount) {
                                        case 0:
                                            putField("Entity.motionX", f);

                                            break;
                                        case 1:
                                            putField("Entity.motionY", f);
                                            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
                                            unsafeField.setAccessible(true);
                                            Unsafe unsafe = (Unsafe) unsafeField.get(null);

                                            Field yawField = f;
                                            yawField.setAccessible(true);

                                            long offset = unsafe.objectFieldOffset(yawField);

                                            addChatMessage(
                                                    String.format("motionY offset: 0x%X", offset));

                                            break;
                                        case 2:
                                            putField("Entity.motionZ", f);

                                            break;
                                    }

                                    Method m = f.getType().getDeclaredMethod(min.name);
                                    if (m.getReturnType() == double.class) {
                                        putMethod("MotionContainer.getDoubleValue", m);
                                    }

                                    motionFieldCount++;
                                    if (motionFieldCount >= 3)
                                        return;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {

        }
    }

    // From Line 29400
    public static Field findField(Class<?> cls, String name, String descriptor) {
        for (Field field : cls.getDeclaredFields()) {
            if (field.getName().equals(name)) {
                if (getDescriptor(field).equals(descriptor)) {
                    return field;
                }
            }
        }
        return null;
    }

    // From Line 29436
    public static Method findSpecificMethod(Class<?> clazz, Class<?> param) {
        if (clazz == null || param == null) {
            return null;
        }
        for (Method method : clazz.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()))
                continue;

            if (!method.getReturnType().equals(void.class))
                continue;

            Class<?>[] params = method.getParameterTypes();
            if (params.length != 1)
                continue;

            if (!params[0].equals(param))
                continue;

            return method;
        }
        return null;
    }

    // From Line 29456
    public static Field getFieldByType(Class<?> clazz, Class<?> type) {
        if (clazz == null || type == null) {
            return null;
        }
        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
            if (field.getType() == type) {
                return field;
            }
        }
        return null;
    }

    // From Line 29466
    public static Field findListUsingParam(Class<?> cls, Class<?> listType) {
        if (cls == null || listType == null) {
            return null;
        }
        Field[] fields = cls.getFields();
        for (Field field : fields) {
            if (List.class.isAssignableFrom(field.getType())) {
                java.lang.reflect.Type fieldType = field.getGenericType();
                if (fieldType instanceof ParameterizedType) {
                    ParameterizedType pType = (ParameterizedType) fieldType;
                    java.lang.reflect.Type[] typeArguments = pType.getActualTypeArguments();
                    if (typeArguments.length == 1) {
                        if (typeArguments[0].equals(listType)) {
                            return field;
                        }
                    }
                }
            }
        }
        return null;
    }

    // From Line 29485
    public static Class<?> findClassUsingFieldNames(String... names) {
        List<Class<?>> loadedClasses = getClasses();
        for (Class<?> clazz : loadedClasses) {
            Set<String> fieldNamesSet = new HashSet<>();
            for (Field field : clazz.getDeclaredFields()) {
                fieldNamesSet.add(field.getName());
            }
            boolean allMatch = true;
            for (String name : names) {
                if (!fieldNamesSet.contains(name)) {
                    allMatch = false;
                    break;
                }
            }
            if (allMatch) {
                return clazz;
            }
        }
        return null;
    }

    // From Line 29506
    public static void findClientStatusPacket() {
        Class<?> statusEnumClass = findClassUsingFieldNames("PERFORM_RESPAWN", "REQUEST_STATS",
                "OPEN_INVENTORY_ACHIEVEMENT");

        if (statusEnumClass == null || !statusEnumClass.isEnum()) {

            return;
        }

        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> packetIfc = get("Packet");
        Class<?> iNetHandlerPlayServer = get("INetHandlerPlayServer");

        if (packetIfc == null || iNetHandlerPlayServer == null) {

            return;
        }

        for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface() || !packetIfc.isAssignableFrom(candidate)) {
                continue;
            }

            boolean implementsCorrectGeneric = false;

            java.lang.reflect.Type[] genericInterfaces = candidate.getGenericInterfaces();
            for (java.lang.reflect.Type genericInterface : genericInterfaces) {
                if (genericInterface instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericInterface;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayServer)) {
                            implementsCorrectGeneric = true;
                            break;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                java.lang.reflect.Type genericSuperclass = candidate.getGenericSuperclass();
                if (genericSuperclass instanceof ParameterizedType) {
                    ParameterizedType paramType = (ParameterizedType) genericSuperclass;
                    java.lang.reflect.Type rawType = paramType.getRawType();
                    if (rawType instanceof Class && ((Class<?>) rawType).equals(packetIfc)) {
                        java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                        if (typeArgs.length > 0 &&
                                typeArgs[0] instanceof Class &&
                                ((Class<?>) typeArgs[0]).equals(iNetHandlerPlayServer)) {
                            implementsCorrectGeneric = true;
                        }
                    }
                }
            }

            if (!implementsCorrectGeneric) {
                continue;
            }

            boolean hasNoArgConstructor = false;
            boolean hasEnumConstructor = false;

            for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();

                if (params.length == 0) {
                    hasNoArgConstructor = true;
                }

                if (params.length == 1 && params[0] == statusEnumClass) {
                    hasEnumConstructor = true;
                }
            }

            if (hasNoArgConstructor && hasEnumConstructor) {
                put("C16PacketClientStatus", candidate);
                put("C16PacketClientStatus.EnumState", statusEnumClass);

                break;
            }
        }
    }

    // From Line 29592
    public static void findPlayerPosLookPacket() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> packetIfc = get("Packet");
        Class<?> iNetHandlerPlayClient = get("INetHandlerPlayClient");

        if (packetIfc == null || iNetHandlerPlayClient == null) {
            System.out.println("Packet veya INetHandlerPlayClient interface'i bulunamadı");
            return;
        }

        for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface())
                continue;
            boolean implementsCorrectInterface = false;
            java.lang.reflect.Type[] genericInterfaces = candidate.getGenericInterfaces();

            for (java.lang.reflect.Type type : genericInterfaces) {
                if (type instanceof java.lang.reflect.ParameterizedType) {
                    java.lang.reflect.ParameterizedType pt = (java.lang.reflect.ParameterizedType) type;
                    if (pt.getRawType() == packetIfc) {
                        java.lang.reflect.Type[] typeArgs = pt.getActualTypeArguments();
                        if (typeArgs.length == 1 && typeArgs[0] == iNetHandlerPlayClient) {
                            implementsCorrectInterface = true;
                            break;
                        }
                    }
                }
            }

            if (!implementsCorrectInterface)
                continue;
            Class<?>[] declaredClasses = candidate.getDeclaredClasses();
            Class<?> targetInnerClass = null;

            for (Class<?> innerClass : declaredClasses) {
                if (innerClass.isEnum()) {
                    targetInnerClass = innerClass;
                    break;
                }
            }

            if (targetInnerClass == null)
                continue;
            boolean hasCorrectConstructor = false;
            try {
                Constructor<?>[] constructors = candidate.getDeclaredConstructors();

                for (Constructor<?> constructor : constructors) {
                    Class<?>[] paramTypes = constructor.getParameterTypes();

                    if (paramTypes.length == 6) {
                        if (paramTypes[0] == double.class &&
                                paramTypes[1] == double.class &&
                                paramTypes[2] == double.class &&
                                paramTypes[3] == float.class &&
                                paramTypes[4] == float.class &&
                                Set.class.isAssignableFrom(paramTypes[5])) {

                            hasCorrectConstructor = true;
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                continue;
            }

            if (hasCorrectConstructor) {
                put("S08PacketPlayerPosLook", candidate);
                put("S08PacketPlayerPosLook.EnumFlags", targetInnerClass);
                return;
            }
        }

    }

    // From Line 29668
    public static Class<?> findClassByName(String className) {
        if (className == null) {
            return null;
        }
        List<Class<?>> loadedClasses = getClasses();
        for (Class<?> clazz : loadedClasses) {
            if (clazz.getName().equals(className)) {
                return clazz;
            }
        }
        return null;
    }

    // From Line 29678
    public static String getOuterClassName(String className) {
        if (className == null) {
            return null;
        }
        int innerClassDelimiter = className.indexOf('$');
        if (innerClassDelimiter != -1) {
            return className.substring(0, innerClassDelimiter);
        } else {
            return className;
        }
    }

    // From Line 29687
    public static Method[] getAllMethods(Class<?> clazz) {
        ArrayList<Method> methods = new ArrayList<>();
        if (clazz == null) {
            return new Method[0];
        }
        for (Method method : clazz.getDeclaredMethods()) {
            block: {
                if (clazz.getSuperclass() != null && !clazz.getSuperclass().getName().equals("java.lang.Object")) {
                    for (Method declaredMethod : clazz.getSuperclass().getDeclaredMethods()) {
                        if (method.toString().equals(declaredMethod.toString()))
                            break block;
                    }
                }
                methods.add(method);
            }
        }
        return methods.toArray(new Method[0]);
    }

    // From Line 29732
    public static void findJumpOpcodeBased() {
        try {
            Class<?> clazz = get("EntityLivingBase");
            byte[] bytes = getClassBytes(clazz);
            if (missingClassBytes("findJumpOpcodeBased", clazz, bytes))
                return;

            ClassReader cr = new ClassReader(bytes);
            ClassNode cn = new ClassNode();
            cr.accept(cn, ClassReader.SKIP_FRAMES);

            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals("Ҁ")
                        && m.getReturnType() == void.class
                        && m.getParameterCount() == 0) {

                    m.setAccessible(true);
                    putMethod("EntityLivingBase.jump", m);
                    return;
                }
            }

        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    // From Line 29822
    public static boolean isPlayerControllerMP(Class<?> clazz) {
        Class<?> minecraftClass = get("Minecraft");
        Class<?> netHandlerClass = get("NetHandlerPlayClient");

        Constructor<?>[] constructors = clazz.getDeclaredConstructors();
        if (constructors.length != 1)
            return false;

        Constructor<?> constructor = constructors[0];
        Class<?>[] params = constructor.getParameterTypes();
        if (params.length != 2)
            return false;
        if (!params[0].equals(minecraftClass))
            return false;
        if (!params[1].equals(netHandlerClass))
            return false;

        boolean hasFinalMinecraft = false;
        boolean hasPublicNetHandler = false;
        boolean hasPrivateBoolean = false;

        for (Field field : clazz.getDeclaredFields()) {
            int mods = field.getModifiers();
            Class<?> type = field.getType();

            if (Modifier.isPrivate(mods) && Modifier.isFinal(mods) && type.equals(minecraftClass)) {
                hasFinalMinecraft = true;
            }

            if (Modifier.isPublic(mods) && !Modifier.isFinal(mods) && type.equals(netHandlerClass)) {
                hasPublicNetHandler = true;
            }

            if (Modifier.isPrivate(mods) && type.equals(boolean.class)) {
                hasPrivateBoolean = true;
            }
        }

        return hasFinalMinecraft && hasPublicNetHandler && hasPrivateBoolean;
    }

    // From Line 29880
    public static Class<?> findS12PacketVelocity() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> packet = get("Packet");
        Class<?> entity = get("Entity");

        for (Class<?> clazz : classes) {
            if (packet.isAssignableFrom(clazz)) {
                Constructor<?>[] constructors = clazz.getDeclaredConstructors();
                if (constructors.length < 3)
                    continue;

                for (Constructor<?> constructor : constructors) {
                    Class<?>[] parameterTypes = constructor.getParameterTypes();
                    if (parameterTypes.length == 4 &&
                            parameterTypes[0] == int.class &&
                            parameterTypes[1] == double.class &&
                            parameterTypes[2] == double.class &&
                            parameterTypes[3] == double.class) {
                        return clazz;
                    }
                }
            }
        }
        return null;
    }

    // From Line 29906
    public static void findMathHelper() {
        List<Class<?>> loadedClasses = getClasses();

        Set<Object> targetValues = new HashSet<>();
        Collections.addAll(targetValues, 11.377778f, 651.8986f, 1.5707964f, 0.7853981633974483);

        for (Class<?> clazz : loadedClasses) {
            int matchCount = 0;

            for (Field field : clazz.getDeclaredFields()) {
                try {
                    if (isStaticFinal(field)) {
                        field.setAccessible(true);
                        Object value = field.get(null);

                        if (targetValues.contains(value)) {
                            matchCount++;

                            if (matchCount >= 3) {
                                put("MathHelper", clazz);

                                return;
                            }
                        }
                    }
                } catch (Exception e) {
                }
            }
        }

    }

    // From Line 29938
    private static boolean isStaticFinal(Field field) {
        int modifiers = field.getModifiers();
        return java.lang.reflect.Modifier.isStatic(modifiers) &&
                java.lang.reflect.Modifier.isFinal(modifiers);
    }

    // From Line 29944
    public static void processItemClass() {
        Vector<Class<?>> classes = new Vector<>(getClasses());

        Class<?>[] requiredClasses = new Class<?>[] {
                get("Block"), get("MovingObjectPosition"), get("EnumFacing"), get("EntityPlayer"),
                get("Gui"), get("Entity"), get("EntityLivingBase"), get("ItemStack"),
                get("Vec3"), get("World"), get("ResourceLocation"), get("BlockPos")
        };

        Class<?>[] extraClasses = new Class<?>[] {
                List.class, Map.class, HashMap.class, HashSet.class, ArrayList.class,
                Random.class, UUID.class,
                com.google.common.base.Function.class,
                com.google.common.collect.HashMultimap.class,
                com.google.common.collect.Maps.class,
                com.google.common.collect.Multimap.class
        };

        Set<Class<?>> requiredSet = Sets.newHashSet(requiredClasses);
        Set<Class<?>> extraSet = Sets.newHashSet(extraClasses);

        for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface() || candidate.getSuperclass() != Object.class)
                continue;

            boolean has64 = false;
            for (Field f : candidate.getDeclaredFields()) {
                f.setAccessible(true);
                int mods = f.getModifiers();
                try {
                    if (Modifier.isProtected(mods) && !Modifier.isStatic(mods) && f.getType() == int.class) {
                        int value = f.getInt(candidate.newInstance());
                        if (value == 64 && !f.getName().equals("MASK_POTION_EXTENDED")) {
                            has64 = true;
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            if (!has64)
                continue;

            boolean usedRequiredOrExtra = false;
            outer: for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
                for (Class<?> param : ctor.getParameterTypes()) {
                    if (requiredSet.contains(param) || extraSet.contains(param)) {
                        usedRequiredOrExtra = true;
                        break outer;
                    }
                }
            }

            if (!usedRequiredOrExtra) {
                outer: for (Method m : candidate.getDeclaredMethods()) {
                    if (requiredSet.contains(m.getReturnType()) || extraSet.contains(m.getReturnType())) {
                        usedRequiredOrExtra = true;
                        break;
                    }
                    for (Class<?> param : m.getParameterTypes()) {
                        if (requiredSet.contains(param) || extraSet.contains(param)) {
                            usedRequiredOrExtra = true;
                            break outer;
                        }
                    }
                }
            }

            if (usedRequiredOrExtra) {
                put("Item", candidate);

                break;
            }
        }
    }

    // From Line 30261
    public static void findModelManager() {
        Class<?> renderItemClass = get("RenderItem");
        if (renderItemClass == null) {
            return;
        }

        Class<?> modelManagerClass = null;
        for (Constructor<?> constructor : renderItemClass.getDeclaredConstructors()) {
            Class<?>[] paramTypes = constructor.getParameterTypes();
            if (paramTypes.length == 2) {
                Class<?> textureManagerClass = get("TextureManager");
                if (textureManagerClass != null && paramTypes[0].equals(textureManagerClass)) {
                    modelManagerClass = paramTypes[1];

                    put("ModelManager", modelManagerClass);
                    break;
                }
            }
        }
        if (modelManagerClass == null) {
            return;
        }
        Class<?>[] interfaces = modelManagerClass.getInterfaces();
        if (interfaces.length > 0) {
            Class<?> interfaceClass = interfaces[0];
            put("IResourceManagerReloadListener", interfaceClass);
        }
        for (Constructor<?> constructor : modelManagerClass.getDeclaredConstructors()) {
            Class<?>[] paramTypes = constructor.getParameterTypes();
            if (paramTypes.length == 1) {
                Class<?> textureMapClass = paramTypes[0];

                put("TextureMap", textureMapClass);
                return;
            }
        }
    }

    // From Line 30299
    public static void findallmobs() {
        Class<?> renderManagerClass = get("RenderManager");
        Constructor<?>[] constructors = renderManagerClass.getDeclaredConstructors();
        for (Constructor<?> constructor : constructors) {
            Class<?>[] paramTypes = constructor.getParameterTypes();
            if (paramTypes.length == 2) {
                String renderItemClassName = paramTypes[1].getSimpleName();

                put("RenderItem", paramTypes[1]);
                break;
            }
        }
        Field entityRenderMapField = null;
        Field[] fields = renderManagerClass.getDeclaredFields();
        for (Field field : fields) {
            if (java.util.Map.class.isAssignableFrom(field.getType())) {

                entityRenderMapField = field;
                break;
            }
        }
    }

    // From Line 30322
    public static void processModelBiped() {
        Class<?> modelBipedClass = findClassUsingFieldNames(
                "bipedHead", "bipedBody", "bipedRightArm", "bipedLeftArm", "bipedRightLeg", "bipedLeftLeg");

        if (modelBipedClass != null) {
            put("ModelBiped", modelBipedClass);

            Class<?> superClass = modelBipedClass.getSuperclass();
            if (superClass != null && !superClass.equals(Object.class)) {
                put("ModelBase", superClass);
                resolveModelBaseMethods(superClass);

            } else {
            }

            boolean modelRendererFound = false;
            for (Field field : modelBipedClass.getDeclaredFields()) {
                if (field.getName().equals("bipedHead")) {
                    Class<?> fieldType = field.getType();
                    if (get("ModelRenderer") == null) {
                        put("ModelRenderer", fieldType);
                        resolveModelRendererMethods(fieldType);
                    }
                    putField("ModelBiped." + field.getName(), field);

                    modelRendererFound = true;
                    break;
                }
            }

            if (!modelRendererFound) {
                for (Field field : modelBipedClass.getDeclaredFields()) {
                    if (field.getName().startsWith("biped")) {
                        Class<?> fieldType = field.getType();
                        if (!fieldType.isPrimitive()) {
                            if (get("ModelRenderer") == null) {
                                put("ModelRenderer", fieldType);
                                resolveModelRendererMethods(fieldType);
                            }
                            putField("ModelBiped." + field.getName(), field);
                            break;
                        }
                    }
                }
            }

        } else {
        }
    }

    // From Line 30372
    public static void resolveModelBaseMethods(Class<?> modelBaseClass) {

        Class<?> ENTITY = get("Entity");
        Class<?> ENTITY_LIVING = get("EntityLivingBase");
        Class<?> MODEL_RENDERER = get("ModelRenderer");
        Class<?> MODEL_BASE = modelBaseClass;
        Class<?> RANDOM = Random.class;
        Class<?> STRING = String.class;
        for (Method m : modelBaseClass.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (matchParams(m, ENTITY, float.class, float.class, float.class,
                    float.class, float.class, float.class)) {
                putMethod("ModelBase.render", m);
                continue;
            }
            if (matchParams(m, float.class, float.class, float.class,
                    float.class, float.class, float.class, ENTITY)) {
                putMethod("ModelBase.setRotationAngles", m);
                continue;
            }
            if (matchParams(m, ENTITY_LIVING, float.class, float.class, float.class)) {
                putMethod("ModelBase.setLivingAnimations", m);
                continue;
            }
            if (matchParams(m, RANDOM)) {
                putMethod("ModelBase.getRandomModelBox", m);
                continue;
            }
            if (matchParams(m, STRING, int.class, int.class)) {
                putMethod("ModelBase.setTextureOffset", m);
                continue;
            }

            if (matchParams(m, STRING)) {

                Class<?> returnType = m.getReturnType();
                if (returnType != null && !returnType.isPrimitive()) {
                    put("TextureOffset", returnType);
                }

                putMethod("ModelBase.getTextureOffset", m);
                continue;
            }
            if (matchParams(m, MODEL_RENDERER, MODEL_RENDERER)) {
                putMethod("ModelBase.copyModelAngles", m);
                continue;
            }
            if (matchParams(m, MODEL_BASE)) {
                putMethod("ModelBase.setModelAttributes", m);
                continue;
            }
        }
    }

    // From Line 30944
    public static void resolveModelRendererMethods(Class<?> modelRendererClass) {

        Class<?> STRING = String.class;
        Class<?> MODEL_RENDERER = get("ModelRenderer");
        for (Method m : modelRendererClass.getDeclaredMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (matchParams(m,
                    STRING, float.class, float.class, float.class,
                    int.class, int.class, int.class)) {

                Class<?> returnType = m.getReturnType();
                if (returnType != null && !returnType.isPrimitive() && get("ModelRenderer") == null) {
                    put("ModelRenderer", returnType);
                }

                putMethod("ModelRenderer.addBox", m);
                continue;
            }
            if (matchParams(m, float.class, float.class, float.class)) {
                putMethod("ModelRenderer.setRotationPoint", m);
                continue;
            }
            if (matchParams(m, MODEL_RENDERER)) {
                putMethod("ModelRenderer.addChild", m);
                continue;
            }
            if (matchParams(m, int.class, int.class)) {
                putMethod("ModelRenderer.setTextureOffset", m);
                continue;
            }
            if (m.getName().equals("Ș")) {
                putMethod("ModelRenderer.render", m);
            }

        }
    }

    // From Line 30993
    public static void findglstatemanager(int minInnerClassCount) {
        List<Class<?>> loadedClasses = getClasses();
        for (Class<?> clazz : loadedClasses) {
            if (clazz.getDeclaredClasses().length < minInnerClassCount)
                continue;

            put("GlStateManager", clazz);

            try {
                byte[] bytes = getClassBytes(clazz);
                if (bytes == null)
                    return;

                ClassReader cr = new ClassReader(bytes);
                ClassNode cn = new ClassNode();
                cr.accept(cn, 0);

                for (MethodNode mn : cn.methods) {

                    int getStatic = 0;
                    int putStatic = 0;
                    int ifCmp = 0;
                    int methodCalls = 0;

                    for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {

                        if (insn instanceof FieldInsnNode) {
                            FieldInsnNode f = (FieldInsnNode) insn;
                            if (f.getOpcode() == Opcodes.GETSTATIC)
                                getStatic++;
                            if (f.getOpcode() == Opcodes.PUTSTATIC)
                                putStatic++;
                        }

                        if (insn instanceof JumpInsnNode) {
                            int op = insn.getOpcode();
                            if (op >= Opcodes.IF_ICMPEQ && op <= Opcodes.IF_ICMPLE)
                                ifCmp++;
                        }

                        if (insn instanceof MethodInsnNode)
                            methodCalls++;
                    }

                }

            } catch (Exception e) {
                e.printStackTrace();
            }

            return;
        }
    }

    // From Line 31047
    public static void processC02PacketUseEntityMethods() {
        Class<?> clazz = get("C02PacketUseEntity");
        if (clazz == null) {
            return;
        }
        for (Method method : clazz.getDeclaredMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (params.length == 1 && params[0] == get("World")) {
                method.setAccessible(true);
                putMethod("C02PacketUseEntity.getEntity", method);
                break;
            }
        }
    }

    // From Line 31062
    public static void processModelBipedMethods() {
        Class<?> clazz = get("ModelBiped");
        if (clazz == null) {

            return;
        }
        for (Method method : clazz.getDeclaredMethods()) {
            Class<?>[] classArray = method.getParameterTypes();
            if (classArray.length != 7 || classArray[0] != Float.TYPE || classArray[1] != Float.TYPE
                    || classArray[2] != Float.TYPE || classArray[3] != Float.TYPE || classArray[4] != Float.TYPE
                    || classArray[5] != Float.TYPE || classArray[6] != get("Entity"))
                continue;
            method.setAccessible(true);
            putMethod("ModelBiped.setRotationAngles", method);

            break;
        }
    }

    // From Line 31081
    public static void findGetSubCompoundMethod() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> itemStackClass = get("ItemStack");

        if (itemStackClass == null) {
            System.out.println("ItemStack sınıfı bulunamadı");
            return;
        }

        for (Class<?> candidate : classes) {
            if (!candidate.equals(itemStackClass)) {
                continue;
            }
            for (Method method : candidate.getDeclaredMethods()) {
                if (method.getParameterCount() != 2) {
                    continue;
                }
                Class<?>[] params = method.getParameterTypes();
                if (params[0] != String.class || params[1] != boolean.class) {
                    continue;
                }
                Class<?> returnType = method.getReturnType();

                put("NBTTagCompound", returnType);

                putMethod("ItemStack.getSubCompound", method);

                return;
            }
        }

    }

    // From Line 31114
    public static void findSetTagInfoMethod() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> itemStackClass = get("ItemStack");

        if (itemStackClass == null) {
            System.out.println("ItemStack sınıfı bulunamadı");
            return;
        }

        for (Class<?> candidate : classes) {
            if (!candidate.equals(itemStackClass)) {
                continue;
            }

            for (Method method : candidate.getDeclaredMethods()) {
                if (method.getParameterCount() != 2) {
                    continue;
                }

                Class<?>[] params = method.getParameterTypes();
                if (params[0] != String.class) {
                    continue;
                }
                Class<?> nbtBaseClass = params[1];
                put("NBTBase", nbtBaseClass);
                putMethod("ItemStack.setTagInfo", method);

                return;
            }
        }
    }

    // From Line 31146
    public static void processKeyBindingClasses() {
        Vector<Class<?>> classes = new Vector<>(getClasses());

        for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface() || candidate.getSuperclass() != Object.class)
                continue;

            boolean hasTargetCtor = false;
            for (Constructor<?> ctor : candidate.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length == 3 &&
                        params[0] == String.class &&
                        params[1] == int.class &&
                        params[2] == String.class) {
                    hasTargetCtor = true;
                    break;
                }
            }

            if (!hasTargetCtor)
                continue;

            boolean implementsComparable = false;
            for (Class<?> iface : candidate.getInterfaces()) {
                if (Comparable.class.isAssignableFrom(iface)) {
                    implementsComparable = true;
                    break;
                }
            }

            if (!implementsComparable)
                continue;

            put("KeyBinding", candidate);

            try {
                byte[] classBytes = getClassBytes(candidate);
                if (missingClassBytes("findKeyBinding fields", candidate, classBytes)) {
                    System.out.println("Class bytes alınamadı: " + candidate.getName());
                    continue;
                }

                Set<String> assignedIntFields = new HashSet<>();
                ClassNode cn = new ClassNode();
                new ClassReader(classBytes).accept(cn, 0);

                for (MethodNode mn : (List<MethodNode>) cn.methods) {
                    if ("<init>".equals(mn.name) && "(Ljava/lang/String;ILjava/lang/String;)V".equals(mn.desc)) {
                        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                            if (insn.getOpcode() == PUTFIELD) {
                                FieldInsnNode fin = (FieldInsnNode) insn;
                                assignedIntFields.add(fin.name);
                            }
                        }
                        break;
                    }
                }

                Field keyCodeDefaultField = null;
                Field keyCodeField = null;
                Field pressedField = null;

                for (Field f : candidate.getDeclaredFields()) {
                    f.setAccessible(true);
                    if (f.getType() == boolean.class && !Modifier.isStatic(f.getModifiers())) {
                        pressedField = f;
                        break;
                    }
                }

                for (Field f : candidate.getDeclaredFields()) {
                    f.setAccessible(true);
                    if (f.getType() == int.class && assignedIntFields.contains(f.getName())) {
                        if (Modifier.isFinal(f.getModifiers()))
                            keyCodeDefaultField = f;
                        else
                            keyCodeField = f;
                    }
                }

                if (keyCodeDefaultField == null || keyCodeField == null) {
                    List<Field> intFields = new ArrayList<>();
                    for (Field f : candidate.getDeclaredFields()) {
                        if (f.getType() == int.class)
                            intFields.add(f);
                    }
                    if (keyCodeDefaultField == null && intFields.size() > 0)
                        keyCodeDefaultField = intFields.get(0);
                    if (keyCodeField == null && intFields.size() > 1)
                        keyCodeField = intFields.get(1);
                }

                if (keyCodeDefaultField != null) {
                    putField("KeyBinding.keyCodeDefault", keyCodeDefaultField);
                }
                if (keyCodeField != null) {
                    putField("KeyBinding.keyCode", keyCodeField);
                }
                if (pressedField != null) {
                    putField("KeyBinding.pressed", pressedField);
                }

                Method setKeyBindState = findMethod(candidate, Modifier.PUBLIC | Modifier.STATIC,
                        void.class, get("Minecraft"), int.class, boolean.class);
                if (setKeyBindState != null) {
                    putMethod("KeyBinding.setKeyBindState", setKeyBindState);
                }

                for (Method m : candidate.getDeclaredMethods()) {
                    Class<?>[] params = m.getParameterTypes();
                    if (m.getReturnType() == void.class &&
                            params.length == 1 &&
                            params[0] == int.class &&
                            Modifier.isStatic(m.getModifiers()) &&
                            Modifier.isPublic(m.getModifiers())) {
                        putMethod("KeyBinding.onTick", m);
                        break;
                    }
                }

            } catch (Throwable t) {
                t.printStackTrace();
            }

            break;
        }
    }

    // From Line 31274
    public static void processMouseHelperClass() {
        Vector<Class<?>> classes = new Vector<>(getClasses());

        for (Class<?> candidate : classes) {
            try {
                if (candidate.isEnum() || candidate.isInterface())
                    continue;

                boolean onlyMouseOrDisplay = true;

                for (Field f : candidate.getDeclaredFields()) {
                    Class<?> t = f.getType();
                    if (t != Mouse.class && t != Display.class) {
                        onlyMouseOrDisplay = false;
                        break;
                    }
                }

                for (Method m : candidate.getDeclaredMethods()) {
                    if (m.getReturnType() != boolean.class &&
                            m.getReturnType() != Mouse.class &&
                            m.getReturnType() != Display.class) {
                        onlyMouseOrDisplay = false;
                        break;
                    }
                    for (Class<?> p : m.getParameterTypes()) {
                        if (p != Mouse.class && p != Display.class) {
                            onlyMouseOrDisplay = false;
                            break;
                        }
                    }
                }

                boolean hasBooleanMethod = false;
                for (Method m : candidate.getDeclaredMethods()) {
                    if (m.getReturnType() == boolean.class) {
                        hasBooleanMethod = true;
                        break;
                    }
                }

                if (onlyMouseOrDisplay && hasBooleanMethod) {
                    put("MouseHelper", candidate);
                    break;
                }

            } catch (Throwable ignored) {
            }
        }
    }

    // From Line 31325
    public static void findGameProfile() {
        Class<?> abstractClientPlayerClass = get("AbstractClientPlayer");
        for (Constructor<?> constructor : abstractClientPlayerClass.getDeclaredConstructors()) {
            Class<?>[] paramTypes = constructor.getParameterTypes();
            if (paramTypes.length == 2) {
                Class<?> worldClass = get("World");
                if (worldClass != null && paramTypes[0].equals(worldClass)) {
                    Class<?> gameProfileClass = paramTypes[1];
                    put("GameProfile", gameProfileClass);
                    for (Field field : gameProfileClass.getDeclaredFields()) {
                        if (field.getType().equals(java.util.UUID.class)) {
                            putField("GameProfile.uuid", field);
                            break;
                        }
                    }
                    for (Method method : gameProfileClass.getDeclaredMethods()) {
                        if (method.getReturnType().equals(java.util.UUID.class) && method.getParameterCount() == 0) {
                            putMethod("GameProfile.getUUID", method);
                            break;
                        }
                    }
                    Class<?> entityPlayerClass = get("EntityPlayer");
                    for (Field field : entityPlayerClass.getDeclaredFields()) {
                        if (field.getType().equals(gameProfileClass)) {
                            putField("EntityPlayer.gameProfile", field);
                            break;
                        }
                    }
                    for (Method method : entityPlayerClass.getDeclaredMethods()) {
                        if (method.getReturnType().equals(java.util.UUID.class) && method.getParameterCount() == 0) {
                            putMethod("EntityPlayer.getGameProfile", method);
                            break;
                        }
                    }
                    return;
                }
            }
        }
    }

    // From Line 31397
    public static void processGameSettingsClass() {
        Vector<Class<?>> classes = new Vector<>(getClasses());

        Class<?>[] requiredClasses = new Class<?>[] {
                get("Minecraft"),
                get("World"),
                get("Gui"),
                get("Packet"),
                get("EntityPlayer"),
                get("craftrise.Config")
        };

        Class<?>[] extraClasses = new Class<?>[] {
                BufferedReader.class, File.class, FileInputStream.class, FileOutputStream.class,
                InputStream.class, InputStreamReader.class, OutputStream.class, OutputStreamWriter.class,
                PrintWriter.class, CallSite.class, MethodHandle.class, MethodHandles.class,
                java.lang.invoke.MethodType.class, MutableCallSite.class, ParameterizedType.class,
                java.security.Key.class, Arrays.class, HashMap.class, Iterator.class, LinkedList.class,
                List.class, Map.class, Set.class, Collectors.class, Cipher.class, SecretKey.class,
                SecretKeyFactory.class, DESKeySpec.class, IvParameterSpec.class,
                org.apache.commons.lang3.ArrayUtils.class, org.apache.logging.log4j.LogManager.class,
                org.apache.logging.log4j.Logger.class, ImmutableSet.class, Lists.class, Maps.class,
                Sets.class, Gson.class
        };

        Set<Class<?>> requiredSet = Sets.newHashSet(requiredClasses);
        Set<Class<?>> extraSet = Sets.newHashSet(extraClasses);

        for (Class<?> candidate : classes) {
            try {

                if (candidate.isEnum() || candidate.isInterface() || candidate.getSuperclass() != Object.class)
                    continue;

                Constructor<?>[] ctors = candidate.getDeclaredConstructors();
                boolean hasDefaultCtor = false;
                boolean hasMinecraftFileCtor = false;

                for (Constructor<?> ctor : ctors) {
                    Class<?>[] params = ctor.getParameterTypes();
                    if (params.length == 0) {
                        hasDefaultCtor = true;
                    } else if (params.length == 2 && params[0].equals(get("Minecraft"))
                            && params[1].equals(File.class)) {
                        hasMinecraftFileCtor = true;
                    }
                }

                if (!(hasDefaultCtor && hasMinecraftFileCtor))
                    continue;

                boolean usedRequiredOrExtra = false;

                outerMethod: for (Method m : candidate.getDeclaredMethods()) {
                    if (requiredSet.contains(m.getReturnType()) || extraSet.contains(m.getReturnType())) {
                        usedRequiredOrExtra = true;
                        break;
                    }
                    for (Class<?> param : m.getParameterTypes()) {
                        if (requiredSet.contains(param) || extraSet.contains(param)) {
                            usedRequiredOrExtra = true;
                            break outerMethod;
                        }
                    }
                }

                if (!usedRequiredOrExtra) {
                    for (Field f : candidate.getDeclaredFields()) {
                        Class<?> t = f.getType();
                        if (t.isArray() || List.class.isAssignableFrom(t)) {
                            usedRequiredOrExtra = true;
                            break;
                        }
                    }
                }

                if (usedRequiredOrExtra) {
                    put("GameSettings", candidate);
                    break;
                }

            } catch (Throwable ignored) {

            }
        }
    }

    // From Line 31484
    public static void findEntityInsideOpaqueBlock(Class<?> clazz) throws Exception {
        byte[] classBytes = getClassBytes(clazz);
        if (missingClassBytes("findEntityInsideOpaqueBlock", clazz, classBytes))
            return;

        ClassReader cr = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        for (MethodNode mn : cn.methods) {

            if ((mn.access & Opcodes.ACC_PUBLIC) == 0)
                continue;
            if (!mn.desc.equals("()Z"))
                continue;

            int tryCatchCount = mn.tryCatchBlocks.size();
            int methodInsnCount = 0;
            int iconstCount = 0;
            int intInsnCount = 0;
            int loopIndicator = 0;

            for (AbstractInsnNode insn : mn.instructions) {
                int opcode = insn.getOpcode();
                if (opcode == Opcodes.ICONST_0 || opcode == Opcodes.ICONST_1 ||
                        opcode == Opcodes.ICONST_2 || opcode == Opcodes.ICONST_3 ||
                        opcode == Opcodes.ICONST_4 || opcode == Opcodes.ICONST_5 ||
                        opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
                    iconstCount++;
                }
                if (insn instanceof MethodInsnNode) {
                    methodInsnCount++;
                }
                if (opcode == Opcodes.IINC || opcode == Opcodes.ILOAD ||
                        opcode == Opcodes.IF_ICMPGE || opcode == Opcodes.IF_ICMPLT ||
                        opcode == Opcodes.GOTO) {
                    loopIndicator++;
                }
            }
            if (tryCatchCount >= 2 && methodInsnCount >= 5 && iconstCount >= 10 && loopIndicator >= 5) {
                Method method = clazz.getDeclaredMethod(mn.name);
                method.setAccessible(true);
                putMethod("Entity.isEntityInsideOpaqueBlock", method);
                return;
            }
        }

    }

    // From Line 31530
    public static void findFallDistanceAndInWeb(Class<?> clazz) throws Exception {
        byte[] bytes = getClassBytes(clazz);
        if (missingClassBytes("findFallDistanceAndInWeb", clazz, bytes))
            return;

        mapEntityGetUniqueID(clazz);
        ClassReader cr = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        for (MethodNode mn : cn.methods) {
            if ((mn.access & Opcodes.ACC_PUBLIC) != 0 && mn.desc.equals("()V")) {

                String floatFieldName = null;
                String booleanFieldName = null;
                boolean callsOtherMethod = false;

                AbstractInsnNode insn = mn.instructions.getFirst();
                while (insn != null) {
                    if (insn instanceof MethodInsnNode) {
                        callsOtherMethod = true;
                        break;
                    }
                    if (insn.getOpcode() == Opcodes.ICONST_1) {
                        AbstractInsnNode next = insn.getNext();
                        if (next instanceof FieldInsnNode) {
                            FieldInsnNode fin = (FieldInsnNode) next;
                            if (fin.getOpcode() == PUTFIELD && "Z".equals(fin.desc) && fin.owner.equals(cn.name)) {
                                booleanFieldName = fin.name;
                            }
                        }
                    }
                    if (insn.getOpcode() == Opcodes.FCONST_0) {
                        AbstractInsnNode next = insn.getNext();
                        if (next instanceof FieldInsnNode) {
                            FieldInsnNode fin = (FieldInsnNode) next;
                            if (fin.getOpcode() == PUTFIELD && "F".equals(fin.desc) && fin.owner.equals(cn.name)) {
                                floatFieldName = fin.name;
                            }
                        }
                    }

                    insn = insn.getNext();
                }

                if (!callsOtherMethod && floatFieldName != null && booleanFieldName != null) {
                    putField("Entity.fallDistance", clazz.getDeclaredField(floatFieldName));
                    putField("Entity.isInWeb", clazz.getDeclaredField(booleanFieldName));
                    return;
                }
            }
        }
    }

    // From Line 31581
    public static void mapEntityGetUniqueID(Class<?> clazz) {
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType().equals(UUID.class)) {
                    putMethod("Entity.getUniqueID", m);
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // From Line 31594
    public static void mapLastTickFields(Class<?> clazz) throws Exception {
        byte[] bytes = getClassBytes(clazz);
        if (missingClassBytes("mapLastTickFields", clazz, bytes))
            return;

        ClassReader cr = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        String owner = cn.name;

        for (MethodNode mn : cn.methods) {
            if (!mn.desc.equals("(DDDFF)V"))
                continue;

            List<FieldInsnNode> doubles = new ArrayList<>();

            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof FieldInsnNode) {
                    FieldInsnNode f = (FieldInsnNode) insn;

                    if (f.owner.equals(owner) &&
                            f.getOpcode() == PUTFIELD &&
                            f.desc.equals("D")) {
                        doubles.add(f);
                    }
                }
            }

            if (doubles.size() != 9)
                continue;

            Field prevX = clazz.getDeclaredField(doubles.get(1).name);
            Field posX = clazz.getDeclaredField(doubles.get(0).name);
            Field lastX = clazz.getDeclaredField(doubles.get(2).name);

            Field prevY = clazz.getDeclaredField(doubles.get(4).name);
            Field posY = clazz.getDeclaredField(doubles.get(3).name);
            Field lastY = clazz.getDeclaredField(doubles.get(5).name);

            Field prevZ = clazz.getDeclaredField(doubles.get(7).name);
            Field posZ = clazz.getDeclaredField(doubles.get(6).name);
            Field lastZ = clazz.getDeclaredField(doubles.get(8).name);
            putField("Entity.prevPosX", prevX);
            putField("Entity.prevPosY", prevY);
            putField("Entity.prevPosZ", prevZ);

            putField("Entity.posX", posX);
            putField("Entity.posY", posY);
            putField("Entity.posZ", posZ);

            putField("Entity.lastTickPosX", lastX);
            putField("Entity.lastTickPosY", lastY);
            putField("Entity.lastTickPosZ", lastZ);

            return;
        }
    }

    // From Line 31650
    public static void findEntityFields(Class<?> clazz) throws Exception {
        byte[] bytes = getClassBytes(clazz);
        if (missingClassBytes("findEntityFields", clazz, bytes))
            return;

        ClassReader cr = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if ((mn.access & Opcodes.ACC_PUBLIC) != 0 &&
                    mn.desc.equals("(L" + cn.name + ";)V")) {

                List<FieldInsnNode> fields = new ArrayList<>();

                for (AbstractInsnNode insn : mn.instructions) {
                    if (insn.getOpcode() == Opcodes.GETFIELD) {
                        FieldInsnNode fin = (FieldInsnNode) insn;
                        if (fin.owner.equals(cn.name)) {
                            fields.add(fin);
                        }
                    }
                }
                if (fields.size() >= 5) {
                    if ("D".equals(fields.get(0).desc) &&
                            "D".equals(fields.get(1).desc) &&
                            "D".equals(fields.get(2).desc) &&
                            "F".equals(fields.get(3).desc) &&
                            "F".equals(fields.get(4).desc)) {

                        Field posX = clazz.getDeclaredField(fields.get(0).name);
                        Field posY = clazz.getDeclaredField(fields.get(1).name);
                        Field posZ = clazz.getDeclaredField(fields.get(2).name);
                        Field rotationYaw = clazz.getDeclaredField(fields.get(3).name);
                        Field rotationPitch = clazz.getDeclaredField(fields.get(4).name);

                        putField("Entity.rotationYaw", rotationYaw);
                        putField("Entity.rotationPitch", rotationPitch);

                        return;
                    }
                }
            }
        }
    }

    // From Line 31807
    public static void mapRotationYawHeadField(Class<?> clazz) throws Exception {
        byte[] bytes = getClassBytes(clazz);
        if (missingClassBytes("mapRotationYawHeadField", clazz, bytes))
            return;

        ClassReader cr = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        String owner = cn.name;
        Field renderYawOffsetField = getField("EntityLivingBase.renderYawOffset");

        for (MethodNode mn : cn.methods) {
            if ((mn.access & Opcodes.ACC_PUBLIC) == 0)
                continue;
            if (!mn.desc.equals("(F)V"))
                continue;

            boolean callsOtherMethods = false;
            Field fieldSet = null;

            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof MethodInsnNode) {
                    callsOtherMethods = true;
                    break;
                }
                if (insn instanceof JumpInsnNode) {
                    callsOtherMethods = true;
                    break;
                }
                if (insn instanceof FieldInsnNode) {
                    FieldInsnNode fin = (FieldInsnNode) insn;
                    if (!fin.owner.equals(owner))
                        continue;
                    if (!fin.desc.equals("F"))
                        continue;
                    if (fin.getOpcode() == PUTFIELD) {
                        fieldSet = clazz.getDeclaredField(fin.name);
                    }
                }
            }

            if (!callsOtherMethods && fieldSet != null && !fieldSet.equals(renderYawOffsetField)) {
                fieldSet.setAccessible(true);
                putField("EntityLivingBase.rotationYawHead", fieldSet);
                return;
            }
        }
    }

    // From Line 31854
    public static void mapRenderYawOffsetField(Class<?> clazz) throws Exception {
        byte[] bytes = getClassBytes(clazz);
        if (missingClassBytes("mapRenderYawOffsetField", clazz, bytes))
            return;
        ClassReader cr = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        String owner = cn.name;

        for (MethodNode mn : cn.methods) {
            if ((mn.access & Opcodes.ACC_PROTECTED) == 0)
                continue;
            if (!mn.desc.equals("(FF)F"))
                continue;

            for (AbstractInsnNode insn : mn.instructions.toArray()) {
                if (insn instanceof FieldInsnNode) {
                    FieldInsnNode fin = (FieldInsnNode) insn;

                    if (!fin.owner.equals(owner))
                        continue;
                    if (!fin.desc.equals("F"))
                        continue;
                    if (fin.getOpcode() == PUTFIELD) {
                        Field field = clazz.getDeclaredField(fin.name);
                        field.setAccessible(true);
                        putField("EntityLivingBase.renderYawOffset", field);
                        return;
                    }
                }
            }
        }
    }

    // From Line 31887
    public static void findEntityFields() {
        try {
            Class<?> entityClass = get("Entity");
            Class<?> entityLivingBaseClass = get("EntityLivingBase");

            if (entityClass == null) {
                System.out.println("Entity classı bulunamadı!");
                return;
            }

            if (entityLivingBaseClass == null) {
                warnMappingSkip("EntityLivingBase fields", "EntityLivingBase class is not mapped");
            } else {
                mapRenderYawOffsetField(entityLivingBaseClass);
                mapRotationYawHeadField(entityLivingBaseClass);
            }

            mapLastTickFields(entityClass);
            findEntityInsideOpaqueBlock(entityClass);
            findFallDistanceAndInWeb(entityClass);
            findEntityFields(entityClass);
            findTicksExisted();

        } catch (Exception e) {
            System.out.println("Entity alanları aranırken hata:");
            e.printStackTrace();
        }
    }

    // From Line 32036
    public static void findTicksExisted() {
        try {

            Class<?> entityClass = get("Entity");
            Class<?> worldClass = get("World");

            if (entityClass == null || worldClass == null) {
                return;
            }

            List<Field> entityIntFields = new ArrayList<>();
            for (Field field : entityClass.getDeclaredFields()) {
                if (field.getType() == int.class && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    entityIntFields.add(field);
                }
            }

            Field ticksExistedField = findFieldWithIncrementPattern(entityIntFields, worldClass);

            if (ticksExistedField != null) {
                putField("Entity.ticksExisted", ticksExistedField);
            } else {

            }

        } catch (Exception e) {

        }
    }

    // From Line 32066
    private static Field findFieldWithIncrementPattern(List<Field> candidateFields, Class<?> worldClass) {
        try {
            byte[] worldBytes = getClassBytes(worldClass);
            if (missingClassBytes("findFieldWithIncrementPattern", worldClass, worldBytes))
                return null;
            ClassNode worldNode = new ClassNode();
            ClassReader worldReader = new ClassReader(worldBytes);
            worldReader.accept(worldNode, ClassReader.SKIP_DEBUG);

            for (Field candidate : candidateFields) {
                String fieldName = candidate.getName();

                if (hasIncrementPatternInWorld(worldNode, fieldName)) {
                    return candidate;
                }
            }
        } catch (Exception e) {

        }
        return null;
    }

    // From Line 32086
    private static boolean hasIncrementPatternInWorld(ClassNode worldNode, String fieldName) {
        for (MethodNode method : worldNode.methods) {
            Iterator<AbstractInsnNode> iterator = method.instructions.iterator();

            while (iterator.hasNext()) {
                AbstractInsnNode insn = iterator.next();

                if (insn instanceof FieldInsnNode) {
                    FieldInsnNode fieldInsn = (FieldInsnNode) insn;
                    if (fieldInsn.name.equals(fieldName) && fieldInsn.getOpcode() == PUTFIELD) {

                        AbstractInsnNode current = insn.getPrevious();
                        boolean foundIADD = false;
                        boolean foundICONST1 = false;
                        boolean foundGETFIELD = false;

                        for (int i = 0; i < 10 && current != null; i++) {
                            if (current.getOpcode() == Opcodes.IADD) {
                                foundIADD = true;
                            } else if (current.getOpcode() == Opcodes.ICONST_1) {
                                foundICONST1 = true;
                            } else if (current instanceof FieldInsnNode) {
                                FieldInsnNode prevField = (FieldInsnNode) current;
                                if (prevField.name.equals(fieldName) && prevField.getOpcode() == Opcodes.GETFIELD) {
                                    foundGETFIELD = true;
                                }
                            }

                            if (foundIADD && foundICONST1 && foundGETFIELD) {
                                return true;
                            }
                            current = current.getPrevious();
                        }
                    }
                }
            }
        }
        return false;
    }

    // From Line 32126
    public static void findPrevRenderYawOffset() {
        try {
            Class<?> entityLivingBaseClass = get("EntityLivingBase");

            if (entityLivingBaseClass == null) {
                return;
            }
            List<Field> allFloatFields = new ArrayList<>();
            for (Field field : entityLivingBaseClass.getDeclaredFields()) {
                if (field.getType() == float.class && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    allFloatFields.add(field);
                }
            }
            Field prevRenderYawOffsetField = findFieldByOpcodePatterns(entityLivingBaseClass, allFloatFields);
            if (prevRenderYawOffsetField != null) {
                putField("EntityLivingBase.prevRenderYawOffset", prevRenderYawOffsetField);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // From Line 32148
    private static Field findFieldByOpcodePatterns(Class<?> targetClass, List<Field> candidateFields) {
        try {
            byte[] classBytes = getClassBytes(targetClass);
            if (missingClassBytes("findFieldByOpcodePatterns", targetClass, classBytes))
                return null;
            ClassNode classNode = new ClassNode();
            ClassReader classReader = new ClassReader(classBytes);
            classReader.accept(classNode, ClassReader.SKIP_DEBUG);
            Map<Field, Integer> fieldScores = new HashMap<>();
            for (Field candidate : candidateFields) {
                String fieldName = candidate.getName();
                int score = calculatePatternScore(classNode, fieldName);
                fieldScores.put(candidate, score);
            }
            Field bestField = null;
            int highestScore = 0;

            for (Map.Entry<Field, Integer> entry : fieldScores.entrySet()) {
                if (entry.getValue() > highestScore) {
                    highestScore = entry.getValue();
                    bestField = entry.getKey();
                }
            }
            if (highestScore >= 3) {
                return bestField;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    // From Line 32179
    private static int calculatePatternScore(ClassNode classNode, String fieldName) {
        int score = 0;

        for (MethodNode method : classNode.methods) {
            Iterator<AbstractInsnNode> iterator = method.instructions.iterator();
            List<AbstractInsnNode> instructions = new ArrayList<>();

            while (iterator.hasNext()) {
                instructions.add(iterator.next());
            }
            for (int i = 0; i < instructions.size(); i++) {
                AbstractInsnNode insn = instructions.get(i);

                if (insn instanceof FieldInsnNode) {
                    FieldInsnNode fieldInsn = (FieldInsnNode) insn;
                    if (fieldInsn.name.equals(fieldName) && fieldInsn.getOpcode() == PUTFIELD) {
                        boolean foundGetFieldBefore = false;
                        boolean foundAload = false;

                        for (int j = 1; j <= 5 && i - j >= 0; j++) {
                            AbstractInsnNode prev = instructions.get(i - j);

                            if (prev instanceof FieldInsnNode) {
                                FieldInsnNode prevField = (FieldInsnNode) prev;
                                if (prevField.getOpcode() == Opcodes.GETFIELD &&
                                        !prevField.name.equals(fieldName)) {
                                    foundGetFieldBefore = true;
                                }
                            } else if (prev.getOpcode() == Opcodes.ALOAD) {
                                foundAload = true;
                            }
                        }

                        if (foundGetFieldBefore && foundAload) {
                            score += 2;
                        }
                    }
                }
            }
            for (int i = 0; i < instructions.size() - 3; i++) {
                AbstractInsnNode insn1 = instructions.get(i);
                AbstractInsnNode insn2 = instructions.get(i + 1);
                AbstractInsnNode insn3 = instructions.get(i + 2);

                if (insn1 instanceof FieldInsnNode &&
                        insn2.getOpcode() == Opcodes.FSUB &&
                        insn3 instanceof FieldInsnNode) {

                    FieldInsnNode fieldInsn1 = (FieldInsnNode) insn1;
                    FieldInsnNode fieldInsn3 = (FieldInsnNode) insn3;

                    if (fieldInsn1.name.equals(fieldName) &&
                            fieldInsn1.getOpcode() == Opcodes.GETFIELD &&
                            fieldInsn3.getOpcode() == Opcodes.GETSTATIC) {
                        score += 3;
                    }
                }
            }
            for (int i = 0; i < instructions.size() - 2; i++) {
                AbstractInsnNode insn1 = instructions.get(i);
                AbstractInsnNode insn2 = instructions.get(i + 1);
                AbstractInsnNode insn3 = instructions.get(i + 2);

                if (insn1.getOpcode() == Opcodes.ALOAD &&
                        insn2.getOpcode() == Opcodes.DUP &&
                        insn3 instanceof FieldInsnNode) {

                    FieldInsnNode fieldInsn = (FieldInsnNode) insn3;
                    if (fieldInsn.name.equals(fieldName) &&
                            fieldInsn.getOpcode() == Opcodes.GETFIELD) {
                        score += 2;
                    }
                }
            }
            for (int i = 0; i < instructions.size() - 3; i++) {
                AbstractInsnNode insn1 = instructions.get(i);
                AbstractInsnNode insn2 = instructions.get(i + 1);
                AbstractInsnNode insn3 = instructions.get(i + 2);

                if (insn1 instanceof MethodInsnNode &&
                        (insn2.getOpcode() == Opcodes.FSUB || insn2.getOpcode() == Opcodes.FADD) &&
                        insn3 instanceof FieldInsnNode) {

                    FieldInsnNode fieldInsn = (FieldInsnNode) insn3;
                    if (fieldInsn.name.equals(fieldName) &&
                            fieldInsn.getOpcode() == PUTFIELD) {
                        score += 3;
                    }
                }
            }

            int fieldReferences = countFieldReferences(method, fieldName);
            if (fieldReferences >= 4) {
                score += 2;
            }
        }

        return score;
    }

    // From Line 32279
    private static int countFieldReferences(MethodNode method, String fieldName) {
        int count = 0;
        Iterator<AbstractInsnNode> iterator = method.instructions.iterator();

        while (iterator.hasNext()) {
            AbstractInsnNode insn = iterator.next();
            if (insn instanceof FieldInsnNode) {
                FieldInsnNode fieldInsn = (FieldInsnNode) insn;
                if (fieldInsn.name.equals(fieldName)) {
                    count++;
                }
            }
        }
        return count;
    }

    // From Line 32295
    public static void findRenderManagerFields() {

    }

    // From Line 32299
    public static void findRendererLivingEntity() {
        Class<?> entityLivingBaseClass = get("EntityLivingBase");
        Class<?> renderClass = get("Render");

        if (entityLivingBaseClass == null || renderClass == null) {
            System.out.println("EntityLivingBase veya Render class bulunamadı!");
            return;
        }

        for (Class<?> clazz : getClasses()) {
            if (renderClass.isAssignableFrom(clazz) && !clazz.equals(renderClass)) {
                java.lang.reflect.Type genericSuperclass = clazz.getGenericSuperclass();

                if (genericSuperclass instanceof ParameterizedType) {
                    ParameterizedType pType = (ParameterizedType) genericSuperclass;
                    java.lang.reflect.Type[] typeArgs = pType.getActualTypeArguments();

                    if (typeArgs.length >= 1) {
                        java.lang.reflect.Type firstTypeArg = typeArgs[0];
                        if (firstTypeArg instanceof TypeVariable) {
                            TypeVariable<?> typeVar = (TypeVariable<?>) firstTypeArg;
                            java.lang.reflect.Type[] bounds = typeVar.getBounds();

                            for (java.lang.reflect.Type bound : bounds) {
                                if (bound instanceof Class
                                        && entityLivingBaseClass.isAssignableFrom((java.lang.Class<?>) bound)) {
                                    put("RendererLivingEntity", clazz);
                                    findLayerRendererClass(clazz);
                                    return;
                                }
                            }
                        } else if (firstTypeArg instanceof Class
                                && entityLivingBaseClass.isAssignableFrom((java.lang.Class<?>) firstTypeArg)) {
                            put("RendererLivingEntity", clazz);
                            findLayerRendererClass(clazz);
                            return;
                        }
                    }
                }
            }
        }

        System.out.println("RendererLivingEntity bulunamadı!");
    }

    // From Line 32344
    public static void findLayerRendererClass(Class<?> rendererLivingEntityClass) {
        for (Method method : rendererLivingEntityClass.getDeclaredMethods()) {
            java.lang.reflect.Type[] genericParamTypes = method.getGenericParameterTypes();

            if (genericParamTypes.length >= 1) {
                for (java.lang.reflect.Type paramType : genericParamTypes) {
                    if (paramType instanceof ParameterizedType) {
                        ParameterizedType pType = (ParameterizedType) paramType;
                        java.lang.reflect.Type[] typeArgs = pType.getActualTypeArguments();

                        if (typeArgs.length >= 2) {
                            java.lang.reflect.Type secondArg = typeArgs[1];

                            if (secondArg instanceof TypeVariable) {
                                TypeVariable<?> typeVarU = (TypeVariable<?>) secondArg;
                                java.lang.reflect.Type[] boundsU = typeVarU.getBounds();

                                for (java.lang.reflect.Type boundU : boundsU) {
                                    if (boundU instanceof ParameterizedType) {
                                        ParameterizedType boundPType = (ParameterizedType) boundU;
                                        java.lang.reflect.Type rawType = boundPType.getRawType();
                                        if (rawType instanceof Class) {
                                            put("LayerRenderer", (Class<?>) rawType);
                                            return;
                                        }
                                    }
                                }
                            } else if (secondArg instanceof ParameterizedType) {
                                ParameterizedType secondPType = (ParameterizedType) secondArg;
                                java.lang.reflect.Type rawType = secondPType.getRawType();

                                if (rawType instanceof Class) {
                                    put("LayerRenderer", (Class<?>) rawType);
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        }

        for (Method method : rendererLivingEntityClass.getDeclaredMethods()) {
            TypeVariable<?>[] typeParams = method.getTypeParameters();
            if (typeParams.length >= 2) {
                TypeVariable<?> V = typeParams[0];
                TypeVariable<?> U = typeParams[1];
                java.lang.reflect.Type[] boundsU = U.getBounds();
                for (java.lang.reflect.Type boundU : boundsU) {
                    if (boundU instanceof ParameterizedType) {
                        ParameterizedType pType = (ParameterizedType) boundU;
                        java.lang.reflect.Type rawType = pType.getRawType();
                        if (rawType instanceof Class) {
                            put("LayerRenderer", (Class<?>) rawType);
                            return;
                        }
                    }
                }
            }
        }
    }

    // From Line 32406
    public static void findItemRendererClass() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> minecraftClass = get("Minecraft");
        Class<?> entityLivingBaseClass = get("EntityLivingBase");
        Class<?> itemStackClass = get("ItemStack");

        for (Class<?> candidate : classes) {
            if (candidate.isEnum() || candidate.isInterface() || candidate.getSuperclass() != Object.class) {
                continue;
            }

            Constructor<?>[] ctors = candidate.getDeclaredConstructors();
            if (ctors.length != 1)
                continue;

            Constructor<?> ctor = ctors[0];
            Class<?>[] params = ctor.getParameterTypes();
            if (params.length != 1 || params[0] != minecraftClass)
                continue;

            Object instance;
            try {
                ctor.setAccessible(true);
                instance = ctor.newInstance(getMinecraft());
            } catch (Exception e) {
                continue;
            }

            boolean hasMagicInt = false;
            for (Field field : candidate.getDeclaredFields()) {
                if (field.getType() != int.class)
                    continue;
                try {
                    field.setAccessible(true);
                    if (field.getInt(instance) == -1) {
                        hasMagicInt = true;
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
            if (!hasMagicInt)
                continue;
            for (Method method : candidate.getDeclaredMethods()) {
                Class<?>[] paramTypes = method.getParameterTypes();
                if (paramTypes.length == 3) {
                    boolean hasEntityLivingBase = false;
                    boolean hasItemStack = false;
                    boolean hasUnknown = false;
                    Class<?> unknownClass = null;

                    for (Class<?> paramType : paramTypes) {
                        if (paramType == entityLivingBaseClass)
                            hasEntityLivingBase = true;
                        else if (paramType == itemStackClass)
                            hasItemStack = true;
                        else if (paramType != minecraftClass && paramType != float.class) {
                            hasUnknown = true;
                            unknownClass = paramType;
                        }
                    }

                    if (hasEntityLivingBase && hasItemStack && hasUnknown) {
                        putMethod("ItemRenderer.renderItem", method);

                        if (unknownClass != null) {
                            if (unknownClass.isEnum()) {
                                put("ItemCameraTransformsType", unknownClass);
                                Class<?> declaringClass = unknownClass.getDeclaringClass();
                                if (declaringClass != null)
                                    put("ItemCameraTransforms", declaringClass);
                            } else {
                                put("ItemCameraTransforms", unknownClass);
                            }
                        }
                        break;
                    }
                }
            }

            if (get("ItemCameraTransforms") == null) {
                Class<?> transformsType = get("ItemCameraTransformsType");
                if (transformsType != null && transformsType.isEnum()) {
                    Class<?> declaringClass = transformsType.getDeclaringClass();
                    if (declaringClass != null)
                        put("ItemCameraTransforms", declaringClass);
                }
            }
            for (Method method : candidate.getDeclaredMethods()) {
                if (!Modifier.isPrivate(method.getModifiers()))
                    continue;
                if (method.getReturnType() != void.class)
                    continue;
                if (method.getParameterCount() != 0)
                    continue;
                putMethod("ItemRenderer.doBlockTransform", method);
                break;
            }
            for (Method method : candidate.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()))
                    continue;
                if (method.getReturnType() != void.class)
                    continue;
                if (method.getParameterCount() != 1)
                    continue;
                if (method.getParameterTypes()[0] != float.class)
                    continue;
                put("ItemRenderer", candidate);
                putMethod("ItemRenderer.renderItemInFirstPerson", method);
            }
            for (Method method : candidate.getDeclaredMethods()) {
                if (method.getName().equals("Ե")) {
                    method.setAccessible(true);
                    putMethod("ItemRenderer.transformFirstPersonItem", method);
                    break;
                }
            }
        }
    }

    // From Line 32616
    public static Class<?> findSwingPacket(Class<?> packetClass) {
        if (packetClass == null) {
            return null;
        }
        List<Class<?>> classes = getClasses();

        List<Class<?>> matchingClasses = new ArrayList<>();

        for (Class<?> clazz : classes) {
            if (packetClass.isAssignableFrom(clazz) && clazz != packetClass) {

                Constructor<?>[] constructors = clazz.getDeclaredConstructors();
                boolean hasOnlyParameterlessConstructors = true;

                for (Constructor<?> constructor : constructors) {
                    if (constructor.getParameterCount() > 0) {
                        hasOnlyParameterlessConstructors = false;
                        break;
                    }
                }

                if (hasOnlyParameterlessConstructors) {
                    matchingClasses.add(clazz);
                }

            }
        }

        if (matchingClasses.size() < 2) {
            return null;
        } else {
            Class<?> secondClass = matchingClasses.get(1);
            return secondClass;
        }
    }

    // From Line 32649
    public static void findItemStackSize() {
        try {
            Class<?> itemStackClass = get("ItemStack");
            if (itemStackClass == null) {
                return;
            }

            for (Field f : itemStackClass.getDeclaredFields()) {
                int mods = f.getModifiers();
                if (!Modifier.isStatic(mods) && f.getType() == int.class) {
                    f.setAccessible(true);

                    Class<?> itemClass = get("Item");
                    if (itemClass == null) {

                    }

                    try {
                        Constructor<?> itemCtor = itemClass.getDeclaredConstructor();
                        itemCtor.setAccessible(true);
                        Object dummyItem = itemCtor.newInstance();

                        Constructor<?> itemStackCtor = itemStackClass.getDeclaredConstructor(itemClass, int.class,
                                int.class);
                        itemStackCtor.setAccessible(true);
                        Object itemStackInstance = itemStackCtor.newInstance(dummyItem, 3, 0);

                        if (f.getInt(itemStackInstance) == 3) {
                            putField("ItemStack.stackSize", f);
                            return;
                        }
                    } catch (Exception e) {

                        continue;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // From Line 32691
    public static void findsendChatMessage() {
        try {
            Class<?> playerClass = get("EntityPlayerSP");
            if (playerClass == null) {
                return;
            }
            for (Method m : playerClass.getDeclaredMethods()) {
                int mods = m.getModifiers();
                if (Modifier.isPublic(mods)
                        && m.getReturnType() == void.class
                        && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == String.class
                        && m.getName().equals("i")) {
                    m.setAccessible(true);
                    putMethod("EntityPlayerSP.sendChatMessage", m);
                    return;
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // From Line 32884
    public static void findItemStackItemField() {
        try {
            Class<?> itemStackClass = get("ItemStack");
            if (itemStackClass == null) {
                return;
            }

            Class<?> itemClass = get("Item");
            if (itemClass == null) {
                return;
            }

            for (Field f : itemStackClass.getDeclaredFields()) {
                int mods = f.getModifiers();

                if (!Modifier.isStatic(mods) && f.getType() == itemClass) {
                    f.setAccessible(true);

                    try {

                        Constructor<?> itemCtor = itemClass.getDeclaredConstructor();
                        itemCtor.setAccessible(true);
                        Object dummyItem = itemCtor.newInstance();

                        Constructor<?> itemStackCtor = itemStackClass.getDeclaredConstructor(itemClass, int.class,
                                int.class);
                        itemStackCtor.setAccessible(true);
                        Object itemStackInstance = itemStackCtor.newInstance(dummyItem, 1, 0);

                        if (f.get(itemStackInstance) == dummyItem) {
                            putField("ItemStack.item", f);

                            return;
                        }
                    } catch (Exception e) {

                        continue;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // From Line 32929
    public static void findItemStackDisplayName() {
        try {
            Class<?> itemStackClass = get("ItemStack");
            if (itemStackClass == null) {
                System.out.println("ItemStack classı bulunamadı!");
                return;
            }

            Class<?> itemClass = get("Item");
            if (itemClass == null) {
                System.out.println("Item classı bulunamadı!");
                return;
            }

            Constructor<?> itemCtor = itemClass.getDeclaredConstructor();
            itemCtor.setAccessible(true);
            Object dummyItem = itemCtor.newInstance();

            Constructor<?> itemStackCtor = itemStackClass.getDeclaredConstructor(itemClass, int.class, int.class);
            itemStackCtor.setAccessible(true);
            Object dummyStack = itemStackCtor.newInstance(dummyItem, 1, 0);

            for (Method m : itemStackClass.getDeclaredMethods()) {
                if (m.getReturnType() == String.class &&
                        m.getParameterCount() == 0 &&
                        !m.getName().equals("toString")) {
                    m.setAccessible(true);
                    try {
                        Object result = m.invoke(dummyStack);
                        if (result instanceof String) {
                            String str = (String) result;

                            if (str.length() == 1 || str.contains("k")) {
                                putMethod("ItemStack.getDisplayName", m);
                                return;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // From Line 32976
    public static void findThirdPersonViewField() {
        try {
            Class<?> renderManagerClass = get("RenderManager");
            Class<?> worldClass = get("World");
            Class<?> fontRendererClass = get("FontRenderer");
            Class<?> entityClass = get("Entity");
            Class<?> gameSettingsClass = get("GameSettings");
            if (renderManagerClass == null || worldClass == null || fontRendererClass == null
                    || entityClass == null || gameSettingsClass == null) {
                warnMappingSkip("findThirdPersonViewField", "required class mapping is missing");
                return;
            }

            for (Field f : gameSettingsClass.getDeclaredFields()) {
                if (Modifier.isPublic(f.getModifiers()) && f.getType() == int.class) {
                    putField("GameSettings.publicIntField", f);
                }
            }

            for (Method m : renderManagerClass.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers()) || m.getReturnType() != void.class)
                    continue;

                Class<?>[] params = m.getParameterTypes();
                if (params.length == 6 &&
                        params[0] == worldClass &&
                        params[1] == fontRendererClass &&
                        params[2] == entityClass &&
                        params[3] == entityClass &&
                        params[4] == gameSettingsClass &&
                        params[5] == float.class) {

                    m.setAccessible(true);

                    Object dummyWorld = worldClass.getDeclaredConstructor().newInstance();
                    Object dummyFont = fontRendererClass.getDeclaredConstructor().newInstance();
                    Object dummyEntity1 = entityClass.getDeclaredConstructor().newInstance();
                    Object dummyEntity2 = entityClass.getDeclaredConstructor().newInstance();
                    Object dummySettings = gameSettingsClass.getDeclaredConstructor().newInstance();

                    try {
                        m.invoke(renderManagerClass.getDeclaredConstructor().newInstance(),
                                dummyWorld, dummyFont, dummyEntity1, dummyEntity2, dummySettings, 0f);
                    } catch (Throwable ignored) {
                    }

                    Field[] paramFields = dummySettings.getClass().getDeclaredFields();
                    for (Field f : paramFields) {
                        if (Modifier.isPublic(f.getModifiers()) && f.getType() == int.class) {
                            putField("GameSettings.methodIntField", f);
                            return;
                        }
                    }
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // From Line 33032
    public static void detectC05Fields(byte[] classBytes, Class<?> clazz) throws Exception {
        if (missingClassBytes("detectC05Fields", clazz, classBytes))
            return;
        ClassNode classNode = new ClassNode();
        ClassReader reader = new ClassReader(classBytes);
        reader.accept(classNode, 0);

        final String[] found = new String[2];

        for (MethodNode method : classNode.methods) {
            if (method.name.equals("<init>") && method.desc.equals("(FFZ)V")) {
                int putIndex = 0;

                for (AbstractInsnNode insn : method.instructions) {
                    if (insn.getOpcode() == PUTFIELD) {
                        FieldInsnNode fin = (FieldInsnNode) insn;
                        if ("F".equals(fin.desc) && putIndex < 2) {
                            found[putIndex] = fin.name;
                            putIndex++;
                        }
                    }
                }
            }
        }

        if (found[0] != null && found[1] != null) {
            Field yaw = clazz.getDeclaredField(found[0]);
            yaw.setAccessible(true);
            Field pitch = clazz.getDeclaredField(found[1]);
            pitch.setAccessible(true);

            putField("C03PacketPlayer.yaw", yaw);
            putField("C03PacketPlayer.pitch", pitch);
        } else {
            throw new IllegalStateException("Yaw ve Pitch alanları bulunamadı.");
        }
    }

    // From Line 33096
    public static void mapNetHandlerPlayClient() {
        try {
            Class<?> clazz = get("NetHandlerPlayClient");
            if (clazz == null) {
                warnMappingSkip("mapNetHandlerPlayClient", "NetHandlerPlayClient is not mapped");
                return;
            }

            for (Method m : clazz.getDeclaredMethods()) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 1 && params[0].equals(UUID.class)) {
                    putMethod("NetHandlerPlayClient.getPlayerInfo", m);
                    break;
                }
            }
        } catch (Exception e) {
            warnMappingSkip("mapNetHandlerPlayClient", e.getMessage());
        }
    }

    // From Line 33116
    public static boolean isFloatContainerMethod(MethodNode mn) {
        if (!"(F)F".equals(mn.desc))
            return false;

        boolean hasMonitor = false;
        boolean hasFload1 = false;
        boolean hasDoubleInvoke = false;
        boolean hasPutField = false;

        AbstractInsnNode prevInvoke = null;

        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();

            if (op == Opcodes.MONITORENTER || op == Opcodes.MONITOREXIT)
                hasMonitor = true;

            if (op == Opcodes.FLOAD && ((VarInsnNode) insn).var == 1)
                hasFload1 = true;

            if (op == Opcodes.PUTFIELD)
                hasPutField = true;

            if (op == Opcodes.INVOKEVIRTUAL) {
                if (prevInvoke != null && prevInvoke.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                    MethodInsnNode m1 = (MethodInsnNode) prevInvoke;
                    MethodInsnNode m2 = (MethodInsnNode) insn;

                    if ("()F".equals(m1.desc) && "(F)F".equals(m2.desc)) {
                        hasDoubleInvoke = true;
                    }
                }
                prevInvoke = insn;
            }
        }

        return hasMonitor && hasFload1 && hasDoubleInvoke && hasPutField;
    }

    // From Line 33155
    public static void processFloatContainerClasses() {
        Vector<Class<?>> classes = new Vector<>(getClasses());
        Class<?> entityLivingBase = get("EntityLivingBase");
        if (entityLivingBase == null) {
            System.out.println("EntityLivingBase class bulunamadı!");
            return;
        }

        Map<Class<?>, List<Field>> fieldTypes = new HashMap<>();
        for (Field field : entityLivingBase.getDeclaredFields()) {
            field.setAccessible(true);
            Class<?> fieldType = field.getType();
            if (fieldType.getName().startsWith("craftrise.")) {
                fieldTypes.computeIfAbsent(fieldType, k -> new ArrayList<>()).add(field);
            }
        }

        Class<?> targetType = null;

        for (Map.Entry<Class<?>, List<Field>> entry : fieldTypes.entrySet()) {
            if (entry.getValue().size() >= 4) {
                targetType = entry.getKey();
                break;
            }
        }

        if (targetType == null)
            return;

        boolean fieldsSetInConstructor = false;
        int newInstanceCount = 0;

        try {
            byte[] classBytes = getClassBytes(entityLivingBase);
            if (classBytes != null) {
                ClassNode cn = new ClassNode();
                new ClassReader(classBytes).accept(cn, 0);

                for (MethodNode method : cn.methods) {
                    if ("<init>".equals(method.name)) {
                        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn
                                .getNext()) {

                            if (insn.getOpcode() == Opcodes.NEW) {
                                TypeInsnNode tin = (TypeInsnNode) insn;
                                if (tin.desc.equals(org.objectweb.asm.Type.getInternalName(targetType))) {
                                    newInstanceCount++;
                                }
                            }
                        }
                    }
                }
                if (newInstanceCount >= 4)
                    fieldsSetInConstructor = true;
            }
        } catch (Exception ignored) {
        }

        if (!fieldsSetInConstructor)
            return;
        put("FloatContainer", targetType);
        Method setValueMethod = findFloatSetValueMethod(targetType);
        if (setValueMethod != null) {
            putMethod("FloatContainer.setValue", setValueMethod);
        }
        Method floatMethod = findRealFloatGetter(targetType);
        if (floatMethod != null) {
            putMethod("FloatContainer.getFloatValue", floatMethod);
        }
    }

    // From Line 33226
    private static Method findFloatSetValueMethod(Class<?> cls) {
        try {
            byte[] bytes = getClassBytes(cls);
            if (bytes == null)
                return null;

            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, 0);

            for (MethodNode mn : cn.methods) {
                if (!"(F)F".equals(mn.desc))
                    continue;

                boolean monitor = false;
                boolean fload1 = false;
                boolean putfield = false;
                boolean doubleInvoke = false;

                AbstractInsnNode lastInvoke = null;

                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {

                    int op = insn.getOpcode();

                    if (op == Opcodes.MONITORENTER || op == Opcodes.MONITOREXIT)
                        monitor = true;

                    if (op == Opcodes.FLOAD && ((VarInsnNode) insn).var == 1)
                        fload1 = true;

                    if (op == Opcodes.PUTFIELD)
                        putfield = true;

                    if (op == Opcodes.INVOKEVIRTUAL) {
                        if (lastInvoke != null) {
                            MethodInsnNode m1 = (MethodInsnNode) lastInvoke;
                            MethodInsnNode m2 = (MethodInsnNode) insn;

                            if ("()F".equals(m1.desc) && "(F)F".equals(m2.desc))
                                doubleInvoke = true;
                        }
                        lastInvoke = insn;
                    }
                }

                if (monitor && fload1 && putfield && doubleInvoke) {

                    for (Method m : cls.getDeclaredMethods()) {
                        if (m.getName().equals(mn.name) &&
                                org.objectweb.asm.Type.getMethodDescriptor(m).equals(mn.desc)) {
                            m.setAccessible(true);
                            return m;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    // From Line 33288
    public static Method findRealFloatGetter(Class<?> targetType) {
        try {
            byte[] classBytes = getClassBytes(targetType);
            if (classBytes == null)
                return null;

            ClassReader cr = new ClassReader(classBytes);
            ClassNode cn = new ClassNode();
            cr.accept(cn, 0);

            for (MethodNode mn : (List<MethodNode>) cn.methods) {

                if ((mn.access & Opcodes.ACC_PUBLIC) == 0)
                    continue;
                if (!"()F".equals(mn.desc))
                    continue;

                boolean looksLikeRealGetter = false;

                if ((mn.access & Opcodes.ACC_SYNCHRONIZED) != 0) {
                    looksLikeRealGetter = true;
                } else {

                    for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {

                        int op = insn.getOpcode();

                        if (op == Opcodes.MONITORENTER || op == Opcodes.MONITOREXIT) {
                            looksLikeRealGetter = true;
                            break;
                        }

                        if (insn instanceof MethodInsnNode) {
                            MethodInsnNode min = (MethodInsnNode) insn;

                            if (min.owner.equals("java/lang/Runtime") && min.name.equals("halt")) {
                                looksLikeRealGetter = true;
                                break;
                            }

                            if (min.owner.equals("java/io/PrintStream") && min.name.equals("println")) {
                                looksLikeRealGetter = true;
                                break;
                            }
                        }
                    }
                }

                if (!looksLikeRealGetter)
                    continue;

                try {
                    Method m = targetType.getDeclaredMethod(mn.name);
                    m.setAccessible(true);
                    return m;
                } catch (Exception ignore) {

                    for (Method mm : targetType.getDeclaredMethods()) {
                        if (mm.getName().equals(mn.name)
                                && mm.getParameterCount() == 0
                                && mm.getReturnType() == float.class) {

                            mm.setAccessible(true);
                            return mm;
                        }
                    }
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return null;
    }

    // From Line 33429
    public static void extractKeyBindings(Class<?> gameSettingsClass) {
        try {
            if (gameSettingsClass == null) {
                warnMappingSkip("extractKeyBindings", "GameSettings is not mapped");
                return;
            }
            Object gsInstance = gameSettingsClass
                    .getConstructor(get("Minecraft"), File.class)
                    .newInstance(getMinecraft(), new File("dummy"));

            Field keyCodeField = getField("KeyBinding.keyCode");
            if (keyCodeField == null) {
                warnMappingSkip("extractKeyBindings", "KeyBinding.keyCode is not mapped");
                return;
            }
            keyCodeField.setAccessible(true);
            for (Field f : gameSettingsClass.getDeclaredFields()) {
                f.setAccessible(true);

                Object fieldValue = f.get(gsInstance);
                if (fieldValue == null)
                    continue;

                Class<?> fieldType = fieldValue.getClass();

                if (fieldType.equals(get("KeyBinding"))) {
                    int code = keyCodeField.getInt(fieldValue);

                    switch (code) {
                        case 17:
                            putField("GameSettings.keyBindForward", f);
                            break;
                        case 30:
                            putField("GameSettings.keyBindLeft", f);
                            break;
                        case 31:
                            putField("GameSettings.keyBindBack", f);
                            break;
                        case 32:
                            putField("GameSettings.keyBindRight", f);
                            break;
                        case 57:
                            putField("GameSettings.keyBindJump", f);
                            break;
                        case 42:
                            putField("GameSettings.keyBindSneak", f);
                            break;
                        case 29:
                            putField("GameSettings.keyBindSprint", f);
                            break;
                    }
                }

                if (fieldType.isArray() && fieldType.getComponentType().equals(get("KeyBinding"))) {
                    Object[] arr = (Object[]) fieldValue;

                    for (int i = 0; i < arr.length; i++) {
                        Object keyBind = arr[i];
                        int code = keyCodeField.getInt(keyBind);

                    }
                }
            }

        } catch (Throwable t) {
            t.printStackTrace();
        }
    }


    // ==========================================
    // Direct GL11 automapper helpers
    // ==========================================

    // Copied from gl11.java: findRenderPlayerMainModelOpcodes

    public static void findRenderPlayerMainModelOpcodes(Class<?> renderPlayerClass) {
        if (renderPlayerClass == null)
            return;

        put("RenderPlayer", renderPlayerClass);

        try {
            byte[] classBytes = getClassBytes(renderPlayerClass);
            if (missingClassBytes("findRenderPlayerMainModelOpcodes", renderPlayerClass, classBytes))
                return;

            ClassReader reader = new ClassReader(classBytes);
            ClassNode classNode = new ClassNode();
            reader.accept(classNode, 0);

            for (MethodNode mn : classNode.methods) {
                if ((mn.access & Opcodes.ACC_PUBLIC) != 0 && mn.desc.startsWith("()")) {
                    InsnList insns = mn.instructions;
                    boolean callsSuper = false;

                    for (AbstractInsnNode insn : insns) {
                        if (insn instanceof MethodInsnNode) {
                            MethodInsnNode mi = (MethodInsnNode) insn;
                            if (mi.getOpcode() == Opcodes.INVOKESPECIAL &&
                                    mi.owner.equals(org.objectweb.asm.Type.getInternalName(renderPlayerClass.getSuperclass()))) {
                                callsSuper = true;
                                break;
                            }
                        }
                    }

                    if (callsSuper) {
                        Method method = renderPlayerClass.getDeclaredMethod(mn.name);
                        putMethod("RenderPlayer.getMainModel", method);

                        break;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Copied from gl11.java: findGetRenderManager

    public static void findGetRenderManager() {
        Class<?> minecraftClass = get("Minecraft");
        Class<?> renderManagerClass = get("RenderManager");
        if (minecraftClass == null || renderManagerClass == null)
            return;

        for (Method method : minecraftClass.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())
                    && method.getReturnType().equals(renderManagerClass)
                    && method.getParameterCount() == 0) {
                putMethod("Minecraft.getRenderManager", method);
                break;
            }
        }
    }

    // Copied from gl11.java: findRenderManagerHooks

    public static void findRenderManagerHooks() throws IOException {
        Class<?> renderManagerClass = get("RenderManager");
        if (renderManagerClass == null)
            return;

        byte[] classBytes = getClassBytes(renderManagerClass);
        if (classBytes != null) {
            ClassReader cr = new ClassReader(classBytes);
            ClassNode classNode = new ClassNode();
            cr.accept(classNode, 0);

            List<MethodNode> methods = classNode.methods;
            MethodNode targetMethod = null;
            for (MethodNode method : methods) {
                if ((method.access & Opcodes.ACC_PUBLIC) != 0 && method.desc.equals("(DDD)V")) {
                    targetMethod = method;
                    break;
                }
            }

            if (targetMethod != null) {
                InsnList insns = targetMethod.instructions;
                List<FieldInsnNode> doubleFields = new ArrayList<>();

                for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == Opcodes.PUTFIELD) {
                        FieldInsnNode fin = (FieldInsnNode) insn;
                        if (fin.desc.equals("D"))
                            doubleFields.add(fin);
                    }
                }

                if (doubleFields.size() >= 3) {
                    try {
                        Field fieldX = renderManagerClass.getDeclaredField(doubleFields.get(0).name);
                        Field fieldY = renderManagerClass.getDeclaredField(doubleFields.get(1).name);
                        Field fieldZ = renderManagerClass.getDeclaredField(doubleFields.get(2).name);

                        putField("RenderManager.renderPosX", fieldX);
                        putField("RenderManager.renderPosY", fieldY);
                        putField("RenderManager.renderPosZ", fieldZ);
                    } catch (NoSuchFieldException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        Class<?> entityClass = get("Entity");
        Class<?> renderClass = get("Render");
        if (entityClass != null && renderClass != null) {
            for (Method method : renderManagerClass.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers()))
                    continue;

                Class<?>[] params = method.getParameterTypes();
                if (params.length == 1 && params[0].equals(entityClass)
                        && renderClass.isAssignableFrom(method.getReturnType())) {
                    method.setAccessible(true);
                    putMethod("RenderManager.getEntityRenderObject", method);
                    break;
                }
            }
        }
    }

    // Copied from gl11.java: findAddMappingCalls

    public static void findAddMappingCalls(Class<?> entityListClass) {
        try {
            Vector<Class<?>> classes = new Vector<>(getClasses());

            for (Class<?> clazz : classes) {
                try {
                    Method clinit = clazz.getDeclaredMethod("<clinit>");
                    clinit.setAccessible(true);
                    analyzeClassForEntityRegistrations(clazz);

                } catch (NoSuchMethodException e) {
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Copied from gl11.java: getPlayerEntitiesInWorld

    public static List<Object> getPlayerEntitiesInWorld() {
        List<Object> emptyList = Collections.emptyList();
        try {
            Object player = getThePlayer();
            Object worldObj = getWorldObj();
            Field playerEntitiesField = getField("World.playerEntities");
            if (player == null || worldObj == null || playerEntitiesField == null) {
                warnMappingSkip("getPlayerEntitiesInWorld", "player, world, or World.playerEntities is not available");
                return emptyList;
            }

            Object entityList = playerEntitiesField.get(worldObj);
            if (!(entityList instanceof List)) {
                return emptyList;
            }

            List<Object> entities = new ArrayList<>();
            for (Object entity : (List<?>) entityList) {
                if (entity != player) {
                    entities.add(entity);
                }
            }

            return entities;
        } catch (Exception e) {
            warnMappingSkip("getPlayerEntitiesInWorld", e.getMessage());
        }
        return emptyList;
    }

    // Copied from gl11.java: getName

    public static String getName(Object entity) {
        try {
            String s = String.valueOf(entity);
            int start = s.indexOf('\'') + 1;
            int end = s.indexOf('\'', start);
            if (start > 0 && end > start) {
                return s.substring(start, end);
            }
        } catch (Exception e) {
            System.out.println("İsim ayıklanamadı: " + e.getMessage());
        }
        return "Bilinmiyor";
    }
    // Copied from gl11.java: asmFieldToReflectionField


    public static Field asmFieldToReflectionField(Class<?> clazz, String fieldName, String fieldDesc) {
        try {
            Class<?> fieldType;
            String className = org.objectweb.asm.Type.getType(fieldDesc).getClassName();

            switch (className) {
                case "boolean":
                    fieldType = boolean.class;
                    break;
                case "byte":
                    fieldType = byte.class;
                    break;
                case "char":
                    fieldType = char.class;
                    break;
                case "short":
                    fieldType = short.class;
                    break;
                case "int":
                    fieldType = int.class;
                    break;
                case "long":
                    fieldType = long.class;
                    break;
                case "float":
                    fieldType = float.class;
                    break;
                case "double":
                    fieldType = double.class;
                    break;
                case "void":
                    fieldType = void.class;
                    break;
                default:
                    fieldType = Class.forName(className, false, clazz.getClassLoader());
            }

            Field field = clazz.getDeclaredField(fieldName);

            if (field.getType().equals(fieldType)) {
                return field;
            } else {
                return null;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }



    // Copied from gl11.java: asmMethodToReflectionMethod

    public static Method asmMethodToReflectionMethod(Class<?> clazz, String methodName, String methodDesc) {
        try {
            org.objectweb.asm.Type[] argTypes = org.objectweb.asm.Type.getArgumentTypes(methodDesc);
            Class<?>[] paramClasses = new Class<?>[argTypes.length];
            ClassLoader loader = clazz.getClassLoader();

            for (int i = 0; i < argTypes.length; i++) {
                paramClasses[i] = Class.forName(argTypes[i].getClassName(), false, loader);
            }

            return clazz.getDeclaredMethod(methodName, paramClasses);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // Copied from gl11.java: findBlockInChunk

    private static void findBlockInChunk(Class<?> chunkClass) {
        Class<?> blockPosClass = get("BlockPos");
        if (chunkClass == null || blockPosClass == null) {
            warnMappingSkip("findBlockInChunk", "Chunk or BlockPos mapping is missing");
            return;
        }

        Map<Class<?>, Integer> returnTypeCount = new HashMap<>();
        Method targetMethod = null;

        for (Method method : chunkClass.getDeclaredMethods()) {
            int mods = method.getModifiers();
            Class<?>[] params = method.getParameterTypes();
            Class<?> returnType = method.getReturnType();

            if (Modifier.isPublic(mods)
                    && !Modifier.isStatic(mods)
                    && params.length == 1
                    && blockPosClass.equals(params[0])
                    && !blockPosClass.equals(returnType)
                    && !returnType.isPrimitive()) {

                returnTypeCount.put(returnType, returnTypeCount.getOrDefault(returnType, 0) + 1);
            }
        }

        for (Method method : chunkClass.getDeclaredMethods()) {
            int mods = method.getModifiers();
            Class<?>[] params = method.getParameterTypes();
            Class<?> returnType = method.getReturnType();

            if (Modifier.isPublic(mods)
                    && !Modifier.isStatic(mods)
                    && params.length == 1
                    && blockPosClass.equals(params[0])
                    && !blockPosClass.equals(returnType)
                    && !returnType.isPrimitive()) {

                if (returnTypeCount.get(returnType) == 1) {
                    targetMethod = method;
                    put("Block", returnType);
                    findGetBlockInChunk();
                    break;
                }
            }
        }
    }

    // Copied from gl11.java: containsMinecraftFields

    public static boolean containsMinecraftFields(Class<?> cls) {
        boolean hasLogger = false, hasProxy = false, hasDisplay = false;
        for (Field f : cls.getDeclaredFields()) {
            f.setAccessible(true);
            String name = f.getType().getName();
            int mod = f.getModifiers();
            if (name.equals("org.apache.logging.log4j.Logger") && Modifier.isStatic(mod) && Modifier.isFinal(mod))
                hasLogger = true;
            if (name.equals("java.net.Proxy") && Modifier.isFinal(mod))
                hasProxy = true;
            if (name.equals("java.util.List") && f.getGenericType().getTypeName().contains("DisplayMode")
                    && Modifier.isStatic(mod) && Modifier.isFinal(mod))
                hasDisplay = true;
        }
        return hasLogger && hasProxy && hasDisplay;
    }

    // Copied from gl11.java: isEntityPlayerSP

    public static boolean isEntityPlayerSP(Class<?> cls) {
        Class<?> minecraftClass = get("Minecraft");
        if (cls == null || minecraftClass == null || !isGameRuntimeClass(cls)) {
            return false;
        }

        boolean hasField = false;
        for (Field f : cls.getDeclaredFields()) {
            if (Modifier.isProtected(f.getModifiers()) && f.getType().equals(minecraftClass)) {
                hasField = true;
                break;
            }
        }

        if (!hasField || !isEntityPlayerSPHierarchy(cls)) {
            return false;
        }

        for (Constructor<?> c : cls.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 4
                    && p[0].equals(minecraftClass)
                    && isGameRuntimeClass(p[1])
                    && isGameRuntimeClass(p[2])) {
                put("World", p[1]);
                put("NetHandlerPlayClient", p[2]);
                mapNetHandlerPlayClient();
                for (Constructor<?> worldConstructor : p[1].getDeclaredConstructors()) {
                    Class<?>[] worldParams = worldConstructor.getParameterTypes();
                    if (worldParams.length == 5
                            && isGameRuntimeClass(worldParams[0])
                            && isGameRuntimeClass(worldParams[1])
                            && isGameRuntimeClass(worldParams[2])
                            && isGameRuntimeClass(worldParams[3])) {
                        put("ISaveHandler", worldParams[0]);
                        put("WorldInfo", worldParams[1]);
                        put("WorldProvider", worldParams[2]);
                        put("Profiler", worldParams[3]);
                        break;
                    }
                }
                return true;
            }
        }

        return false;
    }

    // Copied from gl11.java: isIChatComponent

    public static boolean isIChatComponent(Class<?> clazz) {
        if (!clazz.isInterface())
            return false;

        java.lang.reflect.Type[] interfaces = clazz.getGenericInterfaces();
        boolean extendsIterableOfSelf = false;

        for (java.lang.reflect.Type iface : interfaces) {
            if (iface instanceof ParameterizedType) {
                ParameterizedType pt = (ParameterizedType) iface;
                if (pt.getRawType() == Iterable.class) {
                    java.lang.reflect.Type[] typeArgs = pt.getActualTypeArguments();
                    if (typeArgs.length == 1 && typeArgs[0] == clazz) {
                        extendsIterableOfSelf = true;
                        break;
                    }
                }
            }
        }
        if (!extendsIterableOfSelf)
            return false;

        for (Method method : clazz.getMethods()) {
            if (method.getReturnType() == clazz &&
                    method.getParameterCount() == 1 &&
                    method.getParameterTypes()[0] == String.class) {
                return true;
            }
        }

        return false;
    }

    // Copied from gl11.java: isChatComponentStyle

    public static boolean isChatComponentStyle(Class<?> clazz) {
        if (!Modifier.isAbstract(clazz.getModifiers())) {
            return false;
        }

        if (!get("IChatComponent").isAssignableFrom(clazz)) {
            return false;
        }

        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
            if (Modifier.isProtected(field.getModifiers()) && List.class.isAssignableFrom(field.getType())) {
                java.lang.reflect.Type genericType = field.getGenericType();
                if (genericType instanceof ParameterizedType) {
                    ParameterizedType pt = (ParameterizedType) genericType;
                    java.lang.reflect.Type[] typeArgs = pt.getActualTypeArguments();
                    if (typeArgs.length == 1 && typeArgs[0] instanceof Class<?>) {
                        if (((Class<?>) typeArgs[0]).equals(get("IChatComponent"))) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    // Copied from gl11.java: isChatComponentText

    public static boolean isChatComponentText(Class<?> clazz) {
        if (!get("ChatComponentStyle").isAssignableFrom(clazz)) {
            return false;
        }

        boolean hasPrivateFinalString = false;
        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
            int mods = field.getModifiers();
            if (Modifier.isPrivate(mods) && Modifier.isFinal(mods) && field.getType() == String.class) {
                hasPrivateFinalString = true;
                break;
            }
        }

        if (!hasPrivateFinalString)
            return false;

        Constructor<?>[] constructors = clazz.getDeclaredConstructors();
        for (Constructor<?> constructor : constructors) {
            Class<?>[] params = constructor.getParameterTypes();
            if (params.length == 1 && params[0] == String.class) {
                return true;
            }
        }

        return false;
    }

    // Copied from gl11.java: getMethodByParamAndReturnTypeAndVisibility

    public static Method getMethodByParamAndReturnTypeAndVisibility(Class<?> inWhoClass, Class<?>[] paramTypes,
            Class<?> returnType, String visibility) {
        Method[] methods = inWhoClass.getDeclaredMethods();

        for (Method method : methods) {
            if (!Arrays.equals(method.getParameterTypes(), paramTypes)) {
                continue;
            }

            if (!method.getReturnType().equals(returnType)) {
                continue;
            }

            int mods = method.getModifiers();
            boolean visible = false;

            switch (visibility.toLowerCase()) {
                case "public":
                    visible = Modifier.isPublic(mods);
                    break;
                case "private":
                    visible = Modifier.isPrivate(mods);
                    break;
                case "protected":
                    visible = Modifier.isProtected(mods);
                    break;
                case "package":
                case "default":
                    visible = !(Modifier.isPublic(mods) || Modifier.isPrivate(mods) || Modifier.isProtected(mods));
                    break;
                case "any":
                    visible = true;
                    break;
                default:
                    throw new IllegalArgumentException(
                            "Visibility must be one of: public, private, protected, package, any");
            }

            if (visible) {
                return method;
            }
        }

        return null;
    }

    // Copied from gl11.java: findEffectRenderer

    public static void findEffectRenderer() {
        Class<?> worldClass = get("World");
        Class<?> textureManagerClass = get("TextureManager");

        if (worldClass == null || textureManagerClass == null)
            return;

        for (Class<?> clazz : getClasses()) {
            boolean hasProtectedWorld = false;
            boolean hasPrivateList = false;
            boolean hasPrivateTextureManager = false;
            boolean hasPrivateMap = false;

            for (Field f : clazz.getDeclaredFields()) {
                int mods = f.getModifiers();
                Class<?> type = f.getType();

                if (Modifier.isProtected(mods) && type.equals(worldClass)) {
                    hasProtectedWorld = true;
                }

                if (Modifier.isPrivate(mods) && type.equals(java.util.List.class)) {
                    hasPrivateList = true;
                }

                if (Modifier.isPrivate(mods) && type.equals(textureManagerClass)) {
                    hasPrivateTextureManager = true;
                }

                if (Modifier.isPrivate(mods) && type.equals(java.util.Map.class)) {
                    hasPrivateMap = true;
                }
            }

            if (hasProtectedWorld && hasPrivateList && hasPrivateTextureManager && hasPrivateMap) {
                put("EffectRenderer", clazz);
                return;
            }
        }
    }

    // Copied from gl11.java: findC06PacketPlayerPosLook

    public static void findC06PacketPlayerPosLook(List<Class<?>> classes) {
        for (Class<?> clazz : classes) {
            String name = clazz.getName();
            if (!name.contains("$"))
                continue;
            if (clazz.getSuperclass() == Object.class)
                continue;

            Constructor<?>[] constructors = clazz.getDeclaredConstructors();
            if (constructors.length != 2)
                continue;

            boolean hasNoArg = false;
            boolean hasCorrectParams = false;

            for (Constructor<?> constructor : constructors) {
                Class<?>[] params = constructor.getParameterTypes();
                if (params.length == 0) {
                    hasNoArg = true;
                } else if (params.length == 6 &&
                        params[0] == double.class &&
                        params[1] == double.class &&
                        params[2] == double.class &&
                        params[3] == float.class &&
                        params[4] == float.class &&
                        params[5] == boolean.class) {
                    hasCorrectParams = true;
                }
            }

            if (hasNoArg && hasCorrectParams) {
                put("C06PacketPlayerPosLook", clazz);
                findC03PacketPlayerFromC06(classes);
                return;
            }
        }
    }

    // Copied from gl11.java: findPlayerCapabilitiesMethods

    public static void findPlayerCapabilitiesMethods(
            Class<?> netHandlerClass,
            Class<?> packetClass,
            Class<?> playerCapsClass) throws Exception {

        byte[] bytes = getClassBytes(netHandlerClass);
        if (missingClassBytes("findPlayerCapabilitiesMethods", netHandlerClass, bytes))
            return;
        if (packetClass == null || playerCapsClass == null) {
            warnMappingSkip("findPlayerCapabilitiesMethods",
                    "S39PacketPlayerAbilities or PlayerCapabilities is not mapped");
            return;
        }

        ClassReader cr = new ClassReader(bytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        String packetInternal = packetClass.getName().replace('.', '/');
        String capsInternal = playerCapsClass.getName().replace('.', '/');

        int boolIndex = 0;
        int floatGetterIndex = 0;
        int floatSetterIndex = 0;

        for (MethodNode mn : cn.methods) {
            if (mn.desc.equals("(L" + packetInternal + ";)V")) {

                for (AbstractInsnNode insn : mn.instructions) {

                    if (insn.getOpcode() != INVOKEVIRTUAL)
                        continue;

                    MethodInsnNode min = (MethodInsnNode) insn;
                    String owner = min.owner;
                    String name = min.name;
                    String desc = min.desc;
                    if (owner.equals(packetInternal)) {
                        if (desc.equals("()Z")) {
                            Method m = packetClass.getDeclaredMethod(name);

                            switch (boolIndex) {
                                case 0:
                                    putMethod("S39PacketPlayerAbilities.isFlying", m);
                                    break;
                                case 1:
                                    putMethod("S39PacketPlayerAbilities.isCreativeMode", m);
                                    break;
                                case 2:
                                    putMethod("S39PacketPlayerAbilities.isInvulnerable", m);
                                    break;
                                case 3:
                                    putMethod("S39PacketPlayerAbilities.allowFlying", m);
                                    break;
                            }
                            boolIndex++;
                        } else if (desc.equals("()F")) {
                            Method m = packetClass.getDeclaredMethod(name);

                            switch (floatGetterIndex) {
                                case 0:
                                    putMethod("S39PacketPlayerAbilities.getFlySpeed", m);
                                    break;
                                case 1:
                                    putMethod("S39PacketPlayerAbilities.getWalkSpeed", m);
                                    break;
                            }
                            floatGetterIndex++;
                        }
                    } else if (owner.equals(capsInternal)) {

                        if (desc.equals("(F)V")) {
                            Method m = playerCapsClass.getDeclaredMethod(name, float.class);

                            switch (floatSetterIndex) {
                                case 0:
                                    putMethod("PlayerCapabilities.setFlySpeed", m);
                                    break;
                                case 1:
                                    putMethod("PlayerCapabilities.setWalkSpeed", m);
                                    break;
                            }
                            floatSetterIndex++;
                        }
                    }
                }

                return;
            }
        }
    }

    // Copied from gl11.java: extractAndSaveHandlerClientClass

    private static boolean extractAndSaveHandlerClientClass(java.lang.reflect.Type handlerType) {

        if (handlerType instanceof Class) {
            Class<?> handlerClass = (Class<?>) handlerType;
            put("INetHandlerPlayClient", handlerClass);
            return true;
        } else if (handlerType instanceof java.lang.reflect.ParameterizedType) {
            java.lang.reflect.ParameterizedType paramType = (java.lang.reflect.ParameterizedType) handlerType;
            java.lang.reflect.Type rawType = paramType.getRawType();
            if (rawType instanceof Class) {
                Class<?> handlerClass = (Class<?>) rawType;
                put("INetHandlerPlayClient", handlerClass);
                return true;
            }
        } else if (handlerType instanceof java.lang.reflect.WildcardType) {
            java.lang.reflect.WildcardType wildcard = (java.lang.reflect.WildcardType) handlerType;
            java.lang.reflect.Type[] upperBounds = wildcard.getUpperBounds();
            if (upperBounds.length > 0 && upperBounds[0] instanceof Class) {
                Class<?> handlerClass = (Class<?>) upperBounds[0];
                put("INetHandlerPlayClient", handlerClass);
                return true;
            }
        }

        return false;
    }

    // Copied from gl11.java: extractAndSaveHandlerClass

    private static boolean extractAndSaveHandlerClass(java.lang.reflect.Type handlerType) {

        if (handlerType instanceof Class) {
            Class<?> handlerClass = (Class<?>) handlerType;
            put("INetHandlerPlayServer", handlerClass);

            return true;
        } else if (handlerType instanceof java.lang.reflect.ParameterizedType) {
            java.lang.reflect.ParameterizedType paramType = (java.lang.reflect.ParameterizedType) handlerType;
            java.lang.reflect.Type rawType = paramType.getRawType();
            if (rawType instanceof Class) {
                Class<?> handlerClass = (Class<?>) rawType;
                put("INetHandlerPlayServer", handlerClass);

                return true;
            }
        } else if (handlerType instanceof java.lang.reflect.WildcardType) {
            java.lang.reflect.WildcardType wildcard = (java.lang.reflect.WildcardType) handlerType;
            java.lang.reflect.Type[] upperBounds = wildcard.getUpperBounds();
            if (upperBounds.length > 0 && upperBounds[0] instanceof Class) {
                Class<?> handlerClass = (Class<?>) upperBounds[0];
                put("INetHandlerPlayServer", handlerClass);

                return true;
            }
        }

        return false;
    }


    // Copied from gl11.java: isEntityClassType

    private static boolean isEntityClassType(java.lang.reflect.Type type, Class<?> entityClass) {
        if (type instanceof ParameterizedType) {
            ParameterizedType paramType = (ParameterizedType) type;
            if (paramType.getRawType() == Class.class) {
                java.lang.reflect.Type[] typeArgs = paramType.getActualTypeArguments();
                if (typeArgs.length == 1 && typeArgs[0] instanceof WildcardType) {
                    WildcardType wildcard = (WildcardType) typeArgs[0];
                    java.lang.reflect.Type[] upperBounds = wildcard.getUpperBounds();
                    return upperBounds.length == 1 && upperBounds[0] == entityClass;
                }
            }
        }
        return false;
    }

    // Copied from gl11.java: getDescriptor

    private static String getDescriptor(Field field) {
        Class<?> type = field.getType();
        if (type.isPrimitive()) {
            if (type == int.class)
                return "I";
            if (type == boolean.class)
                return "Z";
            if (type == short.class)
                return "S";
            if (type == long.class)
                return "J";
            if (type == char.class)
                return "C";
            if (type == byte.class)
                return "B";
            if (type == float.class)
                return "F";
            if (type == double.class)
                return "D";
        } else {
            return "L" + type.getName().replace('.', '/') + ";";
        }
        return null;
    }

    // Copied from gl11.java: matchParams

    private static boolean matchParams(Method method, Class<?>... expected) {
        Class<?>[] params = method.getParameterTypes();
        if (params.length != expected.length)
            return false;

        for (int i = 0; i < params.length; i++) {
            if (!params[i].equals(expected[i]))
                return false;
        }
        return true;
    }

    // Copied from gl11.java: analyzeClassForEntityRegistrations

    public static void analyzeClassForEntityRegistrations(Class<?> clazz) {
        try {
            String className = clazz.getName().replace('.', '/') + ".class";
            InputStream is = clazz.getClassLoader().getResourceAsStream(className);
            if (is == null)
                return;

            byte[] bytecode = readAllBytes(is);
            is.close();
            String bytecodeStr = new String(bytecode);
            Pattern pattern = Pattern.compile("addMapping\\(L([^;]+);");
            Matcher matcher = pattern.matcher(bytecodeStr);

            while (matcher.find()) {
                String obfuscatedName = matcher.group(1);

            }

        } catch (Exception e) {
        }
    }

    // Copied from gl11.java: getWorldObj

    public static Object getWorldObj() {
        try {
            Field worldObjField = getField("EntityPlayerSP.worldObj");
            if (worldObjField == null) {
                return null;
            }

            Object player = getThePlayer();
            if (player == null) {
                return null;
            }

            worldObjField.setAccessible(true);

            return worldObjField.get(player);

        } catch (IllegalArgumentException | IllegalAccessException e) {
            e.printStackTrace();
        }
        return null;
    }

    // Copied from gl11.java: findGetBlockInChunk

    public static void findGetBlockInChunk() {
        Class<?> chunkClass = get("Chunk");
        Class<?> blockClass = get("Block");
        if (chunkClass == null || blockClass == null)
            return;

        for (Method method : chunkClass.getDeclaredMethods()) {
            int mods = method.getModifiers();
            Class<?>[] params = method.getParameterTypes();

            if (Modifier.isPublic(mods)
                    && params.length == 3
                    && params[0] == int.class
                    && params[1] == int.class
                    && params[2] == int.class
                    && method.getReturnType().equals(blockClass)) {
                putMethod("Chunk.getBlock", method);
                findGetIdFromBlock();
                break;
            }
        }
    }

    // Copied from gl11.java: findC03PacketPlayerFromC06

    public static void findC03PacketPlayerFromC06(List<Class<?>> classes) {
        Class<?> c06 = get("C06PacketPlayerPosLook");
        if (c06 == null) {

            return;
        }

        String c06Name = c06.getName();

        if (!c06Name.contains("$")) {
            return;
        }

        String outerName = c06Name.split("\\$")[0];

        for (Class<?> clazz : classes) {
            if (clazz.getName().equals(outerName)) {
                put("C03PacketPlayer", clazz);
                findC04PacketPlayerPosition(classes);
                findC05PacketPlayerLook(classes);
                for (Field field : clazz.getDeclaredFields()) {
                    if (field.getType() == boolean.class) {
                        putField("C03PacketPlayer.onGround", field);
                        break;
                    }
                }
                return;
            }
        }
    }


    // Copied from gl11.java: readAllBytes

    private static byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[16384];
        while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return buffer.toByteArray();
    }

    // Copied from gl11.java: findGetIdFromBlock

    public static void findGetIdFromBlock() {
        Class<?> blockClass = get("Block");
        if (blockClass == null)
            return;

        for (Method method : blockClass.getDeclaredMethods()) {
            int mods = method.getModifiers();
            Class<?>[] params = method.getParameterTypes();

            if (Modifier.isPublic(mods) && Modifier.isStatic(mods)
                    && method.getReturnType() == int.class
                    && params.length == 1
                    && params[0].equals(blockClass)) {
                putMethod("Block.getIdFromBlock", method);
                break;
            }
        }
    }

    // Copied from gl11.java: findC04PacketPlayerPosition

    public static void findC04PacketPlayerPosition(List<Class<?>> classes) {
        Class<?> c03 = get("C03PacketPlayer");
        if (c03 == null) {
            System.out.println("C03PacketPlayer bulunamadı.");
            return;
        }

        for (Class<?> clazz : classes) {
            if (clazz.getSuperclass() != c03)
                continue;

            int constructorMatchCount = 0;

            for (Constructor<?> constructor : clazz.getDeclaredConstructors()) {
                Class<?>[] params = constructor.getParameterTypes();

                if (params.length == 0) {
                    constructorMatchCount++;
                } else if (params.length == 4 &&
                        params[0] == double.class &&
                        params[1] == double.class &&
                        params[2] == double.class &&
                        params[3] == boolean.class) {
                    constructorMatchCount++;
                }
            }

            if (constructorMatchCount == 2) {
                put("C04PacketPlayerPosition", clazz);

                return;
            }
        }

    }

    // =========================================================================
    // PLAYER ACCESSORS & SETTERS (Fly, Speed, etc. modülleri için)
    // =========================================================================

    /** motionX/Y/Z wrapper'ı günceller — CraftRise MotionContainer pattern */
    private static void updateMotionField(Field field, Object player, double value) throws Exception {
        Object current = field.get(player);
        if (current == null) return;
        Constructor<?> ctor = current.getClass().getDeclaredConstructor(double.class);
        ctor.setAccessible(true);
        field.set(player, ctor.newInstance(value));
    }

    public static double getMotionX() {
        try {
            Object player = getPlayer();
            if (player == null) return 0.0;
            Field f = getField("Entity.motionX");
            if (f == null) return 0.0;
            f.setAccessible(true);
            Object obj = f.get(player);
            if (obj == null) return 0.0;
            // MotionContainer.getValue() veya direkt double
            if (obj instanceof Double) return (Double) obj;
            Method getValue = getMethod("MotionContainer.getValue");
            if (getValue != null) return (double) getValue.invoke(obj);
            // fallback: ilk double field
            for (Field df : obj.getClass().getDeclaredFields()) {
                if (df.getType() == double.class) { df.setAccessible(true); return df.getDouble(obj); }
            }
        } catch (Exception ignored) {}
        return 0.0;
    }

    public static double getMotionY() {
        try {
            Object player = getPlayer();
            if (player == null) return 0.0;
            Field f = getField("Entity.motionY");
            if (f == null) return 0.0;
            f.setAccessible(true);
            Object obj = f.get(player);
            if (obj == null) return 0.0;
            if (obj instanceof Double) return (Double) obj;
            Method getValue = getMethod("MotionContainer.getValue");
            if (getValue != null) return (double) getValue.invoke(obj);
            for (Field df : obj.getClass().getDeclaredFields()) {
                if (df.getType() == double.class) { df.setAccessible(true); return df.getDouble(obj); }
            }
        } catch (Exception ignored) {}
        return 0.0;
    }

    public static double getMotionZ() {
        try {
            Object player = getPlayer();
            if (player == null) return 0.0;
            Field f = getField("Entity.motionZ");
            if (f == null) return 0.0;
            f.setAccessible(true);
            Object obj = f.get(player);
            if (obj == null) return 0.0;
            if (obj instanceof Double) return (Double) obj;
            Method getValue = getMethod("MotionContainer.getValue");
            if (getValue != null) return (double) getValue.invoke(obj);
            for (Field df : obj.getClass().getDeclaredFields()) {
                if (df.getType() == double.class) { df.setAccessible(true); return df.getDouble(obj); }
            }
        } catch (Exception ignored) {}
        return 0.0;
    }

    public static void setMotionX(double value) {
        try {
            Object player = getPlayer();
            if (player == null) return;
            Field f = getField("Entity.motionX");
            if (f == null) return;
            f.setAccessible(true);
            Object current = f.get(player);
            if (current instanceof Double) { f.set(player, value); return; }
            updateMotionField(f, player, value);
        } catch (Exception e) { e.printStackTrace(); }
    }

    public static void setMotionY(double value) {
        try {
            Object player = getPlayer();
            if (player == null) return;
            Field f = getField("Entity.motionY");
            if (f == null) return;
            f.setAccessible(true);
            Object current = f.get(player);
            if (current instanceof Double) { f.set(player, value); return; }
            updateMotionField(f, player, value);
        } catch (Exception e) { e.printStackTrace(); }
    }

    public static void setMotionZ(double value) {
        try {
            Object player = getPlayer();
            if (player == null) return;
            Field f = getField("Entity.motionZ");
            if (f == null) return;
            f.setAccessible(true);
            Object current = f.get(player);
            if (current instanceof Double) { f.set(player, value); return; }
            updateMotionField(f, player, value);
        } catch (Exception e) { e.printStackTrace(); }
    }

    public static double getPosX() {
        try {
            Object player = getPlayer();
            if (player == null) return 0.0;
            Field f = getField("Entity.posX");
            if (f == null) return 0.0;
            f.setAccessible(true);
            return f.getDouble(player);
        } catch (Exception ignored) {}
        return 0.0;
    }

    public static double getPosY() {
        try {
            Object player = getPlayer();
            if (player == null) return 0.0;
            Field f = getField("Entity.posY");
            if (f == null) return 0.0;
            f.setAccessible(true);
            return f.getDouble(player);
        } catch (Exception ignored) {}
        return 0.0;
    }

    public static double getPosZ() {
        try {
            Object player = getPlayer();
            if (player == null) return 0.0;
            Field f = getField("Entity.posZ");
            if (f == null) return 0.0;
            f.setAccessible(true);
            return f.getDouble(player);
        } catch (Exception ignored) {}
        return 0.0;
    }

    public static double getPosX(Object entity) {
        try {
            Field f = getField("Entity.posX");
            if (f == null) return 0.0;
            f.setAccessible(true);
            return f.getDouble(entity);
        } catch (Exception ignored) {}
        return 0.0;
    }

    public static double getPosY(Object entity) {
        try {
            Field f = getField("Entity.posY");
            if (f == null) return 0.0;
            f.setAccessible(true);
            return f.getDouble(entity);
        } catch (Exception ignored) {}
        return 0.0;
    }

    public static double getPosZ(Object entity) {
        try {
            Field f = getField("Entity.posZ");
            if (f == null) return 0.0;
            f.setAccessible(true);
            return f.getDouble(entity);
        } catch (Exception ignored) {}
        return 0.0;
    }

    public static float getRotationYaw() {
        try {
            Object player = getPlayer();
            if (player == null) return 0f;
            Field f = getField("Entity.rotationYaw");
            if (f == null) return 0f;
            f.setAccessible(true);
            return f.getFloat(player);
        } catch (Exception ignored) {}
        return 0f;
    }

    public static float getRotationPitch() {
        try {
            Object player = getPlayer();
            if (player == null) return 0f;
            Field f = getField("Entity.rotationPitch");
            if (f == null) return 0f;
            f.setAccessible(true);
            return f.getFloat(player);
        } catch (Exception ignored) {}
        return 0f;
    }

    public static void setOnGround(boolean value) {
        try {
            Object player = getPlayer();
            if (player == null) return;
            Field f = getField("Entity.onGround");
            if (f == null) return;
            f.setAccessible(true);
            // onGround BooleanContainer veya boolean olabilir
            Object current = f.get(player);
            if (current instanceof Boolean) {
                f.set(player, value);
            } else if (current != null) {
                // BooleanContainer — boolean field'ını bul
                for (Field bf : current.getClass().getDeclaredFields()) {
                    if (bf.getType() == boolean.class) {
                        bf.setAccessible(true);
                        bf.setBoolean(current, value);
                        return;
                    }
                }
            }
        } catch (Exception ignored) {}
    }

    public static void setFallDistance(float value) {
        try {
            Object player = getPlayer();
            if (player == null) return;
            Field f = getField("Entity.fallDistance");
            if (f == null) return;
            f.setAccessible(true);
            f.setFloat(player, Math.max(0f, value));
        } catch (Exception ignored) {}
    }

    public static float getFallDistance() {
        try {
            Object player = getPlayer();
            if (player == null) return 0f;
            Field f = getField("Entity.fallDistance");
            if (f == null) return 0f;
            f.setAccessible(true);
            return f.getFloat(player);
        } catch (Exception ignored) {}
        return 0f;
    }

    public static boolean isOnGround() {
        try {
            Object player = getPlayer();
            if (player == null) return false;
            Field f = getField("Entity.onGround");
            if (f == null) return false;
            f.setAccessible(true);
            Object val = f.get(player);
            if (val instanceof Boolean) return (Boolean) val;
            // BooleanContainer
            if (val != null) {
                Method getValue = getMethod("BooleanContainer.getValue");
                if (getValue != null) return (boolean) getValue.invoke(val);
                for (Field bf : val.getClass().getDeclaredFields()) {
                    if (bf.getType() == boolean.class) { bf.setAccessible(true); return bf.getBoolean(val); }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

}

