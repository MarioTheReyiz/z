package me.pewa.loader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Enumeration;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class ClassByteResolver {
    private static final File[] KNOWN_CLASS_DIRS = new File[] {
            new File("C:\\pewa\\transformed"),
            new File("C:\\pewa\\classes"),
            new File("C:\\pulse\\classes")
    };

    private static final File[] KNOWN_JARS = new File[] {
            new File("C:\\pulse\\cr.jar"),
            new File("C:\\pulse\\output.jar"),
            new File("C:\\pewa\\cr.jar"),
            new File("C:\\pewa\\output.jar")
    };

    private static final Map<String, byte[]> diskCache = new HashMap<>();
    private static final Set<String> diskMisses = new HashSet<>();
    private static final Map<String, File> knownJarIndex = new HashMap<>();
    private static boolean knownJarIndexBuilt;

    private ClassByteResolver() {
    }

    public static byte[] readClassBytes(Class<?> clazz) {
        if (clazz == null) {
            return null;
        }

        byte[] bytes = ClassByteStore.get(clazz);
        if (isValid(bytes)) {
            return bytes;
        }

        bytes = readFromPatchedClassLoaderMethod(clazz);
        if (isValid(bytes)) {
            return remember(clazz, bytes);
        }

        bytes = readFromPatchedClassLoaderMap(clazz);
        if (isValid(bytes)) {
            return remember(clazz, bytes);
        }

        bytes = readFromClassResource(clazz);
        if (isValid(bytes)) {
            return remember(clazz, bytes);
        }

        bytes = readFromCodeSource(clazz);
        if (isValid(bytes)) {
            return remember(clazz, bytes);
        }

        bytes = readFromKnownDiskLocations(clazz);
        if (isValid(bytes)) {
            return remember(clazz, bytes);
        }

        return null;
    }

    private static byte[] readFromPatchedClassLoaderMethod(Class<?> clazz) {
        try {
            Method method = ClassLoader.class.getDeclaredMethod("getCustomClassByte", Class.class);
            method.setAccessible(true);
            Object value = method.invoke(null, clazz);
            return value instanceof byte[] ? (byte[]) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static byte[] readFromPatchedClassLoaderMap(Class<?> clazz) {
        try {
            Field field = ClassLoader.class.getDeclaredField("customClassBytes");
            field.setAccessible(true);
            Object mapObject = field.get(null);
            if (!(mapObject instanceof Map)) {
                return null;
            }

            Map<?, ?> map = (Map<?, ?>) mapObject;
            synchronized (mapObject) {
                Object exact = map.get(clazz);
                if (exact instanceof byte[] && ((byte[]) exact).length > 0) {
                    return (byte[]) exact;
                }

                String className = clazz.getName();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object key = entry.getKey();
                    Object value = entry.getValue();
                    if (key instanceof Class<?>
                            && className.equals(((Class<?>) key).getName())
                            && value instanceof byte[]
                            && ((byte[]) value).length > 0) {
                        return (byte[]) value;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static byte[] readFromClassResource(Class<?> clazz) {
        String resourceName = toResourceName(clazz);
        InputStream in = null;
        try {
            ClassLoader loader = clazz.getClassLoader();
            if (loader != null) {
                in = loader.getResourceAsStream(resourceName);
            }
            if (in == null) {
                in = ClassLoader.getSystemResourceAsStream(resourceName);
            }
            return in == null ? null : readAllBytes(in);
        } catch (Throwable ignored) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static byte[] readFromCodeSource(Class<?> clazz) {
        try {
            ProtectionDomain domain = clazz.getProtectionDomain();
            CodeSource source = domain == null ? null : domain.getCodeSource();
            URL location = source == null ? null : source.getLocation();
            if (location == null) {
                return null;
            }

            URI uri = location.toURI();
            File file = new File(uri);
            return readFromFileLocation(file, toResourceName(clazz));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static byte[] readFromKnownDiskLocations(Class<?> clazz) {
        String className = clazz.getName();
        String resourceName = toResourceName(clazz);

        synchronized (diskCache) {
            byte[] cached = diskCache.get(className);
            if (cached != null) {
                return copy(cached);
            }
            if (diskMisses.contains(className)) {
                return null;
            }
        }

        byte[] bytes = null;
        for (File dir : KNOWN_CLASS_DIRS) {
            bytes = readFromDirectory(dir, resourceName);
            if (isValid(bytes)) {
                break;
            }
        }

        if (!isValid(bytes)) {
            File jar = findKnownJar(resourceName);
            if (jar != null) {
                bytes = readFromJar(jar, resourceName);
            }
        }

        synchronized (diskCache) {
            if (isValid(bytes)) {
                diskCache.put(className, copy(bytes));
            } else {
                diskMisses.add(className);
            }
        }
        return isValid(bytes) ? copy(bytes) : null;
    }

    private static File findKnownJar(String resourceName) {
        synchronized (knownJarIndex) {
            if (!knownJarIndexBuilt) {
                buildKnownJarIndex();
                knownJarIndexBuilt = true;
            }
            return knownJarIndex.get(resourceName);
        }
    }

    private static void buildKnownJarIndex() {
        for (File jarFile : KNOWN_JARS) {
            if (jarFile == null || !jarFile.isFile()) {
                continue;
            }

            JarFile jar = null;
            try {
                jar = new JarFile(jarFile);
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (name != null && name.endsWith(".class") && !knownJarIndex.containsKey(name)) {
                        knownJarIndex.put(name, jarFile);
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (jar != null) {
                    try {
                        jar.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }

    private static byte[] readFromFileLocation(File file, String resourceName) {
        if (file == null || !file.exists()) {
            return null;
        }
        if (file.isDirectory()) {
            return readFromDirectory(file, resourceName);
        }
        return readFromJar(file, resourceName);
    }

    private static byte[] readFromDirectory(File directory, String resourceName) {
        if (directory == null || !directory.isDirectory()) {
            return null;
        }

        File classFile = new File(directory, resourceName.replace('/', File.separatorChar));
        if (!classFile.isFile()) {
            return null;
        }

        InputStream in = null;
        try {
            in = new FileInputStream(classFile);
            return readAllBytes(in);
        } catch (Throwable ignored) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static byte[] readFromJar(File jarFile, String resourceName) {
        if (jarFile == null || !jarFile.isFile()) {
            return null;
        }

        JarFile jar = null;
        InputStream in = null;
        try {
            jar = new JarFile(jarFile);
            JarEntry entry = jar.getJarEntry(resourceName);
            if (entry == null) {
                return null;
            }
            in = jar.getInputStream(entry);
            return readAllBytes(in);
        } catch (Throwable ignored) {
            return null;
        } finally {
            closeQuietly(in);
            if (jar != null) {
                try {
                    jar.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static byte[] remember(Class<?> clazz, byte[] bytes) {
        if (!isValid(bytes)) {
            return null;
        }
        ClassByteStore.remember(clazz, bytes);
        return copy(bytes);
    }

    private static String toResourceName(Class<?> clazz) {
        return clazz.getName().replace('.', '/') + ".class";
    }

    private static boolean isValid(byte[] bytes) {
        return bytes != null && bytes.length > 0;
    }

    private static byte[] readAllBytes(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[16384];
        int read;
        while ((read = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, read);
        }
        return buffer.toByteArray();
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static byte[] copy(byte[] bytes) {
        byte[] out = new byte[bytes.length];
        System.arraycopy(bytes, 0, out, 0, bytes.length);
        return out;
    }
}
