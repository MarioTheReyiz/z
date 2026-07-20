package me.pewa.loader;

public final class TransformerContext {
    private final String className;
    private final byte[] originalBytes;
    private final byte[] currentBytes;
    private final int appliedCount;

    TransformerContext(String className, byte[] originalBytes, byte[] currentBytes, int appliedCount) {
        this.className = className;
        this.originalBytes = copy(originalBytes);
        this.currentBytes = copy(currentBytes);
        this.appliedCount = appliedCount;
    }

    public String getClassName() {
        return className;
    }

    public byte[] getOriginalBytes() {
        return copy(originalBytes);
    }

    public byte[] getCurrentBytes() {
        return copy(currentBytes);
    }

    public int getAppliedCount() {
        return appliedCount;
    }

    private static byte[] copy(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        byte[] out = new byte[bytes.length];
        System.arraycopy(bytes, 0, out, 0, bytes.length);
        return out;
    }
}
