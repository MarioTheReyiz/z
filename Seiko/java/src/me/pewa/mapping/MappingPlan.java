package me.pewa.mapping;

import java.beans.Introspector;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MappingPlan {
    private final Map<String, PropertyReader> readers;
    private final List<PropertyWriter> writers;

    private MappingPlan(Map<String, PropertyReader> readers, List<PropertyWriter> writers) {
        this.readers = Collections.unmodifiableMap(readers);
        this.writers = Collections.unmodifiableList(writers);
    }

    static MappingPlan build(Class<?> sourceType, Class<?> targetType) {
        return new MappingPlan(readersFor(sourceType), writersFor(targetType));
    }

    PropertyReader getReader(String name) {
        return readers.get(name);
    }

    List<PropertyWriter> getWriters() {
        return writers;
    }

    private static Map<String, PropertyReader> readersFor(Class<?> type) {
        Map<String, PropertyReader> readers = new LinkedHashMap<>();
        for (Class<?> current : hierarchy(type)) {
            for (Field field : current.getDeclaredFields()) {
                if (skipField(field)) {
                    continue;
                }
                String name = mappedName(field, field.getName());
                readers.put(name, PropertyReader.fromField(name, field));
            }
            for (Method method : current.getDeclaredMethods()) {
                if (skipReaderMethod(method)) {
                    continue;
                }
                String propertyName = getterName(method);
                String name = mappedName(method, propertyName);
                readers.put(name, PropertyReader.fromMethod(name, method));
            }
        }
        return readers;
    }

    private static List<PropertyWriter> writersFor(Class<?> type) {
        Map<String, PropertyWriter> writers = new LinkedHashMap<>();
        for (Class<?> current : hierarchy(type)) {
            for (Method method : current.getDeclaredMethods()) {
                if (skipWriterMethod(method)) {
                    continue;
                }
                String propertyName = setterName(method);
                String sourceName = mappedName(method, propertyName);
                writers.put(propertyName, PropertyWriter.fromMethod(propertyName, sourceName, method));
            }
            for (Field field : current.getDeclaredFields()) {
                if (skipField(field) || Modifier.isFinal(field.getModifiers())) {
                    continue;
                }
                String propertyName = field.getName();
                String sourceName = mappedName(field, propertyName);
                if (!writers.containsKey(propertyName)) {
                    writers.put(propertyName, PropertyWriter.fromField(propertyName, sourceName, field));
                }
            }
        }
        return new ArrayList<>(writers.values());
    }

    private static List<Class<?>> hierarchy(Class<?> type) {
        List<Class<?>> classes = new ArrayList<>();
        Class<?> current = type;
        while (current != null && current != Object.class) {
            classes.add(0, current);
            current = current.getSuperclass();
        }
        return classes;
    }

    private static boolean skipField(Field field) {
        int modifiers = field.getModifiers();
        return Modifier.isStatic(modifiers)
                || field.isSynthetic()
                || field.getAnnotation(MappingIgnore.class) != null;
    }

    private static boolean skipReaderMethod(Method method) {
        int modifiers = method.getModifiers();
        return Modifier.isStatic(modifiers)
                || method.isSynthetic()
                || method.getParameterTypes().length != 0
                || method.getReturnType() == Void.TYPE
                || "getClass".equals(method.getName())
                || method.getAnnotation(MappingIgnore.class) != null
                || getterName(method) == null;
    }

    private static boolean skipWriterMethod(Method method) {
        int modifiers = method.getModifiers();
        return Modifier.isStatic(modifiers)
                || method.isSynthetic()
                || method.getParameterTypes().length != 1
                || method.getAnnotation(MappingIgnore.class) != null
                || setterName(method) == null;
    }

    private static String getterName(Method method) {
        String name = method.getName();
        if (name.startsWith("get") && name.length() > 3) {
            return Introspector.decapitalize(name.substring(3));
        }
        if (name.startsWith("is") && name.length() > 2
                && (method.getReturnType() == boolean.class || method.getReturnType() == Boolean.class)) {
            return Introspector.decapitalize(name.substring(2));
        }
        MappingName mappingName = method.getAnnotation(MappingName.class);
        return mappingName == null ? null : name;
    }

    private static String setterName(Method method) {
        String name = method.getName();
        if (name.startsWith("set") && name.length() > 3) {
            return Introspector.decapitalize(name.substring(3));
        }
        MappingName mappingName = method.getAnnotation(MappingName.class);
        return mappingName == null ? null : name;
    }

    private static String mappedName(Field field, String fallback) {
        MappingName annotation = field.getAnnotation(MappingName.class);
        return annotation == null || annotation.value().trim().length() == 0 ? fallback : annotation.value().trim();
    }

    private static String mappedName(Method method, String fallback) {
        MappingName annotation = method.getAnnotation(MappingName.class);
        return annotation == null || annotation.value().trim().length() == 0 ? fallback : annotation.value().trim();
    }
}
