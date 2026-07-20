package me.pewa.loader;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;

import me.pewa.util.Logger;

public final class PewaInstrumentationAgent {
    private static volatile Instrumentation instrumentation;

    private PewaInstrumentationAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        setInstrumentation(inst, "premain");
    }

    public static void agentmain(String args, Instrumentation inst) {
        setInstrumentation(inst, "agentmain");
    }

    public static boolean isAvailable() {
        return instrumentation != null;
    }

    public static boolean canRedefineClasses() {
        return instrumentation != null && instrumentation.isRedefineClassesSupported();
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }

    public static void redefine(Class<?> clazz, byte[] bytecode) throws Exception {
        Instrumentation inst = instrumentation;
        if (inst == null) {
            throw new IllegalStateException("Instrumentation is not available");
        }
        if (!inst.isRedefineClassesSupported()) {
            throw new IllegalStateException("Class redefinition is not supported by this JVM");
        }
        inst.redefineClasses(new ClassDefinition(clazz, bytecode));
    }

    private static void setInstrumentation(Instrumentation inst, String source) {
        instrumentation = inst;
        Logger.info("Pewa instrumentation attached via " + source
                + " (redefine=" + inst.isRedefineClassesSupported()
                + ", retransform=" + inst.isRetransformClassesSupported() + ")");
    }
}
