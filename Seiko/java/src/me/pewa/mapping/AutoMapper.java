package me.pewa.mapping;

import me.pewa.loader.ClassByteResolver;
import me.pewa.util.Logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * AutoMapper - automatic class/field/method registry and cached object mapper.
 */
public class AutoMapper {

    private static final Map<String, Class<?>> classMappings = new ConcurrentHashMap<>();
    private static final Map<String, Field> fieldMappings = new ConcurrentHashMap<>();
    private static final Map<String, Method> methodMappings = new ConcurrentHashMap<>();
    private static final ReflectionMappingCache objectMappingCache = new ReflectionMappingCache();

    private static volatile MappingConfiguration configuration = MappingConfiguration.defaults();

    public static List<Class<?>> cachedClasses = new CopyOnWriteArrayList<>();

    private AutoMapper() {
    }

    public static List<Class<?>> getClasses() {
        return cachedClasses;
    }

    // ========================================
    // CORE MAPPING REGISTRY FUNCTIONS
    // ========================================

    public static void put(String name, Class<?> clazz) {
        if (name == null || clazz == null) {
            Logger.warn("Class mapping skipped: " + name + " -> null");
            return;
        }

        String rejectionReason = getClassMappingRejectionReason(name, clazz);
        if (rejectionReason != null) {
            Logger.warn("Class mapping rejected: " + name + " -> " + clazz.getName()
                    + " (" + rejectionReason + ")");
            return;
        }

        Class<?> previous = classMappings.get(name);
        if (previous == clazz) {
            return;
        }

        classMappings.put(name, clazz);
        saveClass(clazz, name);

        if (previous == null) {
            Logger.info("Class added: " + name + " -> " + clazz.getName());
        } else {
            Logger.info("Class updated: " + name + " -> " + previous.getName()
                    + " => " + clazz.getName());
        }
    }

    public static void putField(String fullName, Field field) {
        if (fullName == null || field == null) {
            Logger.warn("Field mapping skipped: " + fullName + " -> null");
            return;
        }
        if (fieldMappings.containsKey(fullName)) {
            return;
        }

        makeAccessible(field);
        fieldMappings.put(fullName, field);

        Logger.info("Field added: " + fullName + " -> " + field.getName());
    }

    public static void putMethod(String fullName, Method method) {
        if (fullName == null || method == null) {
            Logger.warn("Method mapping skipped: " + fullName + " -> null");
            return;
        }
        makeAccessible(method);
        methodMappings.put(fullName, method);

        Logger.info("Method added: " + fullName + " -> " + method.getName());
    }

    public static Class<?> get(String name) {
        return classMappings.get(name);
    }

    public static Field getField(String fullName) {
        return fieldMappings.get(fullName);
    }

    public static Method getMethod(String fullName) {
        return methodMappings.get(fullName);
    }

    public static boolean contains(String name) {
        return classMappings.containsKey(name);
    }

    public static boolean containsField(String fullName) {
        return fieldMappings.containsKey(fullName);
    }

    public static boolean containsMethod(String fullName) {
        return methodMappings.containsKey(fullName);
    }

    public static Map<String, Class<?>> getAll() {
        return new LinkedHashMap<>(classMappings);
    }

    public static Map<String, Field> getAllFields() {
        return new LinkedHashMap<>(fieldMappings);
    }

    public static Map<String, Method> getAllMethods() {
        return new LinkedHashMap<>(methodMappings);
    }

    // ========================================
    // OBJECT MAPPING API
    // ========================================

    public static MappingConfiguration getConfiguration() {
        return configuration;
    }

    public static void setConfiguration(MappingConfiguration newConfiguration) {
        if (newConfiguration == null) {
            throw new IllegalArgumentException("configuration cannot be null");
        }
        configuration = newConfiguration;
        objectMappingCache.clear();
    }

    public static void resetConfiguration() {
        setConfiguration(MappingConfiguration.defaults());
    }

    public static void registerConverter(TypeConverter<?, ?> converter) {
        configuration.getTypeConverterRegistry().register(converter);
    }

    public static <T> T map(Object source, Class<T> targetType) {
        return map(source, targetType, configuration);
    }

