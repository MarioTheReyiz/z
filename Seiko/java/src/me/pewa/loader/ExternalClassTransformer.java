package me.pewa.loader;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import me.pewa.util.Logger;

public final class ExternalClassTransformer {
    private static final File EXTRA_DIR = new File("C:\\pewa\\extra");
    private static final File TEMP_DIR = new File("C:\\pewa\\transform-temp");
    private static final File OUT_DIR = new File("C:\\pewa\\transformed");
    private static final int TIMEOUT_SECONDS = 20;
    private static final List<ClassLoader> AGENT_LOADERS = new ArrayList<>();
    private static boolean runtimeDumpAgentInstalled;

    private static final List<Rule> RULES = Collections.unmodifiableList(Arrays.asList(
            rule("EffectRenderer", "effecttransformer.jar", new Matcher() {
                @Override
                public boolean matches(String className, byte[] bytes, String text) {
                    return containsAll(text, "SUSPENDED", "()Ljava/util/ArrayList", "WATER_DROP",
                            "SNOW_SHOVEL", "SUSPENDED_DEPTH", "ForgeBlock_addHitEffects");
                }
            }),
            rule("EntityPlayerSP/NoSlow", "noslow.jar", new Matcher() {
                @Override
                public boolean matches(String className, byte[] bytes, String text) {
                    return containsAll(text, "START_SPRINTING", "PERFORM_RESPAWN", "START_SNEAKING",
                            "STOP_SNEAKING", "STOP_SPRINTING", "PERFORM_RESPAWN_DIFFERENT_LOCATION",
                            "OPEN_INVENTORY", "DROP_ALL_ITEMS", "DROP_ITEM", "RIDING_JUMP",
                            "CRIT_MAGIC", "SPRINT", "DOWN", "ENGLISH", "ISO-8859-1", "DES",
                            "MILLISECONDS", "ORIGIN", "CRIT", "Code", "TYPE", "<init>", "<clinit>");
                }
            }),
            rule("ModelBiped", "modelbipedtransform.jar", new Matcher() {
                @Override
                public boolean matches(String className, byte[] bytes, String text) {
                    return containsAll(text, "rotationPointX", "rotationPointY", "rotationPointZ",
                            "rotateAngleX", "rotateAngleY", "rotateAngleZ", "bipedHead",
                            "bipedHeadwear", "bipedBody", "bipedRightArm", "bipedLeftArm",
                            "bipedRightLeg", "bipedLeftLeg", "leftArmPose", "rightArmPose",
                            "LEFT", "EMPTY", "RIGHT", "BOW_AND_ARROW", "<init>", "showModel",
                            "isSneak", "mirror", "<clinit>");
                }
            }),
            rule("ItemRenderer", "itemrenderer.jar", new Matcher() {
                @Override
                public boolean matches(String className, byte[] bytes, String text) {
                    return text.contains("FIRST_PERSON") && text.contains("TRANSLUCENT");
                }
            }),
            rule("RendererLivingEntity", "renderq.jar", new Matcher() {
                @Override
                public boolean matches(String className, byte[] bytes, String text) {
                    return containsAll(text, "(FFFF)V", "(II)Ljava/lang/String;",
                            "RenderLivingEvent_Specials_Pre_Constructor",
                            "RenderLivingEvent_Specials_Post_Constructor");
                }
            }),
            rule("GuiIngame", "transformer.jar", new Matcher() {
                @Override
                public boolean matches(String className, byte[] bytes, String text) {
                    return containsAll(text, "(F)F", "getId", "ITALIC", "TYPE");
                }
            }),
            rule("CpsLimit", "cpslimit.jar", new Matcher() {
                @Override
                public boolean matches(String className, byte[] bytes, String text) {
                    return containsAll(text, "LEFT", "RIGHT", "CPS", "min", "max", "clear");
                }
            }),
            rule("FontRenderer", "fontrenderertransformer.jar", new Matcher() {
                @Override
                public boolean matches(String className, byte[] bytes, String text) {
                    return containsAll(text, "(Ljava/lang/String;)F",
                            "(Ljava/lang/String;)Ljava/lang/String;",
                            "(Ljava/lang/String;FFI)I", "(FFFF)V",
                            "(Ljava/lang/String;IIII)V", "(Ljava/lang/String;)V");
                }
            }),
            rule("PacketBuffer", "packetbuffer.jar", new Matcher() {
                @Override
                public boolean matches(String className, byte[] bytes, String text) {
                    return isPacketBuffer(bytes, text);
                }
            }),
            skip("Data", "data.jar", "no known class matcher; jar validity is still checked"),
            skip("RuntimeDumpAgent", "niggerso.jar", "javaagent-style jar, not an input/output transformer")));

