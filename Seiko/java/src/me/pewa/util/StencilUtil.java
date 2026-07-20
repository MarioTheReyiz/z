package me.pewa.util;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

public class StencilUtil {

    public static void initStencilToWrite() {
        GL11.glClear(GL11.GL_STENCIL_BUFFER_BIT);
        GL11.glEnable(GL11.GL_STENCIL_TEST);
        GL11.glStencilFunc(GL11.GL_ALWAYS, 1, 1);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
        GL11.glColorMask(false, false, false, false);
    }

    public static void readStencilBuffer(int ref) {
        GL11.glColorMask(true, true, true, true);
        GL11.glStencilFunc(GL11.GL_EQUAL, ref, 1);
        GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
    }

    public static void uninitStencilBuffer() {
        GL11.glDisable(GL11.GL_STENCIL_TEST);
    }

    public static void dispose() {
        GL11.glDisable(GL11.GL_STENCIL_TEST);
        GL11.glDisable(GL11.GL_ALPHA_TEST);
        GL11.glDisable(GL11.GL_BLEND);
    }
}
