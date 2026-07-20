package me.pewa.loader;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class GuiIngameHookTransformer implements BytecodeTransformer {
    private static final String NAME = "GuiIngameHudHook";
    private static final String HUD_OWNER = "me/pewa/ui/IngameHud";
    private static final String HUD_METHOD = "render";
    private static final String HUD_DESC = "(Ljava/lang/Object;F)V";

    private GuiIngameHookTransformer() {
    }

    public static synchronized void register() {
        for (BytecodeTransformer transformer : TransformerRegistry.snapshot()) {
            if (NAME.equals(transformer.getName())) {
                return;
            }
        }
        TransformerRegistry.register(new GuiIngameHookTransformer());
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public boolean matches(TransformerContext context) {
        byte[] bytes = context.getCurrentBytes();
        if (bytes == null || bytes.length == 0) {
            return false;
        }

        try {
            ClassNode node = readClass(bytes);
            return looksLikeGuiIngame(node) && findRenderMethod(node) != null;
        } catch (Throwable ignored) {
            String text = new String(bytes, StandardCharsets.ISO_8859_1);
            return containsAll(text, "(F)F", "getId", "ITALIC", "TYPE");
        }
    }

    @Override
    public byte[] transform(TransformerContext context) {
        ClassNode node = readClass(context.getCurrentBytes());
        MethodNode renderMethod = findRenderMethod(node);
        if (renderMethod == null || hasHudCall(renderMethod)) {
            return context.getCurrentBytes();
        }

        InsnList hook = new InsnList();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(new VarInsnNode(Opcodes.FLOAD, 2));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HUD_OWNER, HUD_METHOD, HUD_DESC, false));

        AbstractInsnNode first = firstRealInstruction(renderMethod);
        if (first == null) {
            renderMethod.instructions.add(hook);
        } else {
            renderMethod.instructions.insertBefore(first, hook);
        }

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static ClassNode readClass(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        return node;
    }

    private static boolean looksLikeGuiIngame(ClassNode node) {
        boolean hasStaticMap = false;
        boolean hasProtectedFinalField = false;

        List<?> fields = node.fields;
        for (Object fieldObject : fields) {
            FieldNode field = (FieldNode) fieldObject;
            int access = field.access;
            if ((access & Opcodes.ACC_STATIC) != 0
                    && (access & Opcodes.ACC_FINAL) != 0
                    && "Ljava/util/Map;".equals(field.desc)) {
                hasStaticMap = true;
            }
            if ((access & Opcodes.ACC_PROTECTED) != 0
                    && (access & Opcodes.ACC_FINAL) != 0
                    && (access & Opcodes.ACC_STATIC) == 0
                    && field.desc != null
                    && field.desc.startsWith("L")) {
                hasProtectedFinalField = true;
            }
        }

        return hasStaticMap && hasProtectedFinalField;
    }

    private static MethodNode findRenderMethod(ClassNode node) {
        MethodNode fallback = null;
        List<?> methods = node.methods;
        for (Object methodObject : methods) {
            MethodNode method = (MethodNode) methodObject;
            if (!isOverlayRenderMethod(method)) {
                continue;
            }
            if (!hasHudCall(method)) {
                return method;
            }
            fallback = method;
        }
        return fallback;
    }

    private static boolean isOverlayRenderMethod(MethodNode method) {
        if ("<init>".equals(method.name) || "<clinit>".equals(method.name)) {
            return false;
        }
        if ((method.access & Opcodes.ACC_PUBLIC) == 0 || (method.access & Opcodes.ACC_STATIC) != 0) {
            return false;
        }
        Type[] args = Type.getArgumentTypes(method.desc);
        return args.length == 2
                && args[0].getSort() == Type.OBJECT
                && args[1].getSort() == Type.FLOAT
                && Type.VOID_TYPE.equals(Type.getReturnType(method.desc));
    }

    private static boolean hasHudCall(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if (call.getOpcode() == Opcodes.INVOKESTATIC
                        && HUD_OWNER.equals(call.owner)
                        && HUD_METHOD.equals(call.name)
                        && HUD_DESC.equals(call.desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static AbstractInsnNode firstRealInstruction(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int type = insn.getType();
            if (type != AbstractInsnNode.LABEL
                    && type != AbstractInsnNode.LINE
                    && type != AbstractInsnNode.FRAME) {
                return insn;
            }
        }
        return null;
    }

    private static boolean containsAll(String text, String... needles) {
        for (String needle : needles) {
            if (!text.contains(needle)) {
                return false;
            }
        }
        return true;
    }
}
