package me.pewa.loader;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class ClassByteStore {
    private static final List<Class<?>> customClasses = new ArrayList<>();
    private static final Map<Class<?>, byte[]> customClassBytes = new IdentityHashMap<>();

    private ClassByteStore() {
    }

    public static void remember(Class<?> clazz, byte[] bytecode) {
        if (clazz == null) {
            return;
        }

        synchronized (customClasses) {
            if (!customClasses.contains(clazz)) {
                customClasses.add(clazz);
            }
        }

        if (bytecode != null && bytecode.length > 0) {
            synchronized (customClassBytes) {
                customClassBytes.put(clazz, copy(bytecode));
            }
        }
    }

    public static byte[] get(Class<?> clazz) {
        if (clazz == null) {
            return null;
        }

        synchronized (customClassBytes) {
            byte[] bytes = customClassBytes.get(clazz);
            return bytes == null ? null : copy(bytes);
        }
    }

    public static List<Class<?>> snapshot() {
        synchronized (customClasses) {
            return new ArrayList<>(customClasses);
        }
    }

    public static int size() {
        synchronized (customClasses) {
            return customClasses.size();
        }
    }

    private static byte[] copy(byte[] bytes) {
        byte[] out = new byte[bytes.length];
        System.arraycopy(bytes, 0, out, 0, bytes.length);
        return out;
    }
}
