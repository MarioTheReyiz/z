package me.pewa.loader;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import me.pewa.util.Logger;

public final class TransformerRegistry {
    private static final List<BytecodeTransformer> TRANSFORMERS = new CopyOnWriteArrayList<>();

    private TransformerRegistry() {
    }

    public static void register(BytecodeTransformer transformer) {
        if (transformer == null) {
            throw new IllegalArgumentException("transformer cannot be null");
        }
        TRANSFORMERS.add(transformer);
        Logger.info("Java transformer registered: " + safeName(transformer));
    }

    public static boolean unregister(String name) {
        if (name == null) {
            return false;
        }
        boolean removed = false;
        for (BytecodeTransformer transformer : TRANSFORMERS) {
            if (name.equals(transformer.getName())) {
                removed |= TRANSFORMERS.remove(transformer);
            }
        }
        return removed;
    }

    public static void clear() {
        TRANSFORMERS.clear();
    }

    public static int size() {
        return TRANSFORMERS.size();
    }

    public static List<BytecodeTransformer> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(TRANSFORMERS));
    }

    public static TransformPipelineResult apply(String className, byte[] originalBytes, byte[] currentBytes) {
        if (currentBytes == null || currentBytes.length == 0 || TRANSFORMERS.isEmpty()) {
            return TransformPipelineResult.unchanged(currentBytes);
        }

        byte[] current = copy(currentBytes);
        List<String> applied = new ArrayList<>();
        for (BytecodeTransformer transformer : TRANSFORMERS) {
            TransformerContext context = new TransformerContext(className, originalBytes, current, applied.size());
            boolean matched;
            try {
                matched = transformer.matches(context);
            } catch (Throwable t) {
                Logger.warn("Java transformer matcher failed for " + safeName(transformer) + ": " + t.getMessage());
                continue;
            }
            if (!matched) {
                continue;
            }

            try {
                byte[] next = transformer.transform(context);
                if (next == null || next.length == 0) {
                    Logger.warn("Java transformer returned empty bytes: " + safeName(transformer));
                    continue;
                }
                if (!Arrays.equals(current, next)) {
                    current = copy(next);
                    applied.add(safeName(transformer));
                    Logger.info("Java transformer applied: " + safeName(transformer)
                            + " -> " + safeClassName(className));
                }
            } catch (Throwable t) {
                Logger.error("Java transformer failed (" + safeName(transformer) + " / "
                        + safeClassName(className) + "): " + t.getMessage());
                t.printStackTrace();
            }
        }

        if (applied.isEmpty()) {
            return TransformPipelineResult.unchanged(currentBytes);
        }
        return new TransformPipelineResult(current, applied);
    }

    private static String safeName(BytecodeTransformer transformer) {
        String name = transformer.getName();
        return name == null || name.trim().length() == 0
                ? transformer.getClass().getName()
                : name.trim();
    }

    private static String safeClassName(String className) {
        return className == null ? "<unknown>" : className;
    }

    private static byte[] copy(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        byte[] out = new byte[bytes.length];
        System.arraycopy(bytes, 0, out, 0, bytes.length);
        return out;
    }

    public static final class TransformPipelineResult {
        private final byte[] bytes;
        private final List<String> appliedTransformers;

        private TransformPipelineResult(byte[] bytes, List<String> appliedTransformers) {
            this.bytes = copy(bytes);
            this.appliedTransformers = Collections.unmodifiableList(new ArrayList<>(appliedTransformers));
        }

        private static TransformPipelineResult unchanged(byte[] bytes) {
            return new TransformPipelineResult(bytes, Collections.<String>emptyList());
        }

        public byte[] getBytes() {
            return copy(bytes);
        }

        public boolean isChanged() {
            return !appliedTransformers.isEmpty();
        }

        public List<String> getAppliedTransformers() {
            return appliedTransformers;
        }
    }
}
