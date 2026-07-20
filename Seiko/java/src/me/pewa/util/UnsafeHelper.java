package me.pewa.util;

import sun.misc.Unsafe;
import java.lang.reflect.Field;

public class UnsafeHelper {
    private static Unsafe unsafe;

    static {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            unsafe = (Unsafe) field.get(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get Unsafe instance", e);
        }
    }

    public static Unsafe getUnsafe() {
        return unsafe;
    }

    public static void putObject(Object obj, long offset, Object value) {
        unsafe.putObject(obj, offset, value);
    }

    public static Object getObject(Object obj, long offset) {
        return unsafe.getObject(obj, offset);
    }
}
