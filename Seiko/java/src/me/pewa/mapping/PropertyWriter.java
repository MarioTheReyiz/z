package me.pewa.mapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;

final class PropertyWriter {
    private final String propertyName;
    private final String sourceName;
    private final Class<?> type;
    private final Type genericType;
    private final Field field;
    private final Method method;

    private PropertyWriter(String propertyName, String sourceName, Class<?> type, Type genericType,
                           Field field, Method method) {
        this.propertyName = propertyName;
        this.sourceName = sourceName;
        this.type = type;
        this.genericType = genericType;
        this.field = field;
        this.method = method;
    }

    static PropertyWriter fromField(String propertyName, String sourceName, Field field) {
        setAccessible(field);
        return new PropertyWriter(propertyName, sourceName, field.getType(), field.getGenericType(), field, null);
    }

    static PropertyWriter fromMethod(String propertyName, String sourceName, Method method) {
        setAccessible(method);
        return new PropertyWriter(propertyName, sourceName, method.getParameterTypes()[0],
                method.getGenericParameterTypes()[0], null, method);
    }

    String getPropertyName() {
        return propertyName;
    }

    String getSourceName() {
        return sourceName;
    }

    Class<?> getType() {
        return type;
    }

    Type getGenericType() {
        return genericType;
    }

    void write(Object target, Object value) throws Exception {
        if (field != null) {
            field.set(target, value);
            return;
        }
        method.invoke(target, value);
    }

    private static void setAccessible(java.lang.reflect.AccessibleObject accessibleObject) {
        try {
            accessibleObject.setAccessible(true);
        } catch (Throwable ignored) {
        }
    }
}
