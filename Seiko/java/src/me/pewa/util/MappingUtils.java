package me.pewa.util;

import me.pewa.mapping.AutoMapper;
import me.pewa.mapping.MappingConfiguration;
import me.pewa.mapping.TypeConverter;
import me.pewa.mapping.TypeReference;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;

/**
 * MappingUtils - Wrapper for AutoMapper
 * Provides easy access to mapped classes, fields, and methods
 */
public class MappingUtils {
    
    /**
     * Register an obfuscated class with a friendly name
     */
    public static void registerObfuscatedClass(String name, Class<?> clazz) {
        AutoMapper.put(name, clazz);
    }
    
    /**
     * Register a class by its full class name
     */
    public static void registerClass(String name, String className) {
        try {
            Class<?> clazz = Class.forName(className);
            AutoMapper.put(name, clazz);
        } catch (ClassNotFoundException e) {
            Logger.error("Failed to load class: " + className);
        }
    }
    
    /**
     * Get a mapped class by name
     */
    public static Class<?> get(String name) {
        return AutoMapper.get(name);
    }
    
    /**
     * Get a mapped field by full name (ClassName.fieldName)
     */
    public static Field getField(String fullName) {
        return AutoMapper.getField(fullName);
    }
    
    /**
     * Get a mapped method by full name (ClassName.methodName)
     */
    public static Method getMethod(String fullName) {
        return AutoMapper.getMethod(fullName);
    }
    
    /**
     * Check if a class is loaded
     */
    public static boolean isClassLoaded(String name) {
        return AutoMapper.contains(name);
    }
    
    /**
     * Check if a field is mapped
     */
    public static boolean isFieldMapped(String fullName) {
        return AutoMapper.containsField(fullName);
    }
    
    /**
     * Check if a method is mapped
     */
    public static boolean isMethodMapped(String fullName) {
        return AutoMapper.containsMethod(fullName);
    }
    
    /**
     * Export all mappings to a file
     */
    public static void exportMappings(String filePath) {
        AutoMapper.exportToFile(filePath);
    }
    
    /**
     * Get mapping statistics
     */
    public static String getStats() {
        return String.format("Classes: %d, Fields: %d, Methods: %d",
            AutoMapper.getClassCount(),
            AutoMapper.getFieldCount(),
            AutoMapper.getMethodCount());
    }

    /**
     * Map one object to another type using AutoMapper's cached object mapper.
     */
    public static <T> T map(Object source, Class<T> targetType) {
        return AutoMapper.map(source, targetType);
    }

    /**
     * Map one object to a generic target type.
     */
    public static <T> T map(Object source, TypeReference<T> targetType) {
        return AutoMapper.map(source, targetType);
    }

    /**
     * Map a collection to a typed list.
     */
    public static <T> List<T> mapList(Collection<?> source, Class<T> elementType) {
        return AutoMapper.mapList(source, elementType);
    }

    /**
     * Map an array or collection to an array with the requested component type.
     */
    public static Object mapArray(Object source, Class<?> componentType) {
        return AutoMapper.mapArray(source, componentType);
    }

    /**
     * Replace object mapping behavior while preserving the class/field/method registry.
     */
    public static void configure(MappingConfiguration configuration) {
        AutoMapper.setConfiguration(configuration);
    }

    /**
     * Register a reusable type converter.
     */
    public static void registerConverter(TypeConverter<?, ?> converter) {
        AutoMapper.registerConverter(converter);
    }

    public static String getObjectMappingCacheStats() {
        return AutoMapper.getObjectMappingCacheStats();
    }
}