    private ExternalClassTransformer() {
    }

    public static void logConfiguration() {
        Logger.info("External transformer dir: " + EXTRA_DIR.getAbsolutePath());
        for (Rule rule : RULES) {
            File jar = rule.jarFile();
            if (!jar.exists()) {
                Logger.warn("Transformer missing: " + rule.jarName + " (" + rule.name + ")");
                continue;
            }
            if (!isReadableJar(jar)) {
                Logger.warn("Transformer is not a readable jar: " + rule.jarName + " (" + rule.name + ")");
                continue;
            }
            if (rule.skipReason != null) {
                Logger.warn("Transformer registered but skipped: " + rule.jarName + " - " + rule.skipReason);
            } else {
                Logger.info("Transformer ready: " + rule.jarName + " -> " + rule.name);
            }
        }
    }

    public static synchronized void installAgentTransformers() {
        if (runtimeDumpAgentInstalled) {
            return;
        }

        File agentJar = new File(EXTRA_DIR, "niggerso.jar");
        if (!agentJar.exists()) {
            return;
        }
        if (!isReadableJar(agentJar)) {
            Logger.warn("Runtime dump agent jar is unreadable: " + agentJar.getAbsolutePath());
            return;
        }

        Instrumentation inst = PewaInstrumentationAgent.getInstrumentation();
        if (inst == null) {
            Logger.warn("Runtime dump agent exists but Instrumentation is not attached; "
                    + "start client.jar as a javaagent if you want that agent registered.");
            return;
        }

        try {
            URLClassLoader loader = new URLClassLoader(
                    new URL[] { agentJar.toURI().toURL() },
                    ExternalClassTransformer.class.getClassLoader());
            Class<?> agentClass = Class.forName("Agent", true, loader);
            Method premain = agentClass.getDeclaredMethod("premain", String.class, Instrumentation.class);
            premain.setAccessible(true);
            premain.invoke(null, "", inst);
            AGENT_LOADERS.add(loader);
            runtimeDumpAgentInstalled = true;
            Logger.info("Runtime dump agent registered from: " + agentJar.getAbsolutePath());
        } catch (Throwable t) {
            Logger.error("Could not register runtime dump agent: " + t.getMessage());
            t.printStackTrace();
        }
    }

    public static TransformResult transformIfMatched(String className, byte[] input) {
        if (input == null || input.length == 0) {
            return TransformResult.unchanged(input);
        }

        byte[] current = copy(input);
        String text = toText(current);
        List<String> applied = new ArrayList<>();

        for (Rule rule : RULES) {
            if (rule.skipReason != null) {
                continue;
            }

            boolean matched;
            try {
                matched = rule.matcher.matches(className, current, text);
            } catch (Throwable t) {
                Logger.warn("Transformer matcher failed for " + rule.name + ": " + t.getMessage());
                matched = false;
            }

            if (!matched) {
                continue;
            }

            File jar = rule.jarFile();
            if (!jar.exists()) {
                Logger.warn("Matched " + rule.name + " but jar is missing: " + jar.getAbsolutePath());
                continue;
            }
            if (!isReadableJar(jar)) {
                Logger.warn("Matched " + rule.name + " but jar is unreadable: " + jar.getAbsolutePath());
                continue;
            }

            try {
                Logger.info("Class matched " + rule.name + ": " + safeName(className));
                current = runTransformer(rule, current);
                text = toText(current);
                applied.add(rule.name);
            } catch (Throwable t) {
                Logger.error("Transformer failed (" + rule.jarName + " / " + safeName(className) + "): "
                        + t.getMessage());
                t.printStackTrace();
            }
        }

        if (applied.isEmpty()) {
            TransformerRegistry.TransformPipelineResult extensionResult =
                    TransformerRegistry.apply(className, input, current);
            if (extensionResult.isChanged()) {
                return new TransformResult(extensionResult.getBytes(), extensionResult.getAppliedTransformers());
            }
            return TransformResult.unchanged(input);
        }

        TransformerRegistry.TransformPipelineResult extensionResult =
                TransformerRegistry.apply(className, input, current);
        if (extensionResult.isChanged()) {
            current = extensionResult.getBytes();
            applied.addAll(extensionResult.getAppliedTransformers());
        }
        return new TransformResult(current, applied);
    }

