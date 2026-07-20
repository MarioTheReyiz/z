package me.pewa.packethook;

import io.netty.channel.Channel;
import me.pewa.Pewa;
import me.pewa.event.EventBus;
import me.pewa.event.PacketReceiveEvent;
import me.pewa.event.PacketSendEvent;
import me.pewa.mapping.MinecraftMapper;
import me.pewa.util.Logger;
import me.pewa.util.UnsafeHelper;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PacketHook {
    private static final PacketHook INSTANCE = new PacketHook();

    private static final long PACKET_LISTENER_OFFSET = 0x40;
    private static final long CHANNEL_OFFSET = 0x44;
    private static final int MAX_FORCE_RETRIES = 20;

    private volatile boolean initialized;
    private volatile boolean sendHooked;
    private volatile boolean receiveHooked;
    private volatile boolean forceMode;
    private volatile Object hookedNetworkManager;
    private volatile int forceRetryCount;
    private ScheduledExecutorService injectExecutor;
    private ScheduledExecutorService forceExecutor;

    public static PacketHook get() {
        return INSTANCE;
    }

    public synchronized void init() {
        if (initialized) {
            return;
        }

        initialized = true;
        injectExecutor = newExecutor("Pewa-PacketHook");
        injectExecutor.scheduleAtFixedRate(this::injectIfNeeded, 0, 500, TimeUnit.MILLISECONDS);
        Logger.info("PacketHook: inject loop started");
    }

    public synchronized void shutdown() {
        initialized = false;
        if (injectExecutor != null) {
            injectExecutor.shutdownNow();
            injectExecutor = null;
        }
        if (forceExecutor != null) {
            forceExecutor.shutdownNow();
            forceExecutor = null;
        }
        resetHookState();
    }

    public synchronized void forceInject() {
        Logger.info("PacketHook: force reinject requested");
        resetHookState();
        forceMode = true;
        forceRetryCount = 0;

        if (forceExecutor != null) {
            forceExecutor.shutdownNow();
        }

        forceExecutor = newExecutor("Pewa-PacketHook-Reinject");

        forceExecutor.scheduleAtFixedRate(() -> {
            if (!forceMode || (sendHooked && receiveHooked)) {
                stopForceMode();
                return;
            }

            if (++forceRetryCount > MAX_FORCE_RETRIES) {
                Logger.warn("PacketHook: force reinject timed out");
                stopForceMode();
                return;
            }

            inject();
        }, 200, 200, TimeUnit.MILLISECONDS);
    }

    private static ScheduledExecutorService newExecutor(String name) {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        });
    }

    private synchronized void stopForceMode() {
        forceMode = false;
        if (forceExecutor != null) {
            forceExecutor.shutdownNow();
            forceExecutor = null;
        }
    }

    private void resetHookState() {
        sendHooked = false;
        receiveHooked = false;
        forceMode = false;
        hookedNetworkManager = null;
    }

    private void injectIfNeeded() {
        if (!initialized) {
            return;
        }

        if (sendHooked && receiveHooked) {
            Object currentNetworkManager = resolveNetworkManagerQuietly();
            if (currentNetworkManager != null && hookedNetworkManager != null && currentNetworkManager != hookedNetworkManager) {
                Logger.info("PacketHook: NetworkManager changed, reinjecting");
                resetHookState();
            }

            if (sendHooked && receiveHooked) {
                return;
            }
        }

        inject();
    }

    public synchronized void inject() {
        try {
            Object networkManager = resolveNetworkManager();
            if (networkManager == null) {
                return;
            }

            hookPacketListener(networkManager);
            hookChannel(networkManager);

            if (sendHooked && receiveHooked) {
                hookedNetworkManager = networkManager;
            }
        } catch (Throwable t) {
            Logger.debug("PacketHook: inject failed - " + String.valueOf(t.getMessage()));
        }
    }

    private Object resolveNetworkManagerQuietly() {
        try {
            return resolveNetworkManager();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object resolveNetworkManager() throws Exception {
        Object player = MinecraftMapper.getThePlayer();
        if (player == null) {
            return null;
        }

        Field sendQueue = MinecraftMapper.getField("EntityPlayerSP.sendQueue");
        if (sendQueue == null) {
            sendQueue = MinecraftMapper.getFieldByType(player.getClass(), MinecraftMapper.get("NetHandlerPlayClient"));
        }
        if (sendQueue == null) {
            return null;
        }

        sendQueue.setAccessible(true);
        Object netHandler = sendQueue.get(player);
        if (netHandler == null) {
            return null;
        }

        Field networkManager = MinecraftMapper.getField("NetHandlerPlayClient.networkManager");
        if (networkManager == null) {
            networkManager = MinecraftMapper.getFieldByType(netHandler.getClass(), MinecraftMapper.get("NetworkManager"));
        }
        if (networkManager == null) {
            return null;
        }

        networkManager.setAccessible(true);
        return networkManager.get(netHandler);
    }

    private void hookChannel(Object networkManager) throws Exception {
        Field channel = MinecraftMapper.getField("NetworkManager.channel");
        if (channel == null) {
            channel = MinecraftMapper.getFieldByType(networkManager.getClass(), Channel.class);
        }

        if (channel != null) {
            checkAndHookField(networkManager, channel, "Channel (SEND)", true);
            return;
        }

        checkAndHookOffset(networkManager, CHANNEL_OFFSET, "Channel (SEND)", true);
    }

    private void hookPacketListener(Object networkManager) throws Exception {
        Field listener = findPacketListenerField(networkManager);
        if (listener != null) {
            checkAndHookField(networkManager, listener, "PacketListener (RECEIVE)", false);
            return;
        }

        checkAndHookOffset(networkManager, PACKET_LISTENER_OFFSET, "PacketListener (RECEIVE)", false);
    }

    private Field findPacketListenerField(Object networkManager) {
        Class<?> currentClass = networkManager.getClass();
        while (currentClass != null) {
            Field[] fields = currentClass.getDeclaredFields();
            for (Field field : fields) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || field.getType() == Channel.class || Channel.class.isAssignableFrom(field.getType())) {
                    continue;
                }

                try {
                    field.setAccessible(true);
                    Object value = field.get(networkManager);
                    if (isPacketListener(value)) {
                        return field;
                    }
                } catch (Throwable ignored) {
                }
            }
            currentClass = currentClass.getSuperclass();
        }
        return null;
    }

    private boolean isPacketListener(Object value) {
        if (value == null) {
            return false;
        }

        Class<?> playClient = MinecraftMapper.get("INetHandlerPlayClient");
        Class<?> playServer = MinecraftMapper.get("INetHandlerPlayServer");
        if ((playClient != null && playClient.isInstance(value)) || (playServer != null && playServer.isInstance(value))) {
            return true;
        }

        for (Class<?> iface : collectInterfaces(value.getClass())) {
            String name = iface.getName();
            if (name.contains("INetHandler") || name.endsWith(".PacketListener")) {
                return true;
            }
        }

        return value.getClass().getName().contains("NetHandler");
    }

    private void checkAndHookField(Object target, Field field, String debugName, boolean channelHook) throws Exception {
        field.setAccessible(true);
        Object current = field.get(target);
        if (current == null || markIfAlreadyHooked(current, channelHook)) {
            return;
        }

        Object proxy = createProxy(current, channelHook, debugName);
        if (proxy == null) {
            return;
        }

        try {
            field.set(target, proxy);
        } catch (IllegalArgumentException incompatibleType) {
            UnsafeHelper.putObject(target, UnsafeHelper.getUnsafe().objectFieldOffset(field), proxy);
        }

        markHooked(channelHook);
        Logger.info("PacketHook: hooked " + debugName + " via field " + field.getName());
    }

    private void checkAndHookOffset(Object target, long offset, String debugName, boolean channelHook) throws Exception {
        Object current = UnsafeHelper.getObject(target, offset);
        if (current == null || markIfAlreadyHooked(current, channelHook)) {
            return;
        }

        Object proxy = createProxy(current, channelHook, debugName);
        if (proxy == null) {
            return;
        }

        UnsafeHelper.putObject(target, offset, proxy);
        markHooked(channelHook);
        Logger.info("PacketHook: hooked " + debugName + " at offset 0x" + Long.toHexString(offset).toUpperCase());
    }

    private boolean markIfAlreadyHooked(Object current, boolean channelHook) {
        if (!Proxy.isProxyClass(current.getClass())) {
            return false;
        }

        if (!forceMode) {
            markHooked(channelHook);
        }
        return true;
    }

    private void markHooked(boolean channelHook) {
        if (channelHook) {
            sendHooked = true;
        } else {
            receiveHooked = true;
        }
    }

    private Object createProxy(Object original, boolean channelHook, String debugName) {
        Class<?>[] interfaces = collectInterfaces(original.getClass()).toArray(new Class<?>[0]);
        if (interfaces.length == 0) {
            Logger.warn("PacketHook: " + debugName + " has no interfaces: " + original.getClass().getName());
            return null;
        }

        return Proxy.newProxyInstance(
                original.getClass().getClassLoader(),
                interfaces,
                new GenericProxy(original, channelHook));
    }

    private static Set<Class<?>> collectInterfaces(Class<?> type) {
        Set<Class<?>> interfaces = new LinkedHashSet<Class<?>>();
        collectInterfaces(type, interfaces);
        return interfaces;
    }

    private static void collectInterfaces(Class<?> type, Set<Class<?>> interfaces) {
        if (type == null) {
            return;
        }

        Class<?>[] directInterfaces = type.getInterfaces();
        for (Class<?> iface : directInterfaces) {
            interfaces.add(iface);
            collectInterfaces(iface, interfaces);
        }

        collectInterfaces(type.getSuperclass(), interfaces);
    }

    private static void postSend(Object packet) {
        // Reach module hook — fires on swing packet
        try {
            me.pewa.module.impl.ReachModule reach = (me.pewa.module.impl.ReachModule)
                    me.pewa.Pewa.getInstance().getModuleManager().getModule(me.pewa.module.impl.ReachModule.class);
            if (reach != null && reach.isEnabled()) {
                reach.onPacketSend(packet);
            }
        } catch (Throwable ignored) {}

        PacketSendEvent event = new PacketSendEvent(packet);
        postEvent(event);
        if (event.isCancelled()) {
            throw new PacketCancelledException();
        }
    }

    private static void postReceive(Object packet) {
        PacketReceiveEvent event = new PacketReceiveEvent(packet);
        postEvent(event);
        if (event.isCancelled()) {
            throw new PacketCancelledException();
        }
    }

    private static void postEvent(me.pewa.event.Event event) {
        try {
            EventBus eventBus = Pewa.getInstance().getEventBus();
            if (eventBus != null) {
                eventBus.post(event);
            }
        } catch (Throwable t) {
            Logger.debug("PacketHook: event dispatch failed - " + String.valueOf(t.getMessage()));
        }
    }

    private static boolean isUseEntityPacket(Object packet) {
        Class<?> c02Class = MinecraftMapper.get("C02PacketUseEntity");
        return packet != null && c02Class != null && c02Class.isInstance(packet);
    }

    private static boolean isPacket(Object value) {
        if (value == null) {
            return false;
        }

        Class<?> packetClass = MinecraftMapper.get("Packet");
        return packetClass == null || packetClass.isInstance(value);
    }

    private static void dumpUseEntityPacket(Object packet) {
        if (!isUseEntityPacket(packet)) {
            return;
        }

        try {
            List<String> values = new ArrayList<String>();
            Field[] fields = packet.getClass().getDeclaredFields();
            for (Field field : fields) {
                field.setAccessible(true);
                values.add(field.getName() + "=" + String.valueOf(field.get(packet)));
            }
            Logger.debug("PacketHook C02: " + values);
        } catch (Throwable ignored) {
        }
    }

    private static Object defaultReturnValue(Class<?> type) {
        if (!type.isPrimitive() || type == Void.TYPE) {
            return null;
        }
        if (type == Boolean.TYPE) {
            return Boolean.FALSE;
        }
        if (type == Character.TYPE) {
            return Character.valueOf('\0');
        }
        if (type == Byte.TYPE) {
            return Byte.valueOf((byte) 0);
        }
        if (type == Short.TYPE) {
            return Short.valueOf((short) 0);
        }
        if (type == Integer.TYPE) {
            return Integer.valueOf(0);
        }
        if (type == Long.TYPE) {
            return Long.valueOf(0L);
        }
        if (type == Float.TYPE) {
            return Float.valueOf(0.0F);
        }
        if (type == Double.TYPE) {
            return Double.valueOf(0.0D);
        }
        return null;
    }

    private static class GenericProxy implements InvocationHandler {
        private final Object original;
        private final boolean channelHook;

        private GenericProxy(Object original, boolean channelHook) {
            this.original = original;
            this.channelHook = channelHook;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            try {
                if (args != null && args.length > 0) {
                    Object packet = args[0];
                    String methodName = method.getName();

                    if (channelHook && methodName.startsWith("write") && isPacket(packet)) {
                        dumpUseEntityPacket(packet);
                        postSend(packet);
                    } else if (!channelHook && args.length == 1 && isPacket(packet)) {
                        postReceive(packet);
                    }
                }
            } catch (PacketCancelledException cancelled) {
                return defaultReturnValue(method.getReturnType());
            }

            try {
                return method.invoke(original, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private static class PacketCancelledException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
