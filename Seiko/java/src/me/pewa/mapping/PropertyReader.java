package me.pewa.mapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;

final class PropertyReader {
    private final String name;
    private final Class<?> type;
    private final Type genericType;
    private final Field field;
    private final Method method;

    private PropertyReader(String name, Class<?> type, Type genericType, Field field, Method method) {
        this.name = name;
        this.type = type;
        this.genericType = genericType;
        this.field = field;
        this.method = method;
    }

    static PropertyReader fromField(String name, Field field) {
        setAccessible(field);
        return new PropertyReader(name, field.getType(), field.getGenericType(), field, null);
    }

    static PropertyReader fromMethod(String name, Method method) {
        setAccessible(method);
        return new PropertyReader(name, method.getReturnType(), method.getGenericReturnType(), null, method);
    }

    String getName() {
        return name;
    }

    Class<?> getType() {
        return type;
    }

    Type getGenericType() {
        return genericType;
    }

    Object read(Object source) throws Exception {
        if (field != null) {
            return field.get(source);
        }
        return method.invoke(source);
    }

    private static void setAccessible(java.lang.reflect.AccessibleObject accessibleObject) {
        try {
            accessibleObject.setAccessible(true);
        } catch (Throwable ignored) {
        }
    }
}