    public static int transformLoadedClasses(List<Class<?>> classes) {
        if (classes == null || classes.isEmpty()) {
            return 0;
        }

        Set<Class<?>> unique = new LinkedHashSet<>(classes);
        int matched = 0;
        int redefined = 0;
        boolean canRedefine = PewaInstrumentationAgent.canRedefineClasses();

        for (Class<?> clazz : unique) {
            if (clazz == null || clazz.isArray() || clazz.isPrimitive()) {
                continue;
            }

            byte[] original = readClassBytes(clazz);
            if (original == null || original.length == 0) {
                continue;
            }

            ClassByteStore.remember(clazz, original);
            TransformResult result = transformIfMatched(clazz.getName(), original);
            if (!result.isChanged()) {
                continue;
            }

            matched++;
            ClassByteStore.remember(clazz, result.getBytes());
            writeTransformedClass(clazz.getName(), result.getBytes());

            if (canRedefine) {
                try {
                    PewaInstrumentationAgent.redefine(clazz, result.getBytes());
                    redefined++;
                    Logger.info("Redefined loaded class: " + clazz.getName()
                            + " via " + result.getAppliedTransformers());
                } catch (Throwable t) {
                    Logger.error("Could not redefine loaded class " + clazz.getName() + ": " + t.getMessage());
                }
            }
        }

        if (matched > 0 && !canRedefine) {
            Logger.warn("Matched " + matched + " loaded classes, but Instrumentation is not attached; "
                    + "transformed bytes were cached and written under C:\\pewa\\transformed.");
        }
        if (canRedefine) {
            Logger.info("Loaded class transform pass complete. matched=" + matched + ", redefined=" + redefined);
        } else {
            Logger.info("Loaded class transform pass complete. matched=" + matched + ", cachedOnly=" + matched);
        }
        return matched;
    }

    public static byte[] readClassBytes(Class<?> clazz) {
        return ClassByteResolver.readClassBytes(clazz);
    }

    public static void writeTransformedClass(String className, byte[] bytes) {
        if (className == null || bytes == null || bytes.length == 0) {
            return;
        }
        try {
            File out = new File(OUT_DIR, className.replace('.', File.separatorChar) + ".class");
            File parent = out.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Files.write(out.toPath(), bytes);
        } catch (Throwable t) {
            Logger.warn("Could not write transformed class " + className + ": " + t.getMessage());
        }
    }

