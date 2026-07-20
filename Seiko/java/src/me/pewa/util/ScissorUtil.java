package me.pewa.util;

import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Unified Scissor & Resolution Manager.
 */
public class ScissorUtil {

    private static final Deque<ScissorState> scissorStack = new ArrayDeque<>();

    public static int actualHeight = -1;

    public static int getActualHeight() {
        if (actualHeight != -1) {
            return actualHeight;
        }
        return Display.getHeight();
    }

    public static void push(double x, double y, double width, double height) {
        int scaleFactor = getScaleFactor();
        int realX = (int) Math.round(x * scaleFactor);
        int realY = (int) Math.round((getScaledHeight() - (y + height)) * scaleFactor);
        int realW = (int) Math.round(width * scaleFactor);
        int realH = (int) Math.round(height * scaleFactor);

        if (realW < 0) realW = 0;
        if (realH < 0) realH = 0;

        if (!scissorStack.isEmpty()) {
            ScissorState parent = scissorStack.peek();
            int maxX = Math.min(realX + realW, parent.x + parent.w);
            int maxY = Math.min(realY + realH, parent.y + parent.h);
            realX = Math.max(realX, parent.x);
            realY = Math.max(realY, parent.y);
            realW = Math.max(0, maxX - realX);
            realH = Math.max(0, maxY - realY);
        }

        boolean wasEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        scissorStack.push(new ScissorState(realX, realY, realW, realH, wasEnabled));
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(realX, realY, realW, realH);
    }

    public static void pop() {
        if (scissorStack.isEmpty()) return;
        ScissorState removed = scissorStack.pop();
        if (scissorStack.isEmpty()) {
            if (!removed.wasEnabled) GL11.glDisable(GL11.GL_SCISSOR_TEST);
        } else {
            ScissorState parent = scissorStack.peek();
            GL11.glScissor(parent.x, parent.y, parent.w, parent.h);
        }
    }

    public static int getScaleFactor() {
        return 2;
    }

    public static int getScaledWidth() {
        return (int) Math.ceil((double) Display.getWidth() / getScaleFactor());
    }

    public static int getScaledHeight() {
        return (int) Math.ceil((double) getActualHeight() / getScaleFactor());
    }

    public static int mapMouseX(int rawMouseX) {
        int displayW = Display.getWidth();
        int scaledW = getScaledWidth();
        if (displayW <= 0) return 0;
        return (int) ((long) rawMouseX * scaledW / displayW);
    }

    public static int mapMouseY(int rawMouseY) {
        int actualH = getActualHeight();
        int scaledH = getScaledHeight();
        if (actualH <= 0) return 0;
        int mapped = (int) ((long) rawMouseY * scaledH / actualH);
        return scaledH - mapped - 1;
    }

    @Deprecated
    public static void enable(double x, double y, double width, double height) { push(x, y, width, height); }
    @Deprecated
    public static void disable() { pop(); }
    @Deprecated
    public static double getScaleFactorDouble() { return (double) getScaleFactor(); }

    private static class ScissorState {
        final int x, y, w, h;
        final boolean wasEnabled;
        ScissorState(int x, int y, int w, int h, boolean wasEnabled) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.wasEnabled = wasEnabled;
        }
    }
}