    @SuppressWarnings("unchecked")
    public static <T> T map(Object source, Class<T> targetType, MappingConfiguration config) {
        if (targetType == null) {
            throw new IllegalArgumentException("targetType cannot be null");
        }
        Object mapped = mapValue(source, targetType, normalize(config), new MappingContext());
        return (T) mapped;
    }

    public static <T> T map(Object source, TypeReference<T> targetType) {
        return map(source, targetType, configuration);
    }

    @SuppressWarnings("unchecked")
    public static <T> T map(Object source, TypeReference<T> targetType, MappingConfiguration config) {
        if (targetType == null) {
            throw new IllegalArgumentException("targetType cannot be null");
        }
        return (T) mapValue(source, targetType.getType(), normalize(config), new MappingContext());
    }

    public static Object map(Object source, Type targetType) {
        return map(source, targetType, configuration);
    }

    public static Object map(Object source, Type targetType, MappingConfiguration config) {
        if (targetType == null) {
            throw new IllegalArgumentException("targetType cannot be null");
        }
        return mapValue(source, targetType, normalize(config), new MappingContext());
    }

    public static <T> List<T> mapList(Collection<?> source, Class<T> elementType) {
        if (source == null) {
            return null;
        }
        List<T> result = new ArrayList<>(source.size());
        MappingConfiguration config = configuration;
        MappingContext context = new MappingContext();
        for (Object item : source) {
            result.add(elementType.cast(mapValue(item, elementType, config, context.next())));
        }
        return result;
    }

    public static Object mapArray(Object source, Class<?> componentType) {
        if (componentType == null) {
            throw new IllegalArgumentException("componentType cannot be null");
        }
        return mapArray(source, componentType, componentType, configuration, new MappingContext());
    }

    public static void clearObjectMappingCache() {
        objectMappingCache.clear();
    }

    public static String getObjectMappingCacheStats() {
        return objectMappingCache.stats();
    }

    private static Object mapValue(Object source, Type targetType, MappingConfiguration config, MappingContext context) {
        if (source == null) {
            return null;
        }
        if (context.depth > config.getMaxDepth()) {
            return mappingFailure("Maximum mapping depth exceeded: " + config.getMaxDepth(), config, null);
        }

        if (targetType instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) targetType;
            Class<?> rawType = resolveClass(parameterizedType.getRawType());
            if (rawType != null && Collection.class.isAssignableFrom(rawType)) {
                Type elementType = parameterizedType.getActualTypeArguments()[0];
                return mapCollection(source, rawType, elementType, config, context.next());
            }
            targetType = rawType == null ? Object.class : rawType;
        }

        if (targetType instanceof GenericArrayType) {
            Type componentType = ((GenericArrayType) targetType).getGenericComponentType();
            Class<?> componentClass = resolveClass(componentType);
            return mapArray(source, componentClass == null ? Object.class : componentClass, componentType, config, context.next());
        }

        if (targetType instanceof TypeVariable || targetType instanceof WildcardType) {
            targetType = Object.class;
        }

        Class<?> targetClass = resolveClass(targetType);
        if (targetClass == null || targetClass == Object.class) {
            return source;
        }

        if (targetClass.isPrimitive()) {
            targetClass = TypeConverterRegistry.wrap(targetClass);
        }

        if (targetClass.isInstance(source)) {
            return source;
        }

        if (targetClass.isArray()) {
            return mapArray(source, targetClass.getComponentType(), targetClass.getComponentType(), config, context.next());
        }

        if (Collection.class.isAssignableFrom(targetClass)) {
            return mapCollection(source, targetClass, Object.class, config, context.next());
        }

        if (isSimpleType(targetClass) || isSimpleType(source.getClass())) {
            return convertSimpleValue(source, targetClass, config);
        }

        if (!config.isNestedMappingEnabled()) {
            return mappingFailure("Nested mapping is disabled for " + source.getClass().getName()
                    + " -> " + targetClass.getName(), config, null);
        }

        Object visited = context.getVisited(source);
        if (visited != null && targetClass.isInstance(visited)) {
            return visited;
        }