    private static byte[] runTransformer(Rule rule, byte[] input) throws Exception {
        TEMP_DIR.mkdirs();

        String id = UUID.randomUUID().toString();
        File in = new File(TEMP_DIR, rule.name + "_" + id + "_in.class");
        File out = new File(TEMP_DIR, rule.name + "_" + id + "_out.class");

        try {
            Files.write(in.toPath(), input);

            ProcessBuilder builder = new ProcessBuilder(
                    javaCommand(),
                    "-jar",
                    rule.jarFile().getAbsolutePath(),
                    in.getAbsolutePath(),
                    out.getAbsolutePath());
            builder.redirectErrorStream(true);

            Process process = builder.start();
            List<String> lines = Collections.synchronizedList(new ArrayList<String>());
            Thread outputReader = new Thread(new ProcessOutputReader(process.getInputStream(), lines),
                    "Pewa-" + rule.name + "-Output");
            outputReader.setDaemon(true);
            outputReader.start();

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("timeout after " + TIMEOUT_SECONDS + "s");
            }

            outputReader.join(1000L);
            for (String line : lines) {
                Logger.info("[" + rule.jarName + "] " + line);
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                throw new IOException("process exit code: " + exitCode);
            }
            if (!out.exists() || out.length() <= 0L) {
                throw new IOException("output class was not created");
            }

            byte[] transformed = Files.readAllBytes(out.toPath());
            Logger.info("Transformation successful via " + rule.jarName + " (" + input.length
                    + " -> " + transformed.length + " bytes)");
            return transformed;
        } finally {
            deleteQuietly(in);
            deleteQuietly(out);
        }
    }

    private static String javaCommand() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            File javaExe = new File(javaHome, "bin\\java.exe");
            if (javaExe.exists()) {
                return javaExe.getAbsolutePath();
            }
            File javaBin = new File(javaHome, "bin/java");
            if (javaBin.exists()) {
                return javaBin.getAbsolutePath();
            }
        }
        return "java";
    }

    private static boolean isPacketBuffer(byte[] bytes, String text) {
        if (!text.contains("io/netty/buffer/ByteBuf")) {
            return false;
        }

        try {
            ClassReader reader = new ClassReader(bytes);
            ClassNode node = new ClassNode();
            reader.accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            if (!"io/netty/buffer/ByteBuf".equals(node.superName)) {
                return false;
            }

            boolean hasByteBufConstructor = false;
            int byteBufReturnMethods = 0;
            for (Object methodObject : node.methods) {
                MethodNode method = (MethodNode) methodObject;
                if ("<init>".equals(method.name) && method.desc.contains("Lio/netty/buffer/ByteBuf;")) {
                    hasByteBufConstructor = true;
                }
                if (method.desc != null && method.desc.endsWith("Lio/netty/buffer/ByteBuf;")) {
                    byteBufReturnMethods++;
                }
            }

            return hasByteBufConstructor && byteBufReturnMethods > 0;
        } catch (Throwable ignored) {
            return text.contains("(Lio/netty/buffer/ByteBuf;)V");
        }
    }

    private static boolean containsAll(String text, String... needles) {
        for (String needle : needles) {
            if (!text.contains(needle)) {
                return false;
            }
        }
        return true;
    }

    private static Rule rule(String name, String jarName, Matcher matcher) {
        return new Rule(name, jarName, matcher, null);
    }

    private static Rule skip(String name, String jarName, String reason) {
        return new Rule(name, jarName, null, reason);
    }

    private static String toText(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static boolean isReadableJar(File file) {
        JarFile jar = null;
        try {
            jar = new JarFile(file);
            return true;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (jar != null) {
                try {
                    jar.close();
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

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void deleteQuietly(File file) {
        if (file != null && file.exists() && !file.delete()) {
            file.deleteOnExit();
        }
    }

    private static byte[] copy(byte[] bytes) {
        byte[] out = new byte[bytes.length];
        System.arraycopy(bytes, 0, out, 0, bytes.length);
        return out;
    }

    private static String safeName(String className) {
        return className == null ? "<unknown>" : className;
    }

    private interface Matcher {
        boolean matches(String className, byte[] bytes, String text);
    }

    private static final class Rule {
        private final String name;
        private final String jarName;
        private final Matcher matcher;
        private final String skipReason;

        private Rule(String name, String jarName, Matcher matcher, String skipReason) {
            this.name = name;
            this.jarName = jarName;
            this.matcher = matcher;
            this.skipReason = skipReason;
        }

        private File jarFile() {
            return new File(EXTRA_DIR, jarName);
        }
    }

    public static final class TransformResult {
        private final byte[] bytes;
        private final List<String> appliedTransformers;

        private TransformResult(byte[] bytes, List<String> appliedTransformers) {
            this.bytes = bytes == null ? null : copy(bytes);
            this.appliedTransformers = Collections.unmodifiableList(new ArrayList<>(appliedTransformers));
        }

        private static TransformResult unchanged(byte[] bytes) {
            return new TransformResult(bytes, Collections.<String>emptyList());
        }

        public byte[] getBytes() {
            return bytes == null ? null : copy(bytes);
        }

        public boolean isChanged() {
            return !appliedTransformers.isEmpty();
        }

        public List<String> getAppliedTransformers() {
            return appliedTransformers;
        }
    }

    private static final class ProcessOutputReader implements Runnable {
        private final InputStream inputStream;
        private final List<String> lines;

        private ProcessOutputReader(InputStream inputStream, List<String> lines) {
            this.inputStream = inputStream;
            this.lines = lines;
        }

        @Override
        public void run() {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException ignored) {
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }
}
