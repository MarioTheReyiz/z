package me.pewa.loader;

public interface BytecodeTransformer {
    String getName();

    boolean matches(TransformerContext context);

    byte[] transform(TransformerContext context) throws Exception;
}