        return mapObject(source, targetClass, config, context.next());
    }

    private static Object mapObject(Object source, Class<?> targetClass, MappingConfiguration config, MappingContext context) {
        Object target = instantiate(targetClass, config);
        if (target == null) {
            return null;
        }
        context.putVisited(source, target);

        MappingPlan plan = objectMappingCache.getPlan(source.getClass(), targetClass);
        for (PropertyWriter writer : plan.getWriters()) {
            PropertyReader reader = plan.getReader(writer.getSourceName());
            if (reader == null) {
                handleMissingProperty(writer, config);
                continue;
            }

            try {
                Object rawValue = reader.read(source);
                if (rawValue == null && !config.isMapNullValues()) {
                    continue;
                }

                Object mappedValue = mapValue(rawValue, writer.getGenericType(), config, context.next());
                if (mappedValue == null && writer.getType().isPrimitive()) {
                    continue;
                }
                writer.write(target, mappedValue);
            } catch (Throwable t) {
                mappingFailure("Could not map property '" + writer.getPropertyName() + "': " + t.getMessage(), config, t);
            }
        }

        return target;
    }

    private static Object mapCollection(Object source, Class<?> collectionType, Type elementType,
                                        MappingConfiguration config, MappingContext context) {
        Collection<Object> target = instantiateCollection(collectionType, config);
        if (target == null) {
            return null;
        }

        if (source.getClass().isArray()) {
            int length = Array.getLength(source);
            for (int i = 0; i < length; i++) {
                target.add(mapValue(Array.get(source, i), elementType, config, context.next()));
            }
            return target;
        }

        if (source instanceof Iterable) {
            for (Object item : (Iterable<?>) source) {
                target.add(mapValue(item, elementType, config, context.next()));
            }
            return target;
        }

        return mappingFailure("Source is not a collection or array: " + source.getClass().getName(), config, null);
    }

    private static Object mapArray(Object source, Class<?> componentClass, Type componentType,
                                   MappingConfiguration config, MappingContext context) {
        if (source == null) {
            return null;
        }

        List<Object> sourceValues = new ArrayList<>();
        if (source.getClass().isArray()) {
            int length = Array.getLength(source);
            for (int i = 0; i < length; i++) {
                sourceValues.add(Array.get(source, i));
            }
        } else if (source instanceof Iterable) {
            for (Object item : (Iterable<?>) source) {
                sourceValues.add(item);
            }
        } else {
            return mappingFailure("Source is not a collection or array: " + source.getClass().getName(), config, null);
        }

        Object array = Array.newInstance(componentClass, sourceValues.size());
        for (int i = 0; i < sourceValues.size(); i++) {
            Object mappedValue = mapValue(sourceValues.get(i), componentType, config, context.next());
            if (mappedValue == null && componentClass.isPrimitive()) {
                continue;
            }
            Array.set(array, i, mappedValue);
        }
        return array;
    }

    private static Object convertSimpleValue(Object source, Class<?> targetClass, MappingConfiguration config) {
        try {
            return config.getTypeConverterRegistry().convert(source, targetClass);
        } catch (RuntimeException ex) {
            return mappingFailure(ex.getMessage(), config, ex);
        }
    }

    private static Object instantiate(Class<?> targetClass, MappingConfiguration config) {
        try {
            Constructor<?> constructor = targetClass.getDeclaredConstructor();
            makeAccessible(constructor);
            return constructor.newInstance();
        } catch (Throwable t) {
            return mappingFailure("Target type has no usable no-arg constructor: " + targetClass.getName(), config, t);
        }
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> instantiateCollection(Class<?> collectionType, MappingConfiguration config) {
        if (collectionType == null || collectionType == Collection.class || collectionType == List.class
                || collectionType == Iterable.class) {
            return new ArrayList<>();
        }
        if (collectionType == Set.class || collectionType == LinkedHashSet.class) {
            return new LinkedHashSet<>();
        }
        if (collectionType == Queue.class || collectionType == LinkedList.class) {
            return new LinkedList<>();
        }
        if (collectionType.isInterface() || Modifier.isAbstract(collectionType.getModifiers())) {
            if (Set.class.isAssignableFrom(collectionType)) {
                return new LinkedHashSet<>();
            }
            if (Queue.class.isAssignableFrom(collectionType)) {
                return new LinkedList<>();
            }
            return new ArrayList<>();
        }

        try {
            Constructor<?> constructor = collectionType.getDeclaredConstructor();
            makeAccessible(constructor);
            return (Collection<Object>) constructor.newInstance();
        } catch (Throwable t) {
            return (Collection<Object>) mappingFailure("Collection type has no usable no-arg constructor: "
                    + collectionType.getName(), config, t);
        }
    }

    private static void handleMissingProperty(PropertyWriter writer, MappingConfiguration config) {
        if (config.isIgnoreMissingProperties()) {
            logDebug(config, "No source property for target property: " + writer.getPropertyName());
            return;
        }
        mappingFailure("No source property for target property: " + writer.getPropertyName(), config, null);
    }

    private static Object mappingFailure(String message, MappingConfiguration config, Throwable cause) {
        if (config.isDebugLoggingEnabled()) {
            Logger.debug("[AutoMapper] " + message);
        }
        if (config.isFailOnMappingError()) {
            if (cause == null) {
                throw new MappingException(message);
            }
            throw new MappingException(message, cause);
        }
        return null;
    }

    private static MappingConfiguration normalize(MappingConfiguration config) {
        return config == null ? configuration : config;
    }

    private static void logDebug(MappingConfiguration config, String message) {
        if (config.isDebugLoggingEnabled()) {
            Logger.debug("[AutoMapper] " + message);
        }
    }

    private static boolean isSimpleType(Class<?> type) {
        Class<?> wrapped = TypeConverterRegistry.wrap(type);
        return wrapped.isPrimitive()
                || wrapped.isEnum()
                || wrapped == String.class
                || Number.class.isAssignableFrom(wrapped)
                || wrapped == Boolean.class
                || wrapped == Character.class
                || Date.class.isAssignableFrom(wrapped)
                || UUID.class == wrapped;
    }

    private static Class<?> resolveClass(Type type) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            return resolveClass(((ParameterizedType) type).getRawType());
        }
        if (type instanceof WildcardType) {
            Type[] upperBounds = ((WildcardType) type).getUpperBounds();
            return upperBounds.length == 0 ? Object.class : resolveClass(upperBounds[0]);
        }
        if (type instanceof TypeVariable) {
            Type[] bounds = ((TypeVariable<?>) type).getBounds();
            return bounds.length == 0 ? Object.class : resolveClass(bounds[0]);
        }
        return null;
    }

    // ========================================
    // EXPORT TO FILE
    // ========================================

    public static void exportToFile(String filePath) {
        try {
            File file = new File(filePath);
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }

            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))) {

                writer.write("{Mapping}\n\n");

                for (Map.Entry<String, Class<?>> entry : getAll().entrySet()) {
                    String key = entry.getKey();
                    Class<?> clazz = entry.getValue();
                    if (key == null || clazz == null) {
                        continue;
                    }

                    List<Map.Entry<String, Field>> fieldsList = getAllFields().entrySet().stream()
                            .filter(fe -> fe.getKey().startsWith(key + "."))
                            .collect(Collectors.toList());

                    List<Map.Entry<String, Method>> methodsList = getAllMethods().entrySet().stream()
                            .filter(me -> me.getKey().startsWith(key + "."))
                            .filter(me -> me.getValue() != null)
                            .collect(Collectors.toList());

                    writer.write(key + " {\n");
                    writer.write("  class: " + clazz.getName() + "\n\n");

                    if (!fieldsList.isEmpty()) {
                        writer.write("  fields: {\n");
                        for (Map.Entry<String, Field> fieldEntry : fieldsList) {
                            String fieldName = fieldEntry.getKey().substring(key.length() + 1);
                            Field field = fieldEntry.getValue();

                            String typeName = field.getType().getName();
                            String obfName = field.getName();

                            writer.write(String.format("    %-12s -> type: %-20s, obf: %s\n",
                                    fieldName, typeName, obfName));
                        }
                        writer.write("  }\n\n");
                    }

                    if (!methodsList.isEmpty()) {
                        writer.write("  methods: {\n");
                        for (Map.Entry<String, Method> methodEntry : methodsList) {
                            String methodName = methodEntry.getKey().substring(key.length() + 1);
                            Method method = methodEntry.getValue();

                            String returnType = method.getReturnType().getName();
                            Class<?>[] params = method.getParameterTypes();

                            StringBuilder paramList = new StringBuilder();
                            for (int i = 0; i < params.length; i++) {
                                paramList.append(params[i].getSimpleName());
                                if (i < params.length - 1) {
                                    paramList.append(", ");
                                }
                            }

                            String obfName = method.getName();

                            String displayMethodName = methodName;
                            if (paramList.length() > 40) {
                                displayMethodName = methodName + "(...)";
                            } else if (paramList.length() > 0) {
                                displayMethodName = methodName + "(" + paramList + ")";
                            } else {
                                displayMethodName = methodName + "()";
                            }

                            writer.write(String.format("    %-30s -> returns: %-15s, obf: %s\n",
                                    displayMethodName, returnType, obfName));
                        }
                        writer.write("  }\n");
                    }

                    writer.write("}\n\n");
                }

                Logger.info("Mapping successfully exported to: " + filePath);
            }
        } catch (IOException e) {
            Logger.error("Failed to export mappings: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ========================================
    // SAVE CLASS TO FILE
    // ========================================

    public static void saveClass(Class<?> clazz, String name) {
        if (clazz == null || name == null) {
            return;
        }
        try {
            File file = new File("C:\\pewa\\classes\\" + name + ".class");
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }

            byte[] classBytes = getClassBytes(clazz);
            if (classBytes != null) {
                Files.write(file.toPath(), classBytes);
            }
        } catch (Throwable e) {
            Logger.error("Failed to save class: " + e.getMessage());
        }
    }

    // ========================================
    // HELPER FUNCTIONS
    // ========================================

    public static byte[] getClassBytes(Class<?> clazz) {
        return ClassByteResolver.readClassBytes(clazz);
    }

    // ========================================
    // CLEAR MAPPINGS
    // ========================================

    public static void clear() {
        classMappings.clear();
        fieldMappings.clear();
        methodMappings.clear();
        objectMappingCache.clear();
        Logger.info("All mappings cleared");
    }

    public static int getClassCount() {
        return classMappings.size();
    }

    public static int getFieldCount() {
        return fieldMappings.size();
    }

    public static int getMethodCount() {
        return methodMappings.size();
    }

    private static void makeAccessible(java.lang.reflect.AccessibleObject accessibleObject) {
        try {
            accessibleObject.setAccessible(true);
        } catch (Throwable ignored) {
        }
    }

    private static String getClassMappingRejectionReason(String logicalName, Class<?> clazz) {
        if (clazz.isPrimitive()) {
            return "primitive class cannot represent an obfuscated runtime type";
        }
        if (clazz.isArray()) {
            return "array class cannot represent an obfuscated runtime type";
        }

        String className = clazz.getName();
        if (isGameClassName(className) || isExplicitExternalMapping(logicalName, clazz)) {
            return null;
        }

        if (isPlatformOrLibraryClassName(className)) {
            return "platform/library class does not match logical mapping name";
        }

        return null;
    }

    private static boolean isExplicitExternalMapping(String logicalName, Class<?> clazz) {
        if (logicalName == null || clazz == null) {
            return false;
        }
        return logicalName.equals(clazz.getName()) || logicalName.equals(clazz.getSimpleName());
    }

    private static boolean isGameClassName(String className) {
        return className != null
                && (className.startsWith("craftrise.")
                || className.startsWith("crsecond.")
                || className.startsWith("cr.")
                || className.startsWith("com.craftrise."));
    }

    private static boolean isPlatformOrLibraryClassName(String className) {
        return className != null
                && (className.startsWith("java.")
                || className.startsWith("javax.")
                || className.startsWith("sun.")
                || className.startsWith("jdk.")
                || className.startsWith("com.sun.")
                || className.startsWith("com.google.")
                || className.startsWith("io.netty.")
                || className.startsWith("org."));
    }

    private static final class MappingContext {
        private final IdentityHashMap<Object, Object> visited;
        private final int depth;

        private MappingContext() {
            this(new IdentityHashMap<>(), 0);
        }

        private MappingContext(IdentityHashMap<Object, Object> visited, int depth) {
            this.visited = visited;
            this.depth = depth;
        }

        private MappingContext next() {
            return new MappingContext(visited, depth + 1);
        }

        private Object getVisited(Object source) {
            return visited.get(source);
        }

        private void putVisited(Object source, Object target) {
            visited.put(source, target);
        }
    }
}
