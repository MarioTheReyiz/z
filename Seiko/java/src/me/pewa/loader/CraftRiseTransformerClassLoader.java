package me.pewa.loader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.ProtectionDomain;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import me.pewa.util.Logger;

public final class CraftRiseTransformerClassLoader extends ClassLoader {
    private static final String CRAFTRISE_LOADER = "craftrise.lIlIIlIlllIIlAIbB";
    private static volatile CraftRiseTransformerClassLoader installed;

    private final ClassLoader craftRiseLoader;

    private CraftRiseTransformerClassLoader(ClassLoader craftRiseLoader) {
        super(craftRiseLoader);
        this.craftRiseLoader = craftRiseLoader;
    }

    public static CraftRiseTransformerClassLoader install(List<Class<?>> loadedClasses) {
        ExternalClassTransformer.logConfiguration();
        ExternalClassTransformer.installAgentTransformers();

        ClassLoader target = findCraftRiseLoader(loadedClasses);
        if (target == null) {
            target = Thread.currentThread().getContextClassLoader();
            Logger.warn("CraftRise loader " + CRAFTRISE_LOADER
                    + " was not found in loaded classes; using current context loader.");
        } else {
            Logger.info("CraftRise loader found: " + target.getClass().getName());
        }

        CraftRiseTransformerClassLoader loader = new CraftRiseTransformerClassLoader(target);
        installed = loader;
        Thread.currentThread().setContextClassLoader(loader);

        rememberLoadedClasses(loadedClasses);
        int matched = ExternalClassTransformer.transformLoadedClasses(loadedClasses);
        Logger.info("CraftRise transformer classloader installed. cachedClasses="
                + ClassByteStore.size() + ", matchedTransforms=" + matched);

        return loader;
    }

    public static CraftRiseTransformerClassLoader getInstalled() {
        return installed;
    }

    public static byte[] transformForDefine(String className, byte[] bytes, int offset, int length) {
        if (bytes == null || length <= 0) {
            return bytes;
        }

        byte[] input = new byte[length];
        System.arraycopy(bytes, offset, input, 0, length);
        ExternalClassTransformer.TransformResult result =
                ExternalClassTransformer.transformIfMatched(className, input);
        return result.getBytes();
    }

    public Class<?> defineTransformedClass(String name, byte[] bytes, ProtectionDomain protectionDomain) {
        byte[] transformed = transformForDefine(name, bytes, 0, bytes.length);
        Class<?> clazz;
        synchronized (getClassLoadingLock(name)) {
            clazz = defineClass(name, transformed, 0, transformed.length, protectionDomain);
        }
        ClassByteStore.remember(clazz, transformed);
        return clazz;
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        byte[] bytes = readParentResource(name);
        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }

        byte[] transformed = transformForDefine(name, bytes, 0, bytes.length);
        Class<?> clazz = defineClass(name, transformed, 0, transformed.length);
        ClassByteStore.remember(clazz, transformed);
        return clazz;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        if (!isGameClassName(name)) {
            return super.loadClass(name, resolve);
        }

        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                try {
                    loaded = findClass(name);
                } catch (ClassNotFoundException ignored) {
                    loaded = super.loadClass(name, false);
                }
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    private static void rememberLoadedClasses(List<Class<?>> loadedClasses) {
        if (loadedClasses == null || loadedClasses.isEmpty()) {
            return;
        }

        Set<Class<?>> unique = new LinkedHashSet<>(loadedClasses);
        for (Class<?> clazz : unique) {
            if (clazz == null || clazz.isArray() || clazz.isPrimitive()) {
                continue;
            }
            if (!isGameClassName(clazz.getName()) && !isCraftRiseLoader(clazz.getClassLoader())) {
                continue;
            }
            byte[] bytes = ExternalClassTransformer.readClassBytes(clazz);
            ClassByteStore.remember(clazz, bytes);
        }
    }

    private static ClassLoader findCraftRiseLoader(List<Class<?>> loadedClasses) {
        if (loadedClasses == null) {
            return null;
        }

        for (Class<?> clazz : loadedClasses) {
            if (clazz == null) {
                continue;
            }
            ClassLoader loader = clazz.getClassLoader();
            if (isCraftRiseLoader(loader)) {
                return loader;
            }
        }
        return null;
    }

    private static boolean isCraftRiseLoader(ClassLoader loader) {
        return loader != null && CRAFTRISE_LOADER.equals(loader.getClass().getName());
    }

    private static boolean isGameClassName(String name) {
        return name != null && (name.startsWith("craftrise.")
                || name.startsWith("crsecond.")
                || name.startsWith("cr."));
    }

    private byte[] readParentResource(String className) {
        String resource = className.replace('.', '/') + ".class";
        InputStream in = null;
        try {
            if (craftRiseLoader != null) {
                in = craftRiseLoader.getResourceAsStream(resource);
            }
            if (in == null) {
                in = ClassLoader.getSystemResourceAsStream(resource);
            }
            if (in == null) {
                return null;
            }
            return readAllBytes(in);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
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
}
